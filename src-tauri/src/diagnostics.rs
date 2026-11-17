use serde::Serialize;
use serde_json::{json, Value};
use tauri::AppHandle;

use crate::pak::{
    installer,
    model::{DeviceProbe, PakResult, Release},
    storage::Storage,
};

#[cfg(target_os = "android")]
use tauri::{
    plugin::{Builder, PluginHandle, TauriPlugin},
    Manager, Runtime,
};

#[cfg(target_os = "android")]
struct DiagnosticExporter<R: Runtime>(PluginHandle<R>);

#[cfg(target_os = "android")]
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("diagnostics")
        .setup(|app, api| {
            let plugin =
                api.register_android_plugin("com.titotfp.wuwaid.diagnostics", "DiagnosticsPlugin")?;
            app.manage(DiagnosticExporter(plugin));
            Ok(())
        })
        .build()
}

fn checked(data: impl Serialize) -> Value {
    json!({ "status": "ok", "data": data, "error": null, "reason": null })
}

fn failed(error: String) -> Value {
    json!({ "status": "error", "data": null, "error": error, "reason": null })
}

fn unchecked(reason: &str) -> Value {
    json!({ "status": "not_checked", "data": null, "error": null, "reason": reason })
}

/// Independent sections survive transport/discovery/inspection failures. No mutation or latest request.
fn collect(
    mut environment: PakResult<Value>,
    release: PakResult<Option<Release>>,
    probe: Option<PakResult<DeviceProbe>>,
    storage: Option<&dyn Storage>,
    generated_at: u64,
) -> Value {
    let backend = match &probe {
        Some(Ok(probe)) => format!("android-{}", probe.access_method),
        Some(Err(_)) => "android".into(),
        None => "desktop-unsupported".into(),
    };
    let mut sections = json!({});
    for name in ["application", "device"] {
        sections[name] = match &mut environment {
            Ok(value) => value
                .as_object_mut()
                .and_then(|object| object.remove(name))
                .unwrap_or_else(|| failed(format!("Metadata {name} tidak tersedia."))),
            Err(error) => failed(error.clone()),
        };
    }
    sections["release"] = match release {
        Ok(Some(release)) => checked(release),
        Ok(None) => {
            unchecked("Cache rilis PAK sesi belum tersedia; diagnostik tidak meminta jaringan.")
        }
        Err(error) => failed(error),
    };
    sections["resource"] = unchecked("Probe belum tersedia.");
    sections["patch"] = unchecked("Resource belum diperiksa.");
    let mut custom_uid = None;
    sections["probe"] = match probe {
        None => unchecked("Akses game hanya didukung pada Android."),
        Some(Err(error)) => failed(error),
        Some(Ok(probe)) => {
            if !probe.game_installed || !probe.access_granted || !probe.root_readable {
                sections["resource"] = unchecked(if !probe.game_installed {
                    "Game belum terpasang."
                } else if !probe.access_granted {
                    "Metode akses terpilih belum mendapat izin."
                } else {
                    "Resource tidak dapat dibaca melalui metode akses terpilih."
                });
            } else if let Some(storage) = storage {
                match installer::discover(storage) {
                    Err(error) => sections["resource"] = failed(error),
                    Ok(layout) => {
                        sections["resource"] = checked(json!({
                            "version": layout.version, "signature": layout.signature,
                        }));
                        sections["patch"] = match installer::inspect(storage, &layout) {
                            Err(error) => failed(error),
                            Ok(mut status) => {
                                custom_uid = status.custom_uid.take();
                                let mut section = checked(status);
                                section["data"]["uidCustomized"] = json!(custom_uid.is_some());
                                section
                            }
                        };
                    }
                }
            }
            checked(probe)
        }
    };
    if let Some(uid) = custom_uid.filter(|uid| !uid.is_empty()) {
        redact_messages(&mut sections, &uid);
    }
    json!({
        "schemaVersion": 1, "generatedAt": generated_at, "mock": false, "backend": backend,
        "sections": sections,
        "privacy": { "customUid": "redacted", "errorHistory": "session", "maximumErrors": 20 },
        "errors": [],
    })
}

// Only free text is redacted: UID text such as "ok" must not corrupt protocol enums.
fn redact_messages(value: &mut Value, uid: &str) {
    match value {
        Value::Object(object) => {
            for (key, value) in object {
                if matches!(
                    key.as_str(),
                    "message" | "error" | "reason" | "storageError"
                ) {
                    if let Value::String(message) = value {
                        if message.contains(uid) {
                            *message = message.replace(uid, "[REDACTED]");
                        }
                    }
                } else {
                    redact_messages(value, uid);
                }
            }
        }
        Value::Array(array) => {
            for value in array {
                redact_messages(value, uid);
            }
        }
        _ => {}
    }
}

#[tauri::command]
pub async fn wuwa_diagnostics(app: AppHandle, request_id: u64) -> PakResult<Value> {
    tauri::async_runtime::spawn_blocking(move || {
        let _request = crate::trace::Request::enter(request_id);
        let _phase = crate::trace::Span::new("diagnostics");
        #[cfg(target_os = "android")]
        {
            let environment = app.state::<DiagnosticExporter<tauri::Wry>>().0
                .run_mobile_plugin("environment", json!({})).map_err(|error| error.to_string());
            let storage = app.state::<crate::android::MobileBridge<tauri::Wry>>();
            Ok(collect(environment, crate::native::current_release(&app), Some(storage.probe()),
                Some(&*storage), crate::native::timestamp()))
        }
        #[cfg(not(target_os = "android"))]
        {
            let environment = json!({
                "application": checked(json!({ "name": app.package_info().name,
                    "version": app.package_info().version.to_string(),
                    "buildType": if cfg!(debug_assertions) { "debug" } else { "release" } })),
                "device": checked(json!({ "os": std::env::consts::OS, "architecture": std::env::consts::ARCH })),
            });
            Ok(collect(Ok(environment), crate::native::current_release(&app), None, None,
                crate::native::timestamp()))
        }
    }).await.map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn wuwa_export_diagnostics(
    app: AppHandle,
    action: String,
    content: String,
) -> PakResult<Value> {
    if !matches!(action.as_str(), "save" | "share") {
        return Err("Tindakan diagnostik tidak dikenal.".into());
    }
    #[cfg(target_os = "android")]
    {
        tauri::async_runtime::spawn_blocking(move || {
            app.state::<DiagnosticExporter<tauri::Wry>>()
                .0
                .run_mobile_plugin(
                    "exportReport",
                    json!({ "action": action, "content": content }),
                )
                .map_err(|error| error.to_string())
        })
        .await
        .map_err(|error| error.to_string())?
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, content);
        Ok(
            json!({ "status": "failed", "message": "Simpan/bagikan native hanya didukung pada Android." }),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pak::local_storage::LocalStorage;
    use std::fs;

    fn environment() -> PakResult<Value> {
        Ok(json!({
            "application": checked(json!({ "version": "0.4.0", "versionCode": 8 })),
            "device": checked(json!({ "sdk": 28, "abis": ["arm64-v8a"] })),
        }))
    }

    fn probe(granted: bool) -> DeviceProbe {
        DeviceProbe {
            game_installed: true,
            game_version: "3.7.0".into(),
            game_package: crate::pak::model::GAME_PACKAGE.into(),
            shizuku_installed: false,
            shizuku_running: false,
            shizuku_granted: false,
            axmanager: crate::pak::model::AxManagerStatus::default(),
            access_method: "root".into(),
            access_granted: granted,
            root_readable: granted,
            root_writable: granted,
            storage_error: None,
        }
    }

    fn fixture() -> (tempfile::TempDir, LocalStorage) {
        let root = tempfile::tempdir().unwrap();
        for directory in ["Mount", "Resource", "Lang_en"] {
            fs::create_dir_all(root.path().join("3.7.0").join(directory)).unwrap();
        }
        for (name, bytes) in [
            ("ResManifest", "manifest"),
            ("Mount/MountResource.txt", "::Mount::\n::Del::\n"),
            ("Lang_en/pakchunk0-Lang_en_1000_P.pak", "official pak"),
            ("Lang_en/pakchunk0-Lang_en_1000_P.sig", "official signature"),
        ] {
            fs::write(root.path().join("3.7.0").join(name), bytes).unwrap();
        }
        let storage = LocalStorage::new(root.path()).unwrap();
        (root, storage)
    }

    #[test]
    fn failed_probe_preserves_metadata_without_inventing_patch_absence() {
        let report = collect(
            environment(),
            Ok(None),
            Some(Err("bridge disconnected".into())),
            None,
            123,
        );
        let decoded: Value =
            serde_json::from_str(&serde_json::to_string(&report).unwrap()).unwrap();
        assert_eq!(decoded["schemaVersion"], 1);
        assert_eq!(decoded["generatedAt"], 123);
        assert_eq!(decoded["sections"]["application"]["data"]["versionCode"], 8);
        assert_eq!(decoded["sections"]["device"]["data"]["sdk"], 28);
        assert_eq!(decoded["sections"]["probe"]["error"], "bridge disconnected");
        assert_eq!(decoded["sections"]["patch"]["status"], "not_checked");
        assert!(decoded["sections"]["patch"]["data"].is_null());
    }

    #[test]
    fn denied_access_is_unknown_not_a_negative_installation_result() {
        let report = collect(environment(), Ok(None), Some(Ok(probe(false))), None, 0);
        assert_eq!(report["backend"], "android-root");
        assert_eq!(report["sections"]["probe"]["data"]["accessGranted"], false);
        for name in ["resource", "patch"] {
            assert_eq!(report["sections"][name]["status"], "not_checked");
            assert!(report["sections"][name]["data"].is_null());
        }
    }

    #[test]
    fn environment_failure_does_not_discard_successful_storage_inspection() {
        let (_root, storage) = fixture();
        let report = collect(
            Err("metadata unavailable".into()),
            Ok(None),
            Some(Ok(probe(true))),
            Some(&storage),
            0,
        );
        assert_eq!(report["sections"]["application"]["status"], "error");
        assert_eq!(report["sections"]["device"]["status"], "error");
        assert_eq!(report["sections"]["resource"]["data"]["version"], "3.7.0");
        assert_eq!(report["sections"]["patch"]["status"], "ok");
        assert_eq!(report["sections"]["patch"]["data"]["installed"], false);
    }

    #[test]
    fn failed_discovery_preserves_probe_and_does_not_claim_patch_was_checked() {
        let root = tempfile::tempdir().unwrap();
        let storage = LocalStorage::new(root.path()).unwrap();
        let report = collect(
            environment(),
            Ok(None),
            Some(Ok(probe(true))),
            Some(&storage),
            0,
        );
        assert_eq!(report["sections"]["probe"]["status"], "ok");
        assert_eq!(report["sections"]["resource"]["status"], "error");
        assert_eq!(report["sections"]["patch"]["status"], "not_checked");
        assert!(report["sections"]["patch"]["data"].is_null());
    }

    #[cfg(unix)]
    #[test]
    fn unsafe_patch_paths_fail_only_inspection_and_preserve_discovered_resource() {
        let (root, storage) = fixture();
        let outside = tempfile::tempdir().unwrap();
        let owned = root
            .path()
            .join("3.7.0")
            .join(crate::pak::model::PATCH_DIRECTORY);
        std::os::unix::fs::symlink(outside.path(), owned).unwrap();
        let report = collect(
            environment(),
            Ok(None),
            Some(Ok(probe(true))),
            Some(&storage),
            0,
        );
        assert_eq!(report["sections"]["resource"]["status"], "ok");
        assert_eq!(report["sections"]["patch"]["status"], "error");
        assert!(report["sections"]["patch"]["data"].is_null());
    }

    #[test]
    fn redaction_preserves_protocol_enums_when_uid_matches_an_enum() {
        let mut report = json!({ "status": "ok", "data": { "message": "UID ok failed", "accessMethod": "root" } });
        redact_messages(&mut report, "ok");
        assert_eq!(report["status"], "ok");
        assert_eq!(report["data"]["accessMethod"], "root");
        assert_eq!(report["data"]["message"], "UID [REDACTED] failed");
    }
}
