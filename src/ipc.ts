import { Channel, invoke, isTauri } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { diagnosticHistory, exportInBrowser, serializeDiagnostics } from "./diagnostics";
import type {
  AppUpdateStatus,
  AccessSnapshot,
  BootstrapSnapshot,
  InspectionSnapshot,
  OperationResult,
  ReleaseInfo,
  Dashboard,
  Diagnostics,
  DiagnosticReport,
  DiagnosticExportResult,
  Operation,
  Progress,
  Scenario,
} from "./model";

// Decide before importing mockIPC: mocks themselves populate Tauri's window internals.
const native = isTauri();
const progressListeners = new Set<(progress: Progress) => void>();
let recording = false;
let nextRequest = 0;
let busyRequest: number | undefined;
let activeUid: string | null = null;
function progressReceived(progress: Progress) {
  for (const listener of progressListeners) listener(progress);
}
const ready = native
  ? Promise.all([
      listen<Progress>("wuwa-progress", (event) => progressReceived(event.payload)),
      invoke<boolean>("wuwa_trace_enabled").then((enabled) => { recording = enabled; }),
    ])
  : import("./mock-ipc").then((mock) =>
      mock.subscribeProgress(progressReceived),
    );

async function command<T>(
  name: string,
  args?: Record<string, unknown>,
): Promise<T> {
  const uidText = typeof args?.uidText === "string" ? args.uidText : undefined;
  try {
    await ready;
    const requestId = busyRequest ?? ++nextRequest;
    const result = await invoke<T>(name, { ...args, requestId });
    if (name === "wuwa_inspect" || name === "wuwa_set_scenario")
      activeUid = (result as InspectionSnapshot).uid.custom;
    if (name === "wuwa_run") {
      const outcome = result as OperationResult;
      if (outcome.kind === "inspection") activeUid = outcome.snapshot.uid.custom;
    }
    if (recording) await uiTrace(requestId, "response");
    return result;
  } catch (error) {
    const operation = typeof args?.operation === "string" ? args.operation
      : typeof args?.action === "string" ? `${name}:${args.action}` : name;
    diagnosticHistory.record(operation, error, [activeUid, uidText, uidText?.trim()]);
    throw error;
  }
}

async function uiTrace(requestId: number, event: "busy_start" | "busy_end" | "response", startedUs?: number) {
  if (!recording) return;
  const frontendUs = startedUs ?? Math.round(performance.now() * 1000);
  performance.mark(`wuwaid:${requestId}:${event}`, { startTime: frontendUs / 1000 });
  // Recording must never turn a completed operation into a failed one.
  await invoke<void>("wuwa_ui_trace", { requestId, event, frontendUs }).catch(() => {});
}

export const api = {
  mode: native ? "native" as const : "mock" as const,
  async beginBusy() {
    const startedUs = recording ? Math.round(performance.now() * 1000) : undefined;
    await ready;
    busyRequest = ++nextRequest;
    await uiTrace(busyRequest, "busy_start", startedUs);
  },
  async endBusy() {
    const requestId = busyRequest;
    busyRequest = undefined;
    if (requestId !== undefined) await uiTrace(requestId, "busy_end");
  },
  bootstrap: () => command<BootstrapSnapshot>("wuwa_bootstrap"),
  release: () => command<ReleaseInfo>("wuwa_release"),
  inspect: () => command<InspectionSnapshot>("wuwa_inspect"),
  accessStatus: () => command<AccessSnapshot>("wuwa_access_status"),
  setScenario(scenario: Scenario) {
    if (native)
      return Promise.reject(
        new Error("Skenario simulasi hanya tersedia di browser."),
      );
    return command<Dashboard>("wuwa_set_scenario", { scenario });
  },
  run: (operation: Operation, uidText?: string) =>
    command<OperationResult>("wuwa_run", { operation, uidText }),
  async diagnostics(): Promise<Diagnostics> {
    const report = await command<DiagnosticReport>("wuwa_diagnostics");
    return serializeDiagnostics(report, diagnosticHistory, [activeUid]);
  },
  async exportDiagnostics(action: "save" | "share"): Promise<DiagnosticExportResult> {
    const report = await api.diagnostics();
    const result = native
      ? await command<DiagnosticExportResult>("wuwa_export_diagnostics", { action, content: report.content })
      : await exportInBrowser(action, report);
    if (result.status === "failed")
      diagnosticHistory.record(`diagnostics:${action}`, result.message ?? "Ekspor gagal.", [activeUid]);
    return result;
  },
  openSetup: (target: "game" | "shizuku" | "axmanager") =>
    command<void>("wuwa_open_setup", { target }),
  appUpdate(action: "status" | "check" | "download" | "install", onProgress?: (progress: Progress) => void): Promise<AppUpdateStatus> {
    if (!native) return Promise.resolve({
      supported: false, currentVersion: null, currentVersionCode: null,
      availableVersion: null, size: 0, ready: false, checked: false,
      message: "Tersedia di aplikasi Android versi rilis.",
    });
    const progress = new Channel<Progress>();
    progress.onmessage = (value) => onProgress?.(value);
    return command<AppUpdateStatus>("wuwa_app_update", { action, progress });
  },
  subscribeProgress(listener: (progress: Progress) => void): () => void {
    progressListeners.add(listener);
    return () => progressListeners.delete(listener);
  },
};
