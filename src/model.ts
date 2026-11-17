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
export interface Dashboard {
  mode: "mock" | "native";
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
  if (!d.platformSupported)
    return { ready: false, label: "Perlu Android", detail: "Akses file game hanya tersedia di Android." };
  if (d.access.method === "axmanager") {
    if (!d.axmanager.installed)
      return { ready: false, label: "AxManager belum ditemukan", detail: "Pasang AxManager 3.0.0+ melalui Atur akses game." };
    if (!d.axmanager.supported)
      return { ready: false, label: "AxManager belum didukung", detail: "Perlu Android 8+, profil utama perangkat, dan aplikasi serta server AxManager 3.0.0+." };
    if (!d.access.granted || !d.axmanager.running)
      return { ready: false, label: "AxManager belum terhubung", detail: "Aktifkan server di AxManager, lalu pilih Hubungkan pada Akses game. Izin yang tersimpan tidak berarti sesi sudah terhubung." };
  } else if (d.access.method === "shizuku") {
    if (!d.shizuku.installed)
      return { ready: false, label: "Shizuku belum terpasang", detail: "Unduh Shizuku melalui Akses game, lalu aktifkan layanannya." };
    if (!d.shizuku.running)
      return { ready: false, label: "Shizuku belum aktif", detail: "Buka Shizuku dan aktifkan layanannya, lalu kembali ke sini." };
    if (!d.access.granted)
      return { ready: false, label: d.shizuku.granted ? "Shizuku belum terhubung" : "Izin Shizuku diperlukan", detail: "Pilih Hubungkan atau Izinkan Shizuku pada Akses game. Izin hanya diminta jika belum diberikan." };
  } else if (!d.access.granted) {
    return { ready: false, label: "Root belum terhubung", detail: "Pada perangkat yang sudah di-root, pilih Izinkan root pada Akses game. Metode lain tetap tersedia." };
  }
  return { ready: true, label: "Terhubung", detail: `Akses ${ACCESS_LABELS[d.access.method]} tersedia.` };
}

export function readiness(d: Dashboard): {
  ready: boolean;
  label: string;
  detail: string;
} {
  if (!d.platformSupported)
    return {
      ready: false,
      label: "Perlu Android",
      detail:
        d.blocker ??
        "Gunakan Android dengan AxManager, Shizuku, atau Root.",
    };
  if (!d.game.installed)
    return {
      ready: false,
      label: "Game belum ditemukan",
      detail: "Pasang versi Global atau Galaxy Store, lalu periksa ulang.",
    };
  const access = accessReadiness(d);
  if (!access.ready) return access;
  if (d.resource.ready && (d.patch.legacyPending || d.patch.recoveryPending))
    return {
      ready: false,
      label: d.patch.recoveryPending ? "Perlu pemulihan" : "Pemasangan versi lama",
      detail: d.patch.message ?? "Buka Perawatan untuk migrasi atau pemulihan tanpa unduhan.",
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
      label: "Resource belum siap",
      detail:
        d.blocker ??
        "Selesaikan unduhan resource di game, lalu periksa ulang.",
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
      detail: "Jalankan Perbaiki di Perawatan.",
    };
  return {
    ready: true,
    label: d.patch.installed ? "Terjemahan terpasang" : "Siap dipasang",
    detail:
      `Game, ${ACCESS_LABELS[d.access.method]}, dan resource siap.`,
  };
}
