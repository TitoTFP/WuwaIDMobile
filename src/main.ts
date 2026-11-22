import { api } from "./ipc";
import { diagnosticExportMessage } from "./diagnostics";
import { appUpdateBusy, handleAppUpdate, initAppUpdates, renderAppUpdate } from "./app-update";
import {
  ACCESS_LABELS,
  UID_MAX_LENGTH,
  compatibleGameVersion,
  accessReadiness,
  readiness,
  initialDashboard,
  loadCheck,
  checkLabel,
  type AccessSnapshot,
  type InspectionSnapshot,
  type Operation,
  type Scenario,
  type Section,
  type UiState,
} from "./model";
import { renderAccess, renderCustomize, renderProgress, renderTools } from "./panels";
import { escapeHtml as esc, icon, statusBadge, type StatusTone } from "./ui";

const app = document.querySelector<HTMLDivElement>("#app")!;
const toast = document.querySelector<HTMLDivElement>("#toast")!;
const dialog = document.querySelector<HTMLDialogElement>("#confirm-dialog")!;
let state: UiState = { dashboard: initialDashboard(api.mode), busy: false, progress: null, uidDraft: "" };
let section: Section = "home";
let renderedSection: Section | undefined;
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
let error = "";
let toastTimer: number;
let busyLabel = "";
let fileProgress = false;
let showBusyProgress = true;
let setupArmed = false;
let setupLeft = false;
let setupRefreshPending = false;
let inspectionRequested = false;

function markSetupLeave() {
  if (setupArmed) setupLeft = true;
}

function markSetupReturn() {
  if (document.visibilityState === "hidden") return;
  if (setupArmed && setupLeft) {
    setupArmed = false;
    setupRefreshPending = true;
  }
  void refreshSetupReturn();
}

function invalidateStorage() {
  const d = state.dashboard;
  d.checks.storage = loadCheck();
  d.storageWritable = false;
  d.blocker = null;
  d.resource = { ready: false, version: "" };
  d.patch = { installed: false, healthy: false, recoveryPending: false, legacyPending: false, version: "" };
  d.uid = { original: "", custom: null };
}

function applyAccess(snapshot: AccessSnapshot, reset = false) {
  if (reset || !snapshot.access.granted || snapshot.access.method !== state.dashboard.access.method)
    invalidateStorage();
  Object.assign(state.dashboard, snapshot);
  state.dashboard.checks.access = loadCheck("ready");
}

function applyInspection(snapshot: InspectionSnapshot) {
  const { inspection, ...data } = snapshot;
  const unchangedDraft = state.uidDraft === (state.dashboard.uid.custom ?? "");
  Object.assign(state.dashboard, data);
  state.dashboard.checks.access = loadCheck("ready");
  state.dashboard.checks.storage = loadCheck(inspection, inspection === "error" ? snapshot.blocker : null);
  if (unchangedDraft) state.uidDraft = snapshot.uid.custom ?? "";
}

async function loadBootstrap() {
  const d = state.dashboard;
  if (d.checks.bootstrap.status === "loading") return;
  d.checks.bootstrap = loadCheck("loading");
  render();
  try {
    const snapshot = await api.bootstrap();
    d.mode = snapshot.mode;
    d.platformSupported = snapshot.platformSupported;
    d.uidAvailable = snapshot.uidAvailable;
    d.game = snapshot.device.game;
    d.access.method = snapshot.device.accessMethod;
    d.checks.bootstrap = loadCheck("ready");
    setupRefreshPending = d.platformSupported;
  } catch (cause) {
    d.checks.bootstrap = loadCheck("error", String(cause));
  } finally {
    render();
    void refreshSetupReturn();
  }
}

async function loadRelease() {
  const d = state.dashboard;
  if (d.checks.release.status === "loading") return;
  d.checks.release = loadCheck("loading");
  render();
  try {
    d.release = await api.release();
    d.checks.release = loadCheck("ready");
  } catch (cause) {
    d.release = null;
    d.checks.release = loadCheck("error", String(cause));
  } finally {
    render();
  }
}

async function refreshSetupReturn() {
  const d = state.dashboard;
  if (!setupRefreshPending || state.busy || appUpdateBusy() ||
    d.checks.bootstrap.status !== "ready" || d.checks.access.status === "loading" ||
    document.visibilityState === "hidden") return;
  setupRefreshPending = false;
  if (!d.platformSupported) return;
  d.checks.access = loadCheck("loading");
  render();
  try {
    applyAccess(await api.accessStatus());
  } catch (cause) {
    d.checks.access = loadCheck("error", String(cause));
    invalidateStorage();
  } finally {
    render();
    void inspectWhenNeeded();
    if (setupRefreshPending) void refreshSetupReturn();
  }
}

async function inspectStorage() {
  inspectionRequested = false;
  await execute(async () => {
    state.dashboard.checks.storage = loadCheck("loading");
    render();
    try {
      applyInspection(await api.inspect());
    } catch (cause) {
      state.dashboard.checks.storage = loadCheck("error", cause instanceof Error ? cause.message : String(cause));
      throw cause;
    }
  }, "Memeriksa file game…", { showProgress: false });
}

async function inspectWhenNeeded() {
  if (state.dashboard.checks.storage.status !== "not_checked") inspectionRequested = false;
  if (!inspectionRequested || state.busy || appUpdateBusy() || !accessReadiness(state.dashboard).ready) return;
  await inspectStorage();
}

window.addEventListener("blur", markSetupLeave);
window.addEventListener("focus", markSetupReturn);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") markSetupLeave();
  else markSetupReturn();
});
const navigation: { id: Section; label: string; icon: string }[] = [
  { id: "home", label: "Beranda", icon: "home" },
  { id: "customize", label: "UID", icon: "id" },
  { id: "tools", label: "Perawatan", icon: "tools" },
];
const logo = '<svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M3 13c5-17 9 17 14 0s9 17 12 0M3 20c5-17 9 17 14 0s9 17 12 0" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';
type ThemePreference = "light" | "dark" | "system";
const systemTheme = matchMedia("(prefers-color-scheme: dark)");
let themePreference = document.documentElement.dataset.themePreference as ThemePreference;
const themeOptions: { id: ThemePreference; label: string; icon: string }[] = [
  { id: "light", label: "Terang", icon: "sun" },
  { id: "dark", label: "Gelap", icon: "moon" },
  { id: "system", label: "Sistem", icon: "system" },
];

function applyTheme() {
  const theme = themePreference === "system"
    ? (systemTheme.matches ? "dark" : "light")
    : themePreference;
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.themePreference = themePreference;
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')!.content =
    theme === "dark" ? "#122529" : "#fbfdfa";
  app.querySelectorAll<HTMLButtonElement>("[data-theme-choice]").forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.themeChoice === themePreference));
  });
}
systemTheme.addEventListener("change", () => {
  if (themePreference === "system") applyTheme();
});

function renderThemeControl() {
  return `<div class="theme-control" role="group" aria-label="Tema tampilan">${themeOptions.map((option) => `<button type="button" data-theme-choice="${option.id}" aria-label="Tema ${option.label.toLowerCase()}" title="${option.label}" aria-pressed="${themePreference === option.id}">${icon(option.icon)}</button>`).join("")}</div>`;
}

function notify(message: string) {
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.hidden = false;
  toastTimer = setTimeout(() => {
    toast.hidden = true;
  }, 5500);
}

function renderHome(panelState: UiState) {
  const { dashboard: d, busy } = panelState;
  const status = readiness(d);
  const access = accessReadiness(d);
  const { bootstrap, release, storage } = d.checks;
  const loadingDevice = bootstrap.status === "not_checked" || bootstrap.status === "loading";
  const canInspect = d.platformSupported && bootstrap.status === "ready";
  const edition = d.game.packageName.endsWith(".samsung") ? "Galaxy Store" : "Global";
  const checkVersion = storage.status === "ready" && release.status === "ready" && (d.mode === "mock" || !!d.release);
  const gameMismatch = checkVersion && d.game.installed && !compatibleGameVersion(d, d.game.version);
  const fileMismatch = checkVersion && d.resource.ready && !compatibleGameVersion(d, d.resource.version);
  let primary: { action: string; label: string; icon: string } | null =
    { action: "install", label: "Pasang terjemahan", icon: "download" };
  if (bootstrap.status !== "ready")
    primary = bootstrap.status === "error" ? { action: "bootstrap", label: "Muat ulang perangkat", icon: "refresh" } : null;
  else if (!d.platformSupported)
    primary = null;
  else if (!d.game.installed)
    primary = { action: "setup-game", label: "Pasang game", icon: "external" };
  else if (!access.ready)
    primary = d.checks.access.status === "loading" ? null : { action: "access-setup", label: "Atur akses game", icon: "arrow" };
  else if (storage.status !== "ready")
    primary = { action: "refresh", label: storage.status === "error" ? "Periksa ulang file game" : "Periksa file game", icon: "refresh" };
  else if (d.patch.legacyPending || d.patch.recoveryPending)
    primary = { action: "tools", label: "Buka Perawatan", icon: "tools" };
  else if (release.status !== "ready" || !d.release)
    primary = { action: "release", label: "Periksa rilis", icon: "refresh" };
  else if (gameMismatch)
    primary = { action: "compatibility", label: "Lihat kompatibilitas", icon: "arrow" };
  else if (!d.resource.ready || fileMismatch)
    primary = { action: "launch_game", label: "Buka game", icon: "external" };
  else if (!status.ready)
    primary = { action: "tools", label: "Buka Perawatan", icon: "tools" };
  else if (d.patch.installed)
    primary = d.mode === "native" && d.release.tag !== d.patch.version
      ? { action: "install", label: "Perbarui terjemahan", icon: "download" }
      : { action: "launch_game", label: "Buka game", icon: "external" };

  const rows: { label: string; value: string; tone: StatusTone }[] = [
    {
      label: "Game",
      value: bootstrap.status !== "ready" ? checkLabel(bootstrap) : !d.platformSupported
        ? "Perlu Android" : d.game.installed
          ? `${edition} · ${d.game.version || "Versi tidak diketahui"}${gameMismatch ? " (tidak didukung)" : ""}` : "Belum terpasang",
      tone: bootstrap.status === "error" ? "danger" : !d.platformSupported ? "neutral"
        : d.game.installed && !gameMismatch ? "good" : "warning",
    },
    {
      label: "Akses file",
      value: bootstrap.status !== "ready" ? checkLabel(bootstrap) : !d.platformSupported
        ? "Perlu Android" : access.ready ? `${ACCESS_LABELS[d.access.method]} terhubung` : access.label,
      tone: access.ready ? "good" : d.checks.access.status === "error" ? "danger"
        : d.checks.access.status === "ready" && d.platformSupported ? "warning" : "neutral",
    },
    {
      label: "File game",
      value: bootstrap.status === "ready" && !d.platformSupported ? "Perlu Android"
        : storage.status !== "ready" ? checkLabel(storage)
          : !d.resource.ready ? "Unduhan belum selesai"
            : fileMismatch ? `Versi ${d.resource.version} tidak cocok` : `Siap · ${d.resource.version}`,
      tone: storage.status === "ready" ? (d.resource.ready && !fileMismatch ? "good" : "warning")
        : storage.status === "error" ? "danger" : "neutral",
    },
  ];
  const symbols: Record<StatusTone, string> = { good: "check", neutral: "pending", warning: "info", danger: "alert" };
  const currentStep = rows.findIndex(({ tone }) => tone !== "good");
  const statusTone = status.ready ? "good" : rows[currentStep]?.tone ?? (release.status === "loading" ? "neutral" : "warning");
  const primaryDisabled = primary?.action === "bootstrap" ? state.busy || appUpdateBusy()
    : busy || (primary?.action === "release" && release.status === "loading");
  const detail = status.detail !== error && primary?.action !== "access-setup" ? status.detail : "";

  return `<section id="translation-board" class="translation-board" tabindex="-1" aria-labelledby="translation-title">
    <div class="board-task">
      <h1 id="translation-title">Bahasa Indonesia</h1>
      <p class="game-name">Wuthering Waves</p>
      <div class="board-status" role="status">
        ${statusBadge(status.label, statusTone)}
        ${detail ? `<p>${esc(detail)}</p>` : ""}
      </div>
      ${primary ? `<div class="actions package-actions">
        <button type="button" class="btn btn-primary" data-action="${primary.action}" ${primaryDisabled ? "disabled" : ""}>${icon(primary.icon)} ${primary.label}</button>
        ${d.patch.installed && status.ready && primary.action !== "install"
          ? `<button type="button" class="btn btn-quiet" data-action="install" ${busy ? "disabled" : ""}>Pasang ulang</button>` : ""}
      </div>` : ""}
    </div>
    <section class="device-card" aria-labelledby="device-title">
      <svg class="route-diagram" viewBox="0 0 200 160" fill="none" aria-hidden="true">
        <path d="M72 0v30l36 36v44l50 50M112 0v19l35 35h53M200 17h-35l-66 66v41l-36 36M200 106h-34l-25 25H99l-30-30H0"/>
        <path class="route-branch" d="M0 55h52l32 32h45l71 71"/>
      </svg>
      <div class="panel-heading">
        <h2 id="device-title">Kesiapan perangkat</h2>
        ${canInspect && primary?.action !== "refresh" ? `<button type="button" class="icon-btn" data-action="refresh" aria-label="Periksa ulang perangkat" title="Periksa ulang perangkat" ${busy ? "disabled" : ""}>${icon("refresh")}</button>` : ""}
      </div>
      <ol class="device-list" role="list">${rows.map((row, index) => `<li class="device-row ${row.tone}"${d.platformSupported && !loadingDevice && index === currentStep ? ' aria-current="step"' : ""}>
        <span class="device-marker">${icon(symbols[row.tone])}</span>
        <div><strong>${row.label}</strong><span>${esc(row.value)}</span></div>
      </li>`).join("")}</ol>
    </section>
  </section>
  <div class="home-grid">
    ${renderAccess(panelState)}
    <section id="release-panel" class="panel release-panel" aria-labelledby="release-title">
      <div class="panel-heading"><h2 id="release-title">Rilis terjemahan</h2>${icon("box")}</div>
      <dl class="package-facts">
        <div><dt>Terbaru</dt><dd>${esc(d.release?.tag ?? (release.status === "ready" ? "Tidak tersedia" : checkLabel(release)))}</dd></div>
        <div><dt>Untuk versi game</dt><dd>${d.release ? esc(`${d.release.gameVersion.split(".").slice(0, 2).join(".")}.x`) : "—"}</dd></div>
        ${storage.status === "ready" && d.patch.installed ? `<div><dt>Terpasang</dt><dd>${esc(d.patch.version)}</dd></div>` : ""}
      </dl>
      ${release.status === "error" && release.error !== detail ? `<p class="muted">${esc(release.error ?? "Rilis gagal dimuat.")}</p>` : ""}
      ${primary?.action !== "release" ? `<button type="button" class="text-button" data-action="release" ${state.busy || appUpdateBusy() || release.status === "loading" ? "disabled" : ""}>${icon("refresh")} Periksa rilis</button>` : ""}
    </section>
  </div>`;
}

function renderShell() {
  app.innerHTML = `<header class="app-rail">
    <a class="brand" href="#main-content" aria-label="WuwaID Mobile, Beranda"><span class="brand-symbol">${logo}</span><span>WuwaID <span class="brand-id">Mobile</span></span></a>
    <nav class="app-nav" aria-label="Navigasi utama">${navigation.map((item) => `<button type="button" data-section="${item.id}">${icon(item.icon)}<span>${item.label}</span></button>`).join("")}</nav>
    <div class="appearance"><span class="appearance-label">Tema</span>${renderThemeControl()}</div>
  </header>
  <div class="workspace">
    ${state.dashboard.mode === "mock" ? `<p class="mode-notice">${icon("info")} <strong>Simulasi</strong><span>File game tidak diubah</span></p>` : ""}
    <main id="main-content" tabindex="-1"></main>
    <footer class="footer">Tidak berafiliasi dengan Kuro Games.</footer>
  </div>`;
}

function render() {
  const content = app.querySelector<HTMLElement>("#main-content")!;
  const sameSection = renderedSection === section;
  const active = document.activeElement as HTMLElement | null;
  const focused = sameSection && content.contains(active) ? active : null;
  const focusAction = focused?.dataset.action;
  const scopeId = focused?.closest("section[id]")?.id;
  const accessSelectorFocused = focused?.classList.contains("access-methods");
  const uidSelection = focused instanceof HTMLInputElement && focused.id === "uid-input"
    ? { start: focused.selectionStart, end: focused.selectionEnd, direction: focused.selectionDirection }
    : null;
  const detailsOpen = sameSection && !!content.querySelector(".technical-details[open]");
  const previousMethod = content.querySelector<HTMLElement>("#access-setup")?.dataset.accessMethod;
  const nextMethod = state.dashboard.access.method;
  if (!sameSection) {
    app.querySelector(".app-nav [aria-current]")?.removeAttribute("aria-current");
    app.querySelector(`.app-nav [data-section="${section}"]`)?.setAttribute("aria-current", "page");
  }
  const panelState = appUpdateBusy() || state.dashboard.checks.bootstrap.status !== "ready" ||
    state.dashboard.checks.access.status === "loading" ? { ...state, busy: true } : state;
  content.innerHTML = `
    ${error ? `<div class="error-banner" role="alert">${icon("alert")}<div><strong>Operasi tidak selesai</strong><p>${esc(error)}</p></div><button type="button" class="icon-btn" data-action="dismiss-error" aria-label="Tutup pesan">${icon("close")}</button></div>` : ""}
    ${section !== "home" && state.dashboard.checks.bootstrap.status === "error" ? `<div class="error-banner" role="alert">${icon("alert")}<div><strong>Gagal memuat perangkat</strong><p>${esc(state.dashboard.checks.bootstrap.error ?? "")}</p></div><button type="button" class="btn btn-secondary" data-action="bootstrap">Coba lagi</button></div>` : ""}
    ${state.busy && showBusyProgress ? renderProgress(busyLabel, fileProgress ? state.progress : null) : ""}
    ${section === "home" ? renderHome(panelState) : section === "customize" ? renderCustomize(panelState) : renderTools(panelState)}
    ${renderAppUpdate(panelState.busy, section === "tools")}`;
  renderedSection = section;
  const nextAccess = content.querySelector<HTMLElement>("#access-setup");
  if (sameSection && previousMethod && previousMethod !== nextMethod)
    nextAccess?.classList.add("access-changed");
  if (detailsOpen) {
    const details = content.querySelector<HTMLDetailsElement>(".technical-details");
    if (details) details.open = true;
  }

  if (focusAction) {
    const scope = (scopeId ? document.getElementById(scopeId) : null) ?? content;
    const target = scope.querySelector<HTMLElement>(`[data-action="${focusAction}"]`);
    if (focusAction.startsWith("use_") && target instanceof HTMLButtonElement && target.disabled)
      nextAccess?.querySelector<HTMLElement>(".access-methods")?.focus({ preventScroll: true });
    else if (target && !target.matches(":disabled")) target.focus({ preventScroll: true });
    else (scope instanceof HTMLElement && scope.tabIndex === -1 ? scope : content).focus({ preventScroll: true });
  } else if (accessSelectorFocused) {
    nextAccess?.querySelector<HTMLElement>(".access-methods")?.focus({ preventScroll: true });
  } else if (focused?.id) {
    document.getElementById(focused.id)?.focus({ preventScroll: true });
  }
  if (uidSelection)
    content.querySelector<HTMLInputElement>("#uid-input")?.setSelectionRange(uidSelection.start, uidSelection.end, uidSelection.direction ?? "none");
}

function navigate(next: Section) {
  if (next === section) {
    window.scrollTo({ top: 0, behavior: "instant" });
    return;
  }
  section = next;
  inspectionRequested = section !== "home" && state.dashboard.checks.storage.status === "not_checked";
  render();
  window.scrollTo({ top: 0, behavior: "instant" });
  app.querySelector<HTMLElement>("#main-content")?.focus({ preventScroll: true });
  void inspectWhenNeeded();
}

async function execute(work: () => Promise<void>, label = "Memeriksa status…",
  options: { fileProgress?: boolean; showProgress?: boolean } = {}) {
  if (state.busy || appUpdateBusy() || state.dashboard.checks.bootstrap.status !== "ready" ||
    state.dashboard.checks.access.status === "loading") return;
  const busyTrace = api.beginBusy();
  state.busy = true;
  state.progress = null;
  busyLabel = label;
  fileProgress = options.fileProgress ?? false;
  showBusyProgress = options.showProgress ?? true;
  error = "";
  render();
  try {
    await busyTrace;
    await work();
  } catch (cause) {
    error = cause instanceof Error ? cause.message : String(cause);
  } finally {
    try {
      await api.endBusy();
    } finally {
      state.busy = false;
      state.progress = null;
      render();
      void refreshSetupReturn();
      void inspectWhenNeeded();
    }
  }
}

async function operate(operation: Operation, uidText?: string) {
  const labels: Record<Operation, string> = {
    install: "Menyiapkan pemasangan…",
    repair: "Menyiapkan pemulihan…",
    uninstall: "Menyiapkan penghapusan…",
    apply_uid: "Menyiapkan UID kustom…",
    restore_uid: "Memulihkan UID bawaan…",
    launch_game: "Membuka game…",
    request_permission: "Menunggu izin Shizuku…",
    request_root: "Menghubungkan root…",
    request_axmanager: "Menghubungkan AxManager…",
    use_root: "Memilih Root…",
    use_shizuku: "Memilih Shizuku…",
    use_axmanager: "Memilih AxManager…",
  };
  await execute(async () => {
    let result;
    try {
      result = await api.run(operation, uidText);
    } catch (cause) {
      invalidateStorage();
      inspectionRequested = false;
      setupRefreshPending = true;
      throw cause;
    }
    if (result.kind === "access") {
      applyAccess(result.snapshot, operation !== "launch_game");
    } else {
      applyInspection(result.snapshot);
      if (result.release) {
        state.dashboard.release = result.release;
        state.dashboard.checks.release = loadCheck("ready");
      }
    }
    if (operation === "use_root" || operation === "use_shizuku" || operation === "use_axmanager") return;
    if (
      operation === "apply_uid" ||
      operation === "restore_uid" ||
      operation === "uninstall"
    )
      state.uidDraft = state.dashboard.uid.custom ?? "";
    const messages: Record<Exclude<Operation, "use_root" | "use_shizuku" | "use_axmanager">, string> = {
      install: `Terjemahan ${state.dashboard.patch.version} terpasang.`,
      repair: state.dashboard.patch.installed
        ? "Pemasangan dipulihkan."
        : "Transaksi dipulihkan. Terjemahan belum terpasang.",
      uninstall: state.dashboard.mode === "mock"
        ? "Terjemahan dihapus. Tampilan UID direset."
        : "Terjemahan dihapus.",
      apply_uid: "UID kustom diterapkan.",
      restore_uid: "UID bawaan dipulihkan.",
      launch_game: state.dashboard.mode === "mock"
        ? "Simulasi buka game selesai."
        : "Game dibuka.",
      request_permission: "Izin Shizuku diberikan.",
      request_root: "Akses root aktif.",
      request_axmanager: "AxManager terhubung.",
    };
    notify(messages[operation]);
  }, labels[operation], {
    fileProgress: ["install", "repair", "uninstall", "apply_uid", "restore_uid"].includes(operation),
    showProgress: operation !== "use_root" && operation !== "use_shizuku" && operation !== "use_axmanager",
  });
}

app.addEventListener("click", async (event) => {
  const themeButton = (event.target as Element).closest<HTMLButtonElement>("[data-theme-choice]");
  if (themeButton) {
    themePreference = themeButton.dataset.themeChoice as ThemePreference;
    try {
      localStorage.setItem("wuwaidmobile.theme", themePreference);
    } catch {
      notify("Tema hanya berlaku untuk sesi ini; gagal menyimpan preferensi.");
    }
    applyTheme();
    return;
  }
  const button = (event.target as Element).closest<HTMLElement>(
    "[data-section], [data-action], .brand",
  );
  if (!button || button.hasAttribute("disabled")) return;
  if (button.dataset.section || button.classList.contains("brand")) {
    event.preventDefault();
    navigate((button.dataset.section as Section) || "home");
    return;
  }
  const action = button.dataset.action;
  if (await handleAppUpdate(action, state.busy || state.dashboard.checks.bootstrap.status !== "ready" ||
    state.dashboard.checks.access.status === "loading")) {
    void refreshSetupReturn();
    void inspectWhenNeeded();
    return;
  }
  if (action === "dismiss-error") {
    error = "";
    render();
  } else if (action === "access-setup") {
    if (section !== "home") {
      section = "home";
      inspectionRequested = false;
      render();
    }
    const setup = app.querySelector<HTMLElement>("#access-setup");
    setup?.scrollIntoView({ behavior: reducedMotion.matches ? "instant" : "smooth", block: "start" });
    setup?.focus({ preventScroll: true });
  } else if (action === "tools") {
    navigate("tools");
  } else if (action === "compatibility") {
    navigate("tools");
    const summary = app.querySelector<HTMLElement>("#technical-summary");
    const details = summary?.closest("details");
    if (details) {
      details.open = true;
      details.scrollIntoView({ behavior: reducedMotion.matches ? "instant" : "smooth", block: "nearest" });
      summary?.focus({ preventScroll: true });
    }
  } else if (action === "bootstrap") {
    void loadBootstrap();
  } else if (action === "release") {
    if (!state.busy && !appUpdateBusy()) void loadRelease();
  } else if (action === "check-access") {
    setupRefreshPending = true;
    void refreshSetupReturn();
  } else if (action === "refresh") {
    await inspectStorage();
  } else if (action === "restore-uid") {
    await operate("restore_uid");
  } else if (action === "uninstall") {
    dialog.querySelector("p")!.textContent = state.dashboard.mode === "mock"
      ? "Terjemahan simulasi dihapus dan tampilan UID kembali ke bawaan. File game tidak diubah."
      : "Berkas terjemahan yang dipasang WuwaID Mobile dihapus. File asli game tetap utuh.";
    dialog.showModal();
  } else if (action === "save-diagnostics" || action === "share-diagnostics") {
    await execute(async () => {
      const result = await api.exportDiagnostics(action === "save-diagnostics" ? "save" : "share");
      const message = diagnosticExportMessage(result);
      if (result.status === "failed") throw new Error(message);
      notify(message);
    }, "Menyiapkan laporan diagnostik…");
  } else if (action === "setup-game" || action === "setup-shizuku" || action === "setup-axmanager") {
    if (state.dashboard.mode === "native") {
      await execute(async () => {
        setupArmed = true;
        setupLeft = false;
        try {
          await api.openSetup(action === "setup-game" ? "game" : action === "setup-axmanager" ? "axmanager" : "shizuku");
        } catch (cause) {
          setupArmed = false;
          setupRefreshPending = false;
          throw cause;
        }
        notify("Kembali ke sini setelah pengaturan selesai.");
      }, "Membuka pengaturan…");
    } else {
      navigate("tools");
      notify("Pilih kondisi di Skenario simulasi.");
    }
  } else if (
    action &&
    ["install", "repair", "launch_game", "request_permission", "request_root", "request_axmanager", "use_root", "use_shizuku", "use_axmanager"].includes(action)
  )
    await operate(action as Operation);
});

app.addEventListener("input", (event) => {
  const input = event.target as HTMLInputElement;
  if (input.id !== "uid-input") return;
  state.uidDraft = input.value;
  const draft = state.uidDraft.trim();
  const d = state.dashboard;
  app.querySelector<HTMLElement>("#uid-count")!.textContent =
    `${state.uidDraft.length}/${UID_MAX_LENGTH}`;
  app.querySelector<HTMLButtonElement>("#apply-uid")!.disabled =
    state.busy ||
    !d.uidAvailable ||
    !readiness(d).ready ||
    !draft ||
    draft.length > UID_MAX_LENGTH ||
    draft === (d.uid.custom ?? d.uid.original);
});

app.addEventListener("submit", async (event) => {
  if ((event.target as HTMLFormElement).id !== "uid-form") return;
  event.preventDefault();
  if (app.querySelector<HTMLButtonElement>("#apply-uid")!.disabled) return;
  await operate("apply_uid", state.uidDraft);
});

app.addEventListener("change", async (event) => {
  const target = event.target as HTMLSelectElement;
  if (target.id === "scenario-select")
    await execute(async () => {
      state.dashboard = await api.setScenario(target.value as Scenario);
      state.uidDraft = state.dashboard.uid.custom ?? "";
      notify("Skenario diubah.");
    });
});

document
  .querySelector("#cancel-uninstall")!
  .addEventListener("click", () => dialog.close());
document
  .querySelector("#confirm-uninstall")!
  .addEventListener("click", async () => {
    dialog.close();
    await operate("uninstall");
  });

api.subscribeProgress((progress) => {
  if (state?.busy && fileProgress) {
    state.progress = progress;
    // Progress must not replace navigation, theme controls, or focused inputs.
    const card = app.querySelector<HTMLElement>("#main-content > .progress-card");
    if (!card) return;
    const track = card.querySelector<HTMLElement>(".progress-track");
    if (!track) {
      card.outerHTML = renderProgress(busyLabel, progress);
      return;
    }
    const label = card.querySelector("strong")!;
    if (label.textContent !== progress.label) label.textContent = progress.label;
    card.querySelector(".progress-percent")!.textContent = `${progress.percent}%`;
    track.setAttribute("aria-valuenow", String(progress.percent));
    track.querySelector<HTMLElement>(".progress-fill")!.style.transform = `scaleX(${progress.percent / 100})`;
  }
});

function start() {
  renderShell();
  render();
  void loadBootstrap();
  void loadRelease();
  void initAppUpdates(() => {
    render();
    void refreshSetupReturn();
    void inspectWhenNeeded();
  });
}
start();
