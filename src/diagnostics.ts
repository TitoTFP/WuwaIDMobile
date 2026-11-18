import application from "../package.json";
import type { Dashboard, DiagnosticError, DiagnosticExportResult, DiagnosticReport, DiagnosticSection, Diagnostics } from "./model";

export const DIAGNOSTIC_FILENAME = "wuwaidmobile-diagnostics.json";
const HISTORY_LIMIT = 20;

function redactionPattern(secrets: readonly (string | null | undefined)[]): RegExp | undefined {
  const values = new Set(secrets.filter((value): value is string => !!value));
  if (!values.size) return undefined;
  // Longer UID literals win over the marker; the marker wins over its substrings.
  values.add("[REDACTED]");
  return new RegExp([...values].sort((a, b) => b.length - a.length)
    .map(value => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|"), "g");
}

function redact(message: string, pattern: RegExp | undefined): string {
  return pattern ? message.replace(pattern, "[REDACTED]") : message;
}

export class DiagnosticHistory {
  private readonly entries: DiagnosticError[] = [];

  record(operation: string, cause: unknown, secrets: readonly (string | null | undefined)[] = []): void {
    const message = redact(cause instanceof Error ? cause.message : String(cause), redactionPattern(secrets));
    if (this.entries.length === HISTORY_LIMIT) this.entries.shift();
    this.entries.push({ timestamp: Date.now(), operation, message: message.slice(0, 1024) });
  }

  snapshot(secrets: readonly (string | null | undefined)[] = []): DiagnosticError[] {
    const pattern = redactionPattern(secrets);
    return this.entries.map(entry => ({ ...entry, message: redact(entry.message, pattern).slice(0, 1024) }));
  }
}

export const diagnosticHistory = new DiagnosticHistory();

export function serializeDiagnostics(report: DiagnosticReport, history: DiagnosticHistory,
  secrets: readonly (string | null | undefined)[] = []): Diagnostics {
  const pattern = redactionPattern(secrets);
  const content = JSON.stringify({ ...report, errors: history.snapshot(secrets) }, (key, value: unknown) => {
    if ((key === "custom" || key === "customUid") && typeof value === "string" && value !== "redacted")
      return "[REDACTED]";
    if (["message", "error", "reason", "storageError"].includes(key) && typeof value === "string")
      return redact(value, pattern);
    return value;
  }, 2);
  return { filename: DIAGNOSTIC_FILENAME, content };
}

function checked(data: Record<string, unknown>): DiagnosticSection {
  return { status: "ok", data, error: null, reason: null };
}
function unchecked(reason: string): DiagnosticSection {
  return { status: "not_checked", data: null, error: null, reason };
}

export function mockDiagnostics(d: Dashboard): DiagnosticReport {
  const canRead = d.game.installed && d.access.granted;
  const resource: DiagnosticSection = !canRead
    ? unchecked("Game atau izin akses belum tersedia; resource belum diperiksa.")
    : d.resource.ready ? checked({ version: d.resource.version })
    : { status: "error", data: null, error: "Resource simulasi belum tersedia.", reason: null };
  return {
    schemaVersion: 1,
    generatedAt: Date.now(),
    mock: true,
    backend: "browser-mock",
    sections: {
      application: checked({ name: application.name, version: application.version, versionCode: null, buildType: "browser-mock" }),
      device: checked({ os: "browser", userAgent: navigator.userAgent, platform: navigator.platform }),
      probe: checked({ gameInstalled: d.game.installed, gameVersion: d.game.version, gamePackage: d.game.packageName,
        shizukuInstalled: d.shizuku.installed, shizukuRunning: d.shizuku.running, shizukuGranted: d.shizuku.granted,
        axmanager: { ...d.axmanager },
        accessMethod: d.access.method, accessGranted: d.access.granted,
        rootReadable: canRead && d.resource.ready, rootWritable: canRead && d.storageWritable, storageError: d.blocker }),
      resource,
      patch: resource.status === "ok" ? checked({ ...d.patch, uidCustomized: !!d.uid.custom })
        : unchecked("Resource belum berhasil diperiksa."),
      release: d.release ? checked({ ...d.release }) : unchecked("Rilis PAK tidak tersedia pada simulasi browser."),
    },
    privacy: { customUid: "redacted", errorHistory: "session", maximumErrors: 20 },
    errors: [],
  };
}

export async function exportInBrowser(action: "save" | "share", report: Diagnostics): Promise<DiagnosticExportResult> {
  const blob = new Blob([report.content], { type: "application/json" });
  if (action === "save") {
    const url = URL.createObjectURL(blob);
    try {
      const link = document.createElement("a");
      link.href = url;
      link.download = report.filename;
      link.click();
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    // A click starts a download; the browser does not confirm filesystem persistence.
    return { status: "download_started" };
  }
  const file = new File([blob], report.filename, { type: "application/json" });
  if (!navigator.canShare?.({ files: [file] }) || !navigator.share)
    return { status: "failed", message: "Browser ini tidak mendukung berbagi file. Pilih Simpan." };
  try {
    await navigator.share({ files: [file], title: "Diagnostik WuwaID Mobile" });
    return { status: "share_opened" };
  } catch (error) {
    return error instanceof DOMException && error.name === "AbortError"
      ? { status: "cancelled" }
      : { status: "failed", message: error instanceof Error ? error.message : String(error) };
  }
}

export function diagnosticExportMessage(result: DiagnosticExportResult): string {
  switch (result.status) {
    case "saved": return "Diagnostik disimpan.";
    case "cancelled": return "Ekspor diagnostik dibatalkan.";
    case "share_opened": return "Menu berbagi dibuka.";
    case "download_started": return "Unduhan diagnostik dimulai.";
    case "failed": return result.message ?? "Ekspor diagnostik gagal.";
  }
}
