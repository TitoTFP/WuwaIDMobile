#[cfg(target_os = "android")]
mod android;
mod app_update;
mod diagnostics;
mod native;
mod pak;
mod trace;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        .manage(std::sync::Arc::new(native::NativeState::default()))
        .invoke_handler(tauri::generate_handler![
            native::wuwa_bootstrap,
            native::wuwa_release,
            native::wuwa_inspect,
            native::wuwa_access_status,
            native::wuwa_run,
            native::wuwa_open_setup,
            diagnostics::wuwa_diagnostics,
            diagnostics::wuwa_export_diagnostics,
            native::wuwa_trace_enabled,
            native::wuwa_ui_trace,
            app_update::wuwa_app_update
        ]);
    #[cfg(target_os = "android")]
    let builder = builder
        .plugin(android::init())
        .plugin(app_update::init())
        .plugin(diagnostics::init());
    builder
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
