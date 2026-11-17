use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
#[cfg(target_os = "android")]
use tauri::Emitter;
use tauri::{AppHandle, Manager};

#[cfg(any(target_os = "android", test))]
use crate::pak::model::DeviceProbe;
#[cfg(not(target_os = "android"))]
use crate::pak::model::GAME_PACKAGE;
use crate::pak::model::{AxManagerStatus, InstallStatus, PakResult, Release, ResourceLayout};
#[cfg(target_os = "android")]
use crate::pak::{installer, release, uid};
use crate::trace::{Request, Span};

#[derive(Default)]
pub struct NativeState {
    busy: AtomicBool,
    release: Mutex<Option<Release>>,
}

struct BusyGuard(Arc<NativeState>);
impl BusyGuard {
    fn acquire(state: Arc<NativeState>) -> PakResult<Self> {
        state
            .busy
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .map_err(|_| "Operasi lain masih berjalan.".to_string())?;
        Ok(Self(state))
    }
}
impl Drop for BusyGuard {
    fn drop(&mut self) {
        self.0.busy.store(false, Ordering::Release);
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Dashboard {
    mode: &'static str,
    platform_supported: bool,
    storage_writable: bool,
    blocker: Option<String>,
    release: Option<Release>,
    uid_available: bool,
    game: Game,
    resource: Resource,
    shizuku: Shizuku,
    axmanager: AxManagerStatus,
    access: Access,
    patch: InstallStatus,
    uid: Uid,
    last_checked: u64,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Game {
    installed: bool,
    version: String,
    package_name: String,
}
#[derive(Serialize)]
struct Resource {
    ready: bool,
    version: String,
}
#[derive(Deserialize, Serialize)]
struct Shizuku {
    installed: bool,
    running: bool,
    granted: bool,
}
#[derive(Deserialize, Serialize)]
struct Access {
    method: String,
    granted: bool,
}
#[derive(Deserialize, Serialize)]
pub struct AccessSnapshot {
    shizuku: Shizuku,
    axmanager: AxManagerStatus,
    access: Access,
}

#[derive(Serialize)]
struct Uid {
    original: &'static str,
    custom: Option<String>,
}

pub(crate) fn timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn empty_status() -> InstallStatus {
    InstallStatus {
        installed: false,
        healthy: false,
        recovery_pending: false,
        legacy_pending: false,
        version: String::new(),
        message: None,
        custom_uid: None,
    }
}
pub(crate) fn current_release(app: &AppHandle) -> PakResult<Option<Release>> {
    app.state::<Arc<NativeState>>()
        .release
        .lock()
        .map(|value| value.clone())
        .map_err(|_| "State rilis tidak dapat dibaca.".to_string())
}
fn fetch_release(app: &AppHandle) -> PakResult<Release> {
    let result = crate::pak::release::latest();
    let state = app.state::<Arc<NativeState>>();
    *state
        .release
        .lock()
        .map_err(|_| "State rilis tidak dapat diperbarui.".to_string())? =
        result.as_ref().ok().cloned();
    result
}

#[cfg(target_os = "android")]
fn bridge(app: &AppHandle) -> tauri::State<'_, crate::android::MobileBridge<tauri::Wry>> {
    app.state::<crate::android::MobileBridge<tauri::Wry>>()
}
#[cfg(any(target_os = "android", test))]
fn physical_prerequisites(probe: &DeviceProbe) -> PakResult<()> {
    if !probe.game_installed {
        return Err("Wuthering Waves Global atau Galaxy Store belum terpasang.".into());
    }
    if probe.access_method == "axmanager"
        && (!probe.axmanager.installed || !probe.axmanager.supported || !probe.axmanager.running)
    {
        return Err("Aktifkan AxManager 3.0.0+ pada Android 8+ user utama terlebih dahulu.".into());
    }
    if !probe.access_granted {
        return Err(match probe.access_method.as_str() {
            "root" => "Berikan izin root terlebih dahulu.",
            "axmanager" => {
                "Hubungkan storage melalui tombol Hubungkan di Perawatan terlebih dahulu."
            }
            _ => "Jalankan Shizuku dan berikan izin terlebih dahulu.",
        }
        .into());
    }
    if !probe.root_readable || !probe.root_writable {
        return Err("Metode akses terpilih tidak dapat membaca/menulis resource game. Periksa izin dan selesaikan unduhan resource di game.".into());
    }
    Ok(())
}
#[cfg(target_os = "android")]
fn compatible_line(version: &str, expected: &str) -> bool {
    let parse = |value: &str| -> Option<[u32; 3]> {
        let parts: Vec<u32> = value
            .split('.')
            .map(str::parse)
            .collect::<Result<_, _>>()
            .ok()?;
        parts.try_into().ok()
    };
    match (parse(version), parse(expected)) {
        (Some(actual), Some(expected)) => actual[..2] == expected[..2],
        _ => false,
    }
}
#[cfg(target_os = "android")]
fn check_compatibility(
    probe: &DeviceProbe,
    layout: &ResourceLayout,
    release: &Release,
) -> PakResult<()> {
    if !compatible_line(&probe.game_version, &release.game_version)
        || !compatible_line(&layout.version, &release.game_version)
    {
        return Err(format!(
            "Rilis {} tidak sesuai dengan game {} / resource {}. Pemasangan dibatalkan.",
            release.tag, probe.game_version, layout.version
        ));
    }
    Ok(())
}

fn load_dashboard(
    app: &AppHandle,
    refresh_release: bool,
    completed: Option<(ResourceLayout, InstallStatus)>,
) -> PakResult<Dashboard> {
    let _response = Span::new("dashboard.response");
    let release_result = if refresh_release {
        fetch_release(app).map(Some)
    } else {
        current_release(app)
    };
    let (selected_release, release_error) = match release_result {
        Ok(value) => (value, None),
        Err(error) => (None, Some(error)),
    };
    #[cfg(not(target_os = "android"))]
    {
        let _ = completed;
        Ok(Dashboard {
            mode: "native", platform_supported: false, storage_writable: false,
            blocker: Some(match release_error {
                Some(error) => format!("Pemasangan .pak memerlukan Android dengan Shizuku, Root, atau AxManager. Rilis latest belum dapat dibaca: {error}"),
                None => "Pemasangan .pak memerlukan Android dengan Shizuku, Root, atau AxManager. Desktop tidak menulis direktori game.".into(),
            }),
            release: selected_release, uid_available: false,
            game: Game { installed: false, version: String::new(), package_name: GAME_PACKAGE.into() },
            resource: Resource { ready: false, version: String::new() },
            shizuku: Shizuku { installed: false, running: false, granted: false }, patch: empty_status(),
            axmanager: AxManagerStatus::default(),
            access: Access { method: "shizuku".into(), granted: false },
            uid: Uid { original: "", custom: None }, last_checked: timestamp(),
        })
    }
    #[cfg(target_os = "android")]
    {
        let storage = bridge(app);
        let probe = storage.probe()?;
        let mut blocker = release_error.or_else(|| probe.storage_error.clone());
        let mut resource = Resource {
            ready: false,
            version: String::new(),
        };
        let mut patch = empty_status();
        if probe.access_granted && probe.root_readable {
            // A successful transaction already returned a fully inspected terminal state.
            // This is scoped to this response; explicit refresh still discovers and hashes.
            let (layout, status) = match completed {
                Some((layout, status)) => (Ok(layout), Some(status)),
                None => (installer::discover(&*storage), None),
            };
            match layout {
                Ok(layout) => {
                    resource = Resource {
                        ready: true,
                        version: layout.version.clone(),
                    };
                    match status
                        .map(Ok)
                        .unwrap_or_else(|| installer::inspect(&*storage, &layout))
                    {
                        Ok(status) => patch = status,
                        Err(error) => {
                            patch.message = Some(error.clone());
                            blocker = Some(error);
                        }
                    }
                }
                Err(error) => blocker = Some(error),
            }
        }
        let custom_uid = patch.custom_uid.take();
        Ok(Dashboard {
            mode: "native",
            platform_supported: true,
            storage_writable: probe.root_writable,
            blocker,
            release: selected_release,
            uid_available: true,
            game: Game {
                installed: probe.game_installed,
                version: probe.game_version,
                package_name: probe.game_package,
            },
            resource,
            shizuku: Shizuku {
                installed: probe.shizuku_installed,
                running: probe.shizuku_running,
                granted: probe.shizuku_granted,
            },
            axmanager: probe.axmanager,
            access: Access {
                method: probe.access_method,
                granted: probe.access_granted,
            },
            patch,
            uid: Uid {
                original: "",
                custom: custom_uid,
            },
            last_checked: timestamp(),
        })
    }
}

#[tauri::command]
pub fn wuwa_trace_enabled() -> bool {
    cfg!(debug_assertions)
}

#[tauri::command]
pub fn wuwa_ui_trace(request_id: u64, event: String, frontend_us: u64) -> PakResult<()> {
    if !cfg!(debug_assertions) {
        return Ok(());
    }
    let _request = Request::enter(request_id);
    match event.as_str() {
        "busy_start" => crate::trace::mark("ui.busy", "begin", frontend_us),
        "busy_end" => crate::trace::mark("ui.busy", "end", frontend_us),
        "response" => crate::trace::mark("ui.response", "received", frontend_us),
        _ => return Err("Unsupported trace event".into()),
    }
    Ok(())
}

#[tauri::command]
pub async fn wuwa_load(app: AppHandle, request_id: u64) -> PakResult<Dashboard> {
    tauri::async_runtime::spawn_blocking(move || {
        let _request = Request::enter(request_id);
        let _load = Span::new("dashboard.load");
        load_dashboard(&app, true, None)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn wuwa_access_status(app: AppHandle, request_id: u64) -> PakResult<AccessSnapshot> {
    let guard = BusyGuard::acquire(Arc::clone(app.state::<Arc<NativeState>>().inner()))?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        let _request = Request::enter(request_id);
        let _status = Span::new("access.status");
        #[cfg(target_os = "android")]
        {
            bridge(&app).access_status()
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = app;
            Err("Status akses hanya tersedia di Android.".into())
        }
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn wuwa_run(
    app: AppHandle,
    operation: String,
    uid_text: Option<String>,
    request_id: u64,
) -> PakResult<Dashboard> {
    let guard = BusyGuard::acquire(Arc::clone(app.state::<Arc<NativeState>>().inner()))?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        let _request = Request::enter(request_id);
        let _run = Span::new("operation");
        #[cfg(not(target_os = "android"))]
        {
            let _ = (&app, operation, uid_text);
            Err("Pemasangan nyata memerlukan Android dengan Shizuku, Root, atau AxManager.".into())
        }
        #[cfg(target_os = "android")]
        {
            let storage = bridge(&app);
            let completed = match operation.as_str() {
                "request_permission" | "request_root" | "request_axmanager" | "use_root"
                | "use_shizuku" | "use_axmanager" | "launch_game" => {
                    storage.control(&operation)?;
                    None
                }
                "install" | "repair" | "uninstall" | "apply_uid" | "restore_uid" => {
                    let probe = storage.probe()?;
                    physical_prerequisites(&probe)?;
                    let layout = installer::discover(&*storage)?;
                    let status = installer::inspect(&*storage, &layout)?;
                    let custom_uid = match operation.as_str() {
                        "apply_uid" | "restore_uid" => {
                            if status.recovery_pending || (status.installed && !status.healthy) {
                                return Err("Jalankan Perbaiki di Perawatan.".into());
                            }
                            if operation == "restore_uid" && !status.installed {
                                return Err("Tidak ada pemasangan UID untuk direset.".into());
                            }
                            if operation == "apply_uid" {
                                Some(
                                    uid::validate_text(
                                        uid_text.as_deref().ok_or("Masukkan teks UID kustom.")?,
                                    )?
                                    .to_owned(),
                                )
                            } else {
                                None
                            }
                        }
                        _ => status.custom_uid,
                    };
                    let progress = |value| {
                        let _ = app.emit("wuwa-progress", value);
                    };
                    let completed = if operation == "uninstall" {
                        installer::uninstall(&*storage, &layout, &progress)?
                    } else if operation == "repair" && status.recovery_pending {
                        installer::recover(&*storage, &layout, &progress)?
                    } else if operation == "repair" && status.legacy_pending {
                        installer::migrate_legacy(&*storage, &layout, &progress)?
                    } else {
                        let release = fetch_release(&app)?;
                        check_compatibility(&probe, &layout, &release)?;
                        let cache = app
                            .path()
                            .app_cache_dir()
                            .map_err(|e| e.to_string())?
                            .join("release-paks");
                        let pak = release::download(&release, &cache, &progress)?;
                        if operation == "repair" {
                            installer::repair(
                                &*storage,
                                &layout,
                                &release,
                                pak.path(),
                                custom_uid.as_deref(),
                                &progress,
                            )?
                        } else {
                            installer::install(
                                &*storage,
                                &layout,
                                &release,
                                pak.path(),
                                custom_uid.as_deref(),
                                &progress,
                            )?
                        }
                    };
                    Some((layout, completed))
                }
                _ => return Err("Operasi tidak dikenal.".into()),
            };
            load_dashboard(&app, false, completed)
        }
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn wuwa_open_setup(app: AppHandle, target: String) -> PakResult<()> {
    tauri::async_runtime::spawn_blocking(move || {
        #[cfg(not(target_os = "android"))]
        {
            let _ = (&app, target);
            Err("Pengaturan metode akses/game hanya tersedia di Android.".into())
        }
        #[cfg(target_os = "android")]
        {
            let action = match target.as_str() {
                "game" => "open_store",
                "shizuku" => "open_shizuku",
                "axmanager" => "open_axmanager",
                _ => return Err("Target pengaturan tidak dikenal.".into()),
            };
            bridge(&app).control(action)
        }
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(test)]
mod access_tests {
    use super::physical_prerequisites;
    use crate::pak::model::{AxManagerStatus, DeviceProbe};

    fn root_probe() -> DeviceProbe {
        DeviceProbe {
            game_installed: true,
            game_version: "3.7.0".into(),
            game_package: crate::pak::model::GAME_PACKAGE.into(),
            shizuku_installed: false,
            shizuku_running: false,
            shizuku_granted: false,
            axmanager: AxManagerStatus::default(),
            access_method: "root".into(),
            access_granted: false,
            root_readable: true,
            root_writable: true,
            storage_error: None,
        }
    }

    #[test]
    fn explicit_root_grant_unlocks_storage_without_shizuku() {
        let mut probe = root_probe();
        assert!(physical_prerequisites(&probe).is_err());
        probe.access_granted = true;
        assert!(physical_prerequisites(&probe).is_ok());
        probe.access_granted = false;
        assert!(physical_prerequisites(&probe).is_err());
    }

    #[test]
    fn other_transport_permission_cannot_unlock_selected_method() {
        let mut probe = root_probe();
        probe.shizuku_running = true;
        probe.shizuku_granted = true;
        assert!(physical_prerequisites(&probe).is_err());
        probe.access_method = "shizuku".into();
        assert!(physical_prerequisites(&probe).is_err());
        probe.access_granted = true;
        assert!(physical_prerequisites(&probe).is_ok());
    }

    #[test]
    fn axmanager_requires_its_own_live_supported_session_without_shizuku() {
        let mut probe = root_probe();
        probe.access_method = "axmanager".into();
        probe.axmanager = AxManagerStatus {
            installed: true,
            supported: true,
            running: true,
            version: "2.0.1".into(),
        };
        probe.shizuku_running = true;
        probe.shizuku_granted = true;
        assert!(physical_prerequisites(&probe).is_err());
        probe.shizuku_running = false;
        probe.shizuku_granted = false;
        probe.access_granted = true;
        assert!(physical_prerequisites(&probe).is_ok());
        probe.axmanager.running = false;
        assert!(physical_prerequisites(&probe).is_err());
        probe.axmanager.running = true;
        probe.axmanager.supported = false;
        assert!(physical_prerequisites(&probe).is_err());
        probe.axmanager.supported = true;
        probe.axmanager.installed = false;
        assert!(physical_prerequisites(&probe).is_err());
        probe.axmanager.installed = true;
        probe.root_writable = false;
        assert!(physical_prerequisites(&probe).is_err());
    }

    #[test]
    fn root_does_not_bypass_game_and_resource_permissions() {
        let mut probe = root_probe();
        probe.access_granted = true;
        probe.game_installed = false;
        assert!(physical_prerequisites(&probe).is_err());
        probe.game_installed = true;
        probe.root_readable = false;
        assert!(physical_prerequisites(&probe).is_err());
        probe.root_readable = true;
        probe.root_writable = false;
        assert!(physical_prerequisites(&probe).is_err());
        probe.root_writable = true;
        assert!(physical_prerequisites(&probe).is_ok());
    }
}
