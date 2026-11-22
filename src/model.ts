export type Section = "home" | "customize" | "tools";
export type Scenario =
  | "ready"
  | "installed"
  | "game_missing"
  | "unsupported"
  | "resource_missing"
  | "shizuku_missing"
  | "shizuku_off"
  | "permission"
  | "root_permission"
  | "root_ready"
  | "axmanager_missing"
  | "axmanager_off"
  | "axmanager_permission"
  | "axmanager_ready"
  | "legacy"
  | "recovery";
export type Operation =
  | "install"
  | "repair"
  | "uninstall"
  | "apply_uid"
  | "restore_uid"
  | "launch_game"
  | "request_permission"
  | "request_root"
  | "request_axmanager"
  | "use_axmanager"
  | "use_root"
  | "use_shizuku";
export interface ReleaseInfo {
  tag: string;
  gameVersion: string;
  assetName: string;
  downloadUrl: string;
  htmlUrl: string;
  size: number;
  sha256: string;
}
export type LoadStatus = "not_checked" | "loading" | "ready" | "error";
export interface LoadCheck { status: LoadStatus; error: string | null }
export interface Dashboard {
  mode: "mock" | "native";
  checks: Record<"bootstrap" | "access" | "release" | "storage", LoadCheck>;
  platformSupported: boolean;
  storageWritable: boolean;
  blocker: string | null;
  release: ReleaseInfo | null;
  uidAvailable: boolean;
  game: { installed: boolean; version: string; packageName: string };
  resource: { ready: boolean; version: string };
  shizuku: { installed: boolean; running: boolean; granted: boolean };
  axmanager: { installed: boolean; supported: boolean; running: boolean; version: string };
  access: { method: "shizuku" | "root" | "axmanager"; granted: boolean };
  patch: {
    installed: boolean;
    healthy: boolean;
    recoveryPending: boolean;
    legacyPending: boolean;
    version: string;
    message?: string | null;
  };
  uid: { original: string; custom: string | null };
  lastChecked: string | number;
}
export type AccessSnapshot = Pick<Dashboard, "shizuku" | "axmanager" | "access">;
export interface BootstrapSnapshot {
  mode: Dashboard["mode"];
  platformSupported: boolean;
  uidAvailable: boolean;
  device: { game: Dashboard["game"]; accessMethod: Dashboard["access"]["method"] };
}
export type InspectionSnapshot = Pick<Dashboard,
  "storageWritable" | "blocker" | "game" | "resource" | "shizuku" | "axmanager" | "access" | "patch" | "uid" | "lastChecked"
> & { inspection: Exclude<LoadStatus, "loading"> };
export type OperationResult =
  | { kind: "access"; snapshot: AccessSnapshot }
  | { kind: "inspection"; snapshot: InspectionSnapshot; release: ReleaseInfo | null };

export function loadCheck(status: LoadStatus = "not_checked", error: string | null = null): LoadCheck {
  return { status, error };
}

export function checkLabel(check: LoadCheck): string {
  switch (check.status) {
    case "loading": return "Memeriksa…";
    case "error": return "Gagal diperiksa";
    case "not_checked": return "Belum diperiksa";
    case "ready": return "Selesai";
  }
}

export function initialDashboard(mode: Dashboard["mode"]): Dashboard {
  return {
    mode, checks: { bootstrap: loadCheck(), access: loadCheck(), release: loadCheck(), storage: loadCheck() },
    platformSupported: false, storageWritable: false, blocker: null, release: null, uidAvailable: false,
    game: { installed: false, version: "", packageName: "" },
    resource: { ready: false, version: "" },
    shizuku: { installed: false, running: false, granted: false },
    axmanager: { installed: false, supported: false, running: false, version: "" },
    access: { method: "axmanager", granted: false },
    patch: { installed: false, healthy: false, recoveryPending: false, legacyPending: false, version: "" },
    uid: { original: "", custom: null }, lastChecked: 0,
  };
}

export const ACCESS_LABELS = { shizuku: "Shizuku", root: "Root", axmanager: "AxManager" } as const;

export interface Progress {
  percent: number;
  label: string;
}
export interface Diagnostics {
  filename: string;
  content: string;
}
export type DiagnosticCheckStatus = "ok" | "error" | "not_checked";
export interface DiagnosticSection {
  status: DiagnosticCheckStatus;
  data: Record<string, unknown> | null;
  error: string | null;
  reason: string | null;
}
export interface DiagnosticError {
  timestamp: number;
  operation: string;
  message: string;
}
export interface DiagnosticReport {
  schemaVersion: 1;
  generatedAt: number;
  mock: boolean;
  backend: string;
  sections: Record<"application" | "device" | "probe" | "resource" | "patch" | "release", DiagnosticSection>;
  privacy: { customUid: "redacted"; errorHistory: "session"; maximumErrors: 20 };
  errors: DiagnosticError[];
}
export interface DiagnosticExportResult {
  status: "saved" | "cancelled" | "failed" | "share_opened" | "download_started";
  message?: string;
}
export interface AppUpdateStatus {
  supported: boolean;
  currentVersion: string | null;
  currentVersionCode: number | null;
  availableVersion: string | null;
  size: number;
  ready: boolean;
  checked: boolean;
  message: string;
}
export interface UiState {
  dashboard: Dashboard;
  busy: boolean;
  progress: Progress | null;
  uidDraft: string;
}
export const CONTENT_VERSION = "3.7.0-R2";
export const UID_MAX_LENGTH = 32;

export function compatibleGameVersion(d: Dashboard, version: string): boolean {
  if (d.mode === "mock") return version === "3.7.0";
  return (
    !!d.release &&
    /^\d+\.\d+\.\d+$/.test(version) &&
    version.split(".").slice(0, 2).join(".") ===
      d.release.gameVersion.split(".").slice(0, 2).join(".")
  );
}

export function installationPrerequisites(d: Dashboard): boolean {
  return (
    d.platformSupported &&
    d.checks.bootstrap.status === "ready" &&
    d.checks.storage.status === "ready" &&
    d.checks.release.status === "ready" &&
    !d.blocker &&
    d.storageWritable &&
    d.game.installed &&
    compatibleGameVersion(d, d.game.version) &&
    accessReadiness(d).ready &&
    d.resource.ready &&
    compatibleGameVersion(d, d.resource.version)
  );
}
export function accessReadiness(d: Dashboard): { ready: boolean; label: string; detail: string } {
  if (d.checks.bootstrap.status !== "ready")
    return { ready: false, label: checkLabel(d.checks.bootstrap), detail: d.checks.bootstrap.error ?? "Memuat informasi perangkat." };
  if (!d.platformSupported)
    return { ready: false, label: "Perlu Android", detail: "Akses file game hanya tersedia di aplikasi Android." };
  if (d.checks.access.status !== "ready")
    return { ready: false, label: d.checks.access.status === "error" ? "Gagal memeriksa akses" : checkLabel(d.checks.access), detail: d.checks.access.error ?? "" };
  if (d.access.method === "axmanager") {
    if (!d.axmanager.installed)
      return { ready: false, label: "AxManager belum terpasang", detail: "Unduh AxManager, lalu aktifkan servernya." };
    if (!d.axmanager.supported)
      return { ready: false, label: "AxManager belum didukung", detail: "Perlu Android 8+, profil utama, serta aplikasi dan server AxManager 3.0.0+." };
    if (!d.access.granted || !d.axmanager.running)
      return { ready: false, label: "AxManager belum terhubung", detail: "Pastikan server aktif, lalu pilih Hubungkan." };
  } else if (d.access.method === "shizuku") {
    if (!d.shizuku.installed)
      return { ready: false, label: "Shizuku belum terpasang", detail: "Unduh Shizuku, lalu aktifkan layanannya." };
    if (!d.shizuku.running)
      return { ready: false, label: "Shizuku belum aktif", detail: "Buka Shizuku dan aktifkan layanannya." };
    if (!d.access.granted)
      return d.shizuku.granted
        ? { ready: false, label: "Shizuku belum terhubung", detail: "Izin sudah tersedia. Pilih Hubungkan Shizuku." }
        : { ready: false, label: "Izin Shizuku diperlukan", detail: "Pilih Izinkan Shizuku, lalu setujui permintaan izin." };
  } else if (!d.access.granted) {
    return { ready: false, label: "Izin root diperlukan", detail: "Berikan izin di pengelola root, atau pilih metode lain." };
  }
  return { ready: true, label: "Terhubung", detail: "" };
}

export function readiness(d: Dashboard): {
  ready: boolean;
  label: string;
  detail: string;
} {
  if (d.checks.bootstrap.status !== "ready")
    return { ready: false, label: d.checks.bootstrap.status === "error" ? "Gagal memuat perangkat" : "Mengenali perangkat…", detail: d.checks.bootstrap.error ?? "" };
  if (!d.platformSupported)
    return {
      ready: false,
      label: "Perlu Android",
      detail: d.blocker ?? "Pemasangan terjemahan dan perubahan UID hanya tersedia di aplikasi Android.",
    };
  if (!d.game.installed)
    return {
      ready: false,
      label: "Game belum terpasang",
      detail: "Pasang Wuthering Waves versi Global atau Galaxy Store.",
    };
  const access = accessReadiness(d);
  if (!access.ready) return access;
  if (d.checks.storage.status !== "ready") {
    const storage = d.checks.storage;
    return {
      ready: false,
      label: storage.status === "loading" ? "Memeriksa file game…" : storage.status === "error"
        ? "Gagal memeriksa file game" : "File game belum diperiksa",
      detail: storage.error ?? "",
    };
  }
  if (d.resource.ready && (d.patch.legacyPending || d.patch.recoveryPending))
    return {
      ready: false,
      label: d.patch.recoveryPending ? "Perlu pemulihan" : "Pemasangan versi lama",
      detail: d.patch.message ?? (d.patch.recoveryPending ? "Ada transaksi pemasangan yang belum selesai." : "Migrasikan berkas yang sudah ada tanpa mengunduh ulang."),
    };
  if (d.checks.release.status !== "ready")
    return {
      ready: false,
      label: d.checks.release.status === "error" ? "Gagal memuat rilis" : "Memuat rilis terjemahan…",
      detail: d.checks.release.error ?? "",
    };
  if (
    d.mode === "native" &&
    d.access.granted &&
    !d.release
  )
    return {
      ready: false,
      label: "Rilis belum tersedia",
      detail:
        d.blocker ?? "Periksa koneksi, lalu muat ulang rilis.",
    };
  if (!compatibleGameVersion(d, d.game.version))
    return {
      ready: false,
      label: "Versi belum didukung",
      detail:
        d.mode === "mock"
          ? "Game dan resource harus versi 3.7.0."
          : `Rilis ${d.release?.tag ?? "terbaru"} memerlukan game ${d.release?.gameVersion.split(".").slice(0, 2).join(".") ?? "sesuai rilis"}.`,
    };
  if (!d.resource.ready || !compatibleGameVersion(d, d.resource.version))
    return {
      ready: false,
      label: "File game belum siap",
      detail: d.blocker ?? "Selesaikan unduhan resource di game, termasuk bahasa Inggris. Lalu periksa ulang.",
    };
  if (d.blocker || !d.storageWritable)
    return {
      ready: false,
      label: "Pemasangan diblokir",
      detail:
        d.blocker ?? "Akses terpilih tidak dapat menulis folder resource game.",
    };
  if (d.patch.recoveryPending || (d.patch.installed && !d.patch.healthy))
    return {
      ready: false,
      label: "Perlu pemulihan",
      detail: "Perbaiki pemasangan untuk memulihkan berkas terjemahan.",
    };
  return {
    ready: true,
    label: d.patch.installed ? "Terjemahan terpasang" : "Siap dipasang",
    detail: "",
  };
}
