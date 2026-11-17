import { mockIPC } from "@tauri-apps/api/mocks";
import { mockDiagnostics } from "./diagnostics";
import {
  CONTENT_VERSION,
  UID_MAX_LENGTH,
  readiness,
  loadCheck,
  type InspectionSnapshot,
  type OperationResult,
  type ReleaseInfo,
  type Dashboard,
  type Operation,
  type Progress,
  type Scenario,
} from "./model";

const STORAGE_KEY = "wuwaidmobile.mock-state.v8";
const GAME_VERSION = "3.7.0";
const PACKAGE_NAME = "com.kurogame.wutheringwaves.global";
const MOCK_RELEASE: ReleaseInfo = {
  tag: CONTENT_VERSION, gameVersion: GAME_VERSION, assetName: "WuwaID_99_P.pak",
  htmlUrl: `https://github.com/TitoTFP/WuwaID/releases/tag/${CONTENT_VERSION}`,
  downloadUrl: `https://github.com/TitoTFP/WuwaID/releases/download/${CONTENT_VERSION}/WuwaID_99_P.pak`,
  size: 1024, sha256: "a".repeat(64),
};

function seed(): Dashboard {
  return {
    mode: "mock",
    checks: { bootstrap: loadCheck("ready"), access: loadCheck("ready"), release: loadCheck("ready"), storage: loadCheck("ready") },
    platformSupported: true,
    storageWritable: true,
    blocker: null,
    release: { ...MOCK_RELEASE },
    uidAvailable: true,
    game: { installed: true, version: GAME_VERSION, packageName: PACKAGE_NAME },
    resource: { ready: true, version: GAME_VERSION },
    shizuku: { installed: true, running: false, granted: false },
    axmanager: { installed: true, supported: true, running: true, version: "3.0.0" },
    access: { method: "axmanager", granted: true },
    patch: {
      installed: false,
      healthy: false,
      recoveryPending: false,
      legacyPending: false,
      version: CONTENT_VERSION,
    },
    uid: { original: "900123456", custom: null },
    lastChecked: new Date().toISOString(),
  };
}

function clone(dashboard: Dashboard): Dashboard {
  return {
    ...dashboard,
    checks: {
      bootstrap: { ...dashboard.checks.bootstrap }, access: { ...dashboard.checks.access },
      release: { ...dashboard.checks.release }, storage: { ...dashboard.checks.storage },
    },
    release: dashboard.release ? { ...dashboard.release } : null,
    game: { ...dashboard.game },
    resource: { ...dashboard.resource },
    shizuku: { ...dashboard.shizuku },
    axmanager: { ...dashboard.axmanager },
    access: { ...dashboard.access },
    patch: { ...dashboard.patch },
    uid: { ...dashboard.uid },
  };
}

function inspectionSnapshot(d: Dashboard): InspectionSnapshot {
  const inspected = d.access.granted;
  return {
    game: { ...d.game }, shizuku: { ...d.shizuku }, axmanager: { ...d.axmanager }, access: { ...d.access },
    storageWritable: inspected && d.storageWritable, blocker: d.blocker,
    resource: inspected ? { ...d.resource } : { ready: false, version: "" },
    patch: inspected ? { ...d.patch } : { installed: false, healthy: false, recoveryPending: false, legacyPending: false, version: "" },
    uid: inspected ? { ...d.uid } : { original: "", custom: null },
    lastChecked: Date.now(), inspection: inspected ? "ready" : "not_checked",
  };
}

function validDashboard(value: unknown): value is Dashboard {
  if (!value || typeof value !== "object") return false;
  const d = value as Dashboard;
  return (
    d.mode === "mock" &&
    d.platformSupported === true &&
    d.storageWritable === true &&
    d.uidAvailable === true &&
    d.blocker === null &&
    d.release?.tag === CONTENT_VERSION &&
    !!d.checks && Object.values(d.checks).length === 4 &&
    Object.values(d.checks).every((check) => check.status === "ready" && check.error === null) &&
    !!d.game &&
    typeof d.game.installed === "boolean" &&
    typeof d.game.version === "string" &&
    typeof d.game.packageName === "string" &&
    !!d.resource &&
    typeof d.resource.ready === "boolean" &&
    typeof d.resource.version === "string" &&
    !!d.shizuku &&
    typeof d.shizuku.installed === "boolean" &&
    typeof d.shizuku.running === "boolean" &&
    typeof d.shizuku.granted === "boolean" &&
    !!d.axmanager &&
    typeof d.axmanager.installed === "boolean" &&
    typeof d.axmanager.supported === "boolean" &&
    typeof d.axmanager.running === "boolean" &&
    typeof d.axmanager.version === "string" &&
    !!d.access &&
    (d.access.method === "shizuku" || d.access.method === "root" || d.access.method === "axmanager") &&
    typeof d.access.granted === "boolean" &&
    !!d.patch &&
    typeof d.patch.installed === "boolean" &&
    typeof d.patch.healthy === "boolean" &&
    typeof d.patch.recoveryPending === "boolean" &&
    typeof d.patch.legacyPending === "boolean" &&
    typeof d.patch.version === "string" &&
    !!d.uid &&
    typeof d.uid.original === "string" &&
    (d.uid.custom === null ||
      (typeof d.uid.custom === "string" &&
        d.uid.custom.trim().length > 0 &&
        d.uid.custom.length <= UID_MAX_LENGTH)) &&
    typeof d.lastChecked === "string"
  );
}

function loadPersisted(): Dashboard {
  try {
    const saved = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (saved) {
      const parsed: unknown = JSON.parse(saved);
      if (validDashboard(parsed)) return clone(parsed);
    }
  } catch {
    /* Unavailable storage or corrupt state resets to the deterministic seed. */
  }
  return seed();
}

let dashboard = loadPersisted();
let busy = false;
const progressListeners = new Set<(progress: Progress) => void>();

function persist(next: Dashboard): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    /* The in-memory mock remains usable if storage is unavailable. */
  }
}

function emit(percent: number, label: string): void {
  const progress = { percent, label };
  for (const listener of progressListeners) listener(progress);
}

function dependenciesReady(d: Dashboard): boolean {
  return (
    d.game.installed &&
    d.game.version === GAME_VERSION &&
    d.access.granted &&
    (d.access.method !== "axmanager" || (d.axmanager.installed && d.axmanager.supported && d.axmanager.running)) &&
    d.resource.ready &&
    d.resource.version === GAME_VERSION
  );
}

function requireDependencies(d: Dashboard): void {
  if (!dependenciesReady(d)) throw new Error(readiness(d).detail);
}

function scenarioState(scenario: Scenario): Dashboard {
  const next = seed();
  switch (scenario) {
    case "ready":
      break;
    case "installed":
      next.patch.installed = true;
      next.patch.healthy = true;
      break;
    case "legacy":
      next.patch.installed = true;
      next.patch.legacyPending = true;
      break;
    case "game_missing":
      next.game.installed = false;
      next.game.version = "";
      break;
    case "unsupported":
      next.game.version = "3.6.0";
      next.resource.version = "3.6.0";
      break;
    case "resource_missing":
      next.resource.ready = false;
      break;
    case "shizuku_missing":
      next.access.method = "shizuku";
      next.shizuku = { installed: false, running: false, granted: false };
      next.access.granted = false;
      break;
    case "shizuku_off":
      next.access.method = "shizuku";
      next.shizuku.running = false;
      next.shizuku.granted = false;
      next.access.granted = false;
      break;
    case "permission":
      next.access.method = "shizuku";
      next.shizuku.running = true;
      next.shizuku.granted = false;
      next.access.granted = false;
      break;
    case "root_permission":
    case "root_ready":
      next.access.method = "root";
      next.access.granted = scenario === "root_ready";
      next.shizuku.running = false;
      next.shizuku.granted = false;
      break;
    case "axmanager_missing":
      next.axmanager = { installed: false, supported: false, running: false, version: "" };
      next.access.granted = false;
      break;
    case "axmanager_off":
    case "axmanager_permission":
    case "axmanager_ready":
      next.access.method = "axmanager";
      next.access.granted = scenario === "axmanager_ready";
      next.axmanager.running = scenario !== "axmanager_off";
      next.shizuku.running = false;
      next.shizuku.granted = false;
      break;
    case "recovery":
      next.patch.installed = true;
      next.patch.healthy = false;
      next.patch.recoveryPending = true;
      break;
    default:
      throw new Error("Skenario simulasi tidak dikenal.");
  }
  return next;
}

async function delay(ms: number): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, ms));
}

async function perform(
  operation: Operation,
  uidText?: string,
): Promise<OperationResult> {
  if (busy) throw new Error("Operasi simulasi lain sedang berjalan.");
  busy = true;
  try {
    const next = clone(dashboard);
    const offlineRepair = operation === "repair" && (next.patch.recoveryPending || next.patch.legacyPending);
    const phases: Progress[] = [
      { percent: 18, label: "Menyiapkan…" },
      { percent: 58, label: "Memproses…" },
      { percent: 100, label: "Simulasi selesai" },
    ];
    if (operation === "use_root" || operation === "use_shizuku" || operation === "use_axmanager" || operation === "request_root") {
      if (operation === "request_root" && next.access.method !== "root")
        throw new Error("Pilih metode Root terlebih dahulu.");
    } else if (operation === "request_axmanager") {
      if (next.access.method !== "axmanager") throw new Error("Pilih metode AxManager terlebih dahulu.");
      if (!next.axmanager.installed || !next.axmanager.supported || !next.axmanager.running)
        throw new Error("Aktifkan AxManager 3.0.0+ terlebih dahulu.");
    } else if (operation === "request_permission") {
      if (next.access.method !== "shizuku") throw new Error("Pilih metode Shizuku terlebih dahulu.");
      if (!next.shizuku.running) throw new Error("Shizuku belum aktif.");
    } else if (operation === "launch_game") {
      if (!next.game.installed)
        throw new Error("Wuthering Waves Global belum terpasang.");
    } else {
      requireDependencies(next);
      if (operation === "install") {
        if (
          next.patch.recoveryPending ||
          (next.patch.installed && !next.patch.healthy)
        )
          throw new Error(readiness(next).detail);
      }
      if (
        ![
          "install",
          "repair",
          "uninstall",
          "apply_uid",
          "restore_uid",
        ].includes(operation)
      )
        throw new Error("Operasi simulasi tidak dikenal.");
      if (
        operation === "repair" &&
        !next.patch.installed &&
        !next.patch.recoveryPending
      )
        throw new Error("Tidak ada pemasangan untuk dipulihkan.");
      if (
        operation === "uninstall" &&
        !next.patch.installed &&
        !next.patch.recoveryPending
      )
        throw new Error("Terjemahan belum terpasang.");
      if (operation === "apply_uid" || operation === "restore_uid") {
        if (
          next.patch.recoveryPending ||
          (next.patch.installed && !next.patch.healthy)
        )
          throw new Error(readiness(next).detail);
        if (operation === "restore_uid" && !next.patch.installed)
          throw new Error("Tidak ada pemasangan UID untuk direset.");
        if (operation === "apply_uid") {
          if (typeof uidText !== "string" || !uidText.trim())
            throw new Error("Masukkan teks UID kustom.");
          if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u.test(uidText))
            throw new Error("Teks UID tidak boleh memuat karakter kontrol atau pemisah baris.");
          uidText = uidText.trim();
          if (uidText.length > UID_MAX_LENGTH)
            throw new Error(`Teks UID maksimal ${UID_MAX_LENGTH} karakter.`);
        }
      }
    }
    for (const phase of phases) {
      await delay(180);
      emit(phase.percent, phase.label);
    }
    switch (operation) {
      case "install":
        next.patch.installed = true;
        next.patch.healthy = true;
        next.patch.recoveryPending = false;
        next.patch.legacyPending = false;
        break;
      case "repair":
        next.patch.installed = true;
        next.patch.healthy = true;
        next.patch.recoveryPending = false;
        next.patch.legacyPending = false;
        break;
      case "uninstall":
        next.patch.installed = false;
        next.patch.healthy = false;
        next.patch.recoveryPending = false;
        next.patch.legacyPending = false;
        next.uid.custom = null;
        break;
      case "apply_uid":
        next.patch.installed = true;
        next.patch.healthy = true;
        next.patch.recoveryPending = false;
        next.patch.legacyPending = false;
        next.uid.custom = uidText!;
        break;
      case "restore_uid":
        next.uid.custom = null;
        break;
      case "request_permission":
        next.shizuku.granted = true;
        if (next.access.method === "shizuku") next.access.granted = true;
        break;
      case "use_root":
        next.access.method = "root";
        next.access.granted = false;
        break;
      case "use_shizuku":
        next.access.method = "shizuku";
        next.access.granted = next.shizuku.running && next.shizuku.granted;
        break;
      case "use_axmanager":
        next.access.method = "axmanager";
        next.access.granted = false;
        break;
      case "request_axmanager":
        next.access.granted = true;
        break;
      case "request_root":
        next.access.granted = true;
        break;
      case "launch_game":
        break;
    }
    next.lastChecked = new Date().toISOString();
    dashboard = next;
    persist(dashboard);
    if (operation === "use_root" || operation === "use_shizuku" || operation === "use_axmanager" || operation === "launch_game")
      return { kind: "access", snapshot: { shizuku: { ...next.shizuku }, axmanager: { ...next.axmanager }, access: { ...next.access } } };
    return {
      kind: "inspection", snapshot: inspectionSnapshot(next),
      release: ["install", "apply_uid", "restore_uid", "repair"].includes(operation) && !offlineRepair ? { ...MOCK_RELEASE } : null,
    };
  } finally {
    busy = false;
  }
}


mockIPC(async (command, args) => {
  const payload = (args ?? {}) as Record<string, unknown>;
  switch (command) {
    case "wuwa_bootstrap":
      return { mode: "mock", platformSupported: true, uidAvailable: true, device: { game: { ...dashboard.game }, accessMethod: dashboard.access.method } };
    case "wuwa_release":
      return { ...MOCK_RELEASE };
    case "wuwa_inspect": {
      if (busy) throw new Error("Operasi simulasi lain sedang berjalan.");
      busy = true;
      try {
        await delay(180);
        return inspectionSnapshot(dashboard);
      } finally { busy = false; }
    }
    case "wuwa_access_status": {
      if (busy) throw new Error("Operasi simulasi lain sedang berjalan.");
      return { shizuku: { ...dashboard.shizuku }, axmanager: { ...dashboard.axmanager }, access: { ...dashboard.access } };
    }
    case "wuwa_set_scenario": {
      if (busy) throw new Error("Operasi simulasi lain sedang berjalan.");
      if (typeof payload.scenario !== "string")
        throw new Error("Skenario simulasi tidak valid.");
      const next = scenarioState(payload.scenario as Scenario);
      dashboard = next;
      persist(dashboard);
      return clone(dashboard);
    }
    case "wuwa_run":
      return perform(
        payload.operation as Operation,
        payload.uidText as string | undefined,
      );
    case "wuwa_diagnostics":
      return mockDiagnostics(dashboard);
    default:
      throw new Error(`Perintah mock tidak dikenal: ${command}`);
  }
});

export function subscribeProgress(
  listener: (progress: Progress) => void,
): () => void {
  progressListeners.add(listener);
  return () => progressListeners.delete(listener);
}
