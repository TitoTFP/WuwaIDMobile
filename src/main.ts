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
import { escapeHtml as esc, icon } from "./ui";

const app = document.querySelector<HTMLDivElement>("#app")!;
const toast = document.querySelector<HTMLDivElement>("#toast")!;
const dialog = document.querySelector<HTMLDialogElement>("#confirm-dialog")!;
let state: UiState = { dashboard: initialDashboard(api.mode), busy: false, progress: null, uidDraft: "" };
let section: Section = "home";
let renderedSection: Section | undefined;
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
reducedMotion.addEventListener("change", () => {
  if (reducedMotion.matches)
    app.querySelector("#main-content")?.getAnimations({ subtree: true }).forEach((animation) => animation.cancel());
});
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
      state.dashboard.checks.storage = loadCheck("error", String(cause));
      throw cause;
    }
  }, "Memeriksa resource dan patch…");
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
const navigation: { id: Section; label: string }[] = [
  { id: "home", label: "Beranda" },
  { id: "customize", label: "Kustomisasi" },
  { id: "tools", label: "Perawatan" },
];
const logo = '<svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M3 16c5-17 9 17 14 0s9 17 12 0M3 23c5-17 9 17 14 0s9 17 12 0" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
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
    theme === "dark" ? "#14141b" : "#f6f7fa";
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
  const d = state.dashboard;
  const ready = readiness(d);
  const access = accessReadiness(d);
  const disabled = panelState.busy || !d.platformSupported ? "disabled" : "";
  let primary: { action: string; label: string } = {
    action: "install",
    label: "Pasang terjemahan",
  };
  if (d.checks.bootstrap.status !== "ready")
    primary = { action: "bootstrap", label: "Muat perangkat" };
  else if (!d.platformSupported)
    primary = { action: "refresh", label: "Perlu Android" };
  else if (!d.game.installed)
    primary = { action: "setup-game", label: "Pasang game" };
  else if (!access.ready)
    primary = { action: "access-setup", label: "Atur akses game" };
  else if (d.checks.storage.status !== "ready")
    primary = { action: "refresh", label: "Periksa resource" };
  else if (d.patch.legacyPending || d.patch.recoveryPending)
    primary = { action: "tools", label: "Buka Perawatan" };
  else if (d.checks.release.status !== "ready" || !d.release)
    primary = { action: "release", label: "Periksa rilis" };
  else if (!compatibleGameVersion(d, d.game.version))
    primary = { action: "tools", label: "Lihat kompatibilitas" };
  else if (!d.resource.ready)
    primary = {
      action: "launch_game",
      label: "Buka game",
    };
  else if (!ready.ready) primary = { action: "tools", label: "Buka Perawatan" };
  else if (
    d.patch.installed &&
    d.mode === "native" &&
    d.release?.tag !== d.patch.version
  )
    primary = { action: "install", label: "Perbarui terjemahan" };
  else if (d.patch.installed)
    primary = { action: "launch_game", label: "Buka game" };
  const deviceRows = [
    {
      name: "Wuthering Waves",
      value: d.checks.bootstrap.status !== "ready" ? checkLabel(d.checks.bootstrap) : !d.platformSupported
        ? "Perlu Android"
        : d.game.installed
          ? `${d.game.packageName.endsWith(".samsung") ? "Galaxy Store" : "Global"} · ${d.game.version}`
          : "Belum terpasang",
      ok: d.checks.bootstrap.status === "ready" && d.checks.release.status === "ready" &&
        d.game.installed && compatibleGameVersion(d, d.game.version),
      icon: "game",
    },
    {
      name: ACCESS_LABELS[d.access.method],
      value: access.label,
      ok: access.ready,
      icon: "shield",
    },
    {
      name: "Resource",
      value: d.checks.storage.status !== "ready" ? checkLabel(d.checks.storage) : !d.platformSupported
        ? "Perlu Android"
        : d.resource.ready
          ? d.resource.version
          : "Unduhan belum selesai",
      ok: d.checks.storage.status === "ready" && d.checks.release.status === "ready" &&
        d.resource.ready && compatibleGameVersion(d, d.resource.version),
      icon: "folder",
    },
  ];
  const contentVersion =
    d.release?.tag ?? checkLabel(d.checks.release);
  const gameLine = d.release
    ? `${d.release.gameVersion.split(".").slice(0, 2).join(".")}.*`
    : "—";
  const primaryIcon = primary.action === "launch_game" ? "external"
    : primary.action === "tools" ? "tools"
      : primary.action === "refresh" ? "refresh"
        : primary.action === "install" ? "download" : "arrow";
  return `<section class="page-heading">
      <h1>Bahasa Indonesia</h1><p>Wuthering Waves</p>
    </section>
    <div class="home-grid">
      <section class="card translation-card" aria-labelledby="translation-title">
        <div class="card-header"><h2 id="translation-title">Terjemahan</h2><span class="status-badge ${ready.ready ? "good" : "warning"}">${icon(ready.ready ? "check" : "info")}${esc(ready.ready && d.patch.installed ? "Terpasang" : ready.label)}</span></div>
        ${!ready.ready ? `<p class="package-detail">${esc(ready.detail)}</p>` : ""}
        <dl class="package-facts"><div><dt>Terbaru</dt><dd>${esc(contentVersion)}</dd></div><div><dt>Untuk game</dt><dd>${esc(gameLine)}</dd></div>${d.checks.storage.status === "ready" && d.patch.installed ? `<div><dt>Terpasang</dt><dd>${esc(d.patch.version)}</dd></div>` : ""}</dl>
        ${d.checks.release.status === "error" ? `<p class="package-detail">${esc(d.checks.release.error ?? "Rilis gagal dimuat.")}</p>` : ""}
        <button class="text-button" data-action="release" ${state.busy || appUpdateBusy() || d.checks.release.status === "loading" ? "disabled" : ""}>${icon("refresh")} Periksa rilis</button>
        <div class="package-actions"><button class="btn btn-primary" data-action="${primary.action}" ${disabled || (primary.action === "release" && d.checks.release.status === "loading" ? "disabled" : "")}>${icon(primaryIcon)} ${primary.label}</button>${d.patch.installed && ready.ready ? `<button class="text-button" data-action="install" ${disabled}>${icon("refresh")} Pasang ulang</button>` : ""}</div>
      </section>
      ${renderAccess(panelState)}
    </div>
      <section class="card device-card" aria-labelledby="device-title">
        <div class="card-header"><h2 id="device-title">Perangkat</h2><button class="icon-btn" data-action="refresh" aria-label="Periksa perangkat" title="Periksa perangkat" ${panelState.busy || !d.platformSupported ? "disabled" : ""}>${icon("refresh")}</button></div>
        <div class="device-list">${deviceRows.map((row) => `<div class="device-row"><span class="device-icon">${icon(row.icon)}</span><div><strong>${row.name}</strong><p>${esc(row.value)}</p></div><span class="device-check ${row.ok ? "" : "pending"}" aria-label="${row.ok ? "Siap" : "Perlu perhatian"}">${icon(row.ok ? "check" : "info")}</span></div>`).join("")}</div>
        ${d.platformSupported ? `<button class="text-button" data-action="access-setup" ${panelState.busy ? "disabled" : ""}>Atur akses game ${icon("arrow")}</button>` : ""}
      </section>
    `;
}

function render() {
  const focused = document.activeElement as HTMLElement | null;
  const focusAction = focused?.dataset.action;
  const accessSelectorFocused = focused?.classList.contains("access-methods");
  const uidSelection = focused instanceof HTMLInputElement && focused.id === "uid-input"
    ? { start: focused.selectionStart, end: focused.selectionEnd, direction: focused.selectionDirection }
    : null;
  if (!app.querySelector(".app-header")) {
    app.innerHTML = `<header class="app-header"><div class="header-inner"><a class="brand" href="#home" aria-label="WuwaIDMobile Beranda"><span class="brand-symbol">${logo}</span><span>wuwa<span class="brand-id">id</span></span></a><nav class="desktop-nav" aria-label="Navigasi utama">${navigation.map((item) => `<button class="nav-item ${section === item.id ? "active" : ""}" data-section="${item.id}" ${section === item.id ? 'aria-current="page"' : ""}>${item.label}</button>`).join("")}</nav>${renderThemeControl()}</div></header>
    <div class="workspace">${state.dashboard.mode === "mock" ? `<p class="mode-notice">${icon("info")} Simulasi · file game tidak diubah</p>` : ""}
    <main id="main-content" tabindex="-1"></main><footer class="footer">Tidak berafiliasi dengan Kuro Games.</footer></div>
    <nav class="mobile-nav" aria-label="Navigasi seluler">${navigation.map((item) => `<button data-section="${item.id}" class="${section === item.id ? "active" : ""}" ${section === item.id ? 'aria-current="page"' : ""}>${icon(item.id)}<span>${item.label}</span></button>`).join("")}</nav>`;
  }
  app.querySelectorAll<HTMLButtonElement>(".desktop-nav [data-section], .mobile-nav [data-section]").forEach((button) => {
    const active = button.dataset.section === section;
    button.classList.toggle("active", active);
    if (active) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  const content = app.querySelector<HTMLElement>("#main-content")!;
  const panelState = appUpdateBusy() || state.dashboard.checks.bootstrap.status !== "ready" ||
    state.dashboard.checks.access.status === "loading" ? { ...state, busy: true } : state;
  const previousAccess = content.querySelector<HTMLElement>("#access-setup");
  const animateAccess = previousAccess && renderedSection === section && !reducedMotion.matches &&
    (previousAccess.dataset.accessMethod !== state.dashboard.access.method ||
      previousAccess.getAnimations().some((animation) => animation.playState === "running"));
  // Carry an interrupted transition's current geometry into the next render.
  const previousBox = animateAccess ? previousAccess.getBoundingClientRect() : null;
  const previousScroll = previousBox ? { x: window.scrollX, y: window.scrollY } : null;
  content.innerHTML = `
    ${error ? `<div class="error-banner" role="alert">${icon("info")}<span>${esc(error)}</span><button class="icon-btn" data-action="dismiss-error" aria-label="Tutup pesan">${icon("close")}</button></div>` : ""}
    ${state.dashboard.checks.bootstrap.status === "error" ? `<div class="error-banner" role="alert"><span>${esc(state.dashboard.checks.bootstrap.error ?? "Perangkat gagal dimuat.")}</span><button class="btn btn-secondary" data-action="bootstrap">Coba lagi</button></div>` : ""}
    ${state.busy && showBusyProgress ? renderProgress(busyLabel, fileProgress ? state.progress : null) : ""}
    ${section === "home" ? renderHome(panelState) : section === "customize" ? renderCustomize(panelState) : renderTools(panelState)}
    ${renderAppUpdate(panelState.busy, section === "tools")}`;
  const nextAccess = content.querySelector<HTMLElement>("#access-setup");
  if (previousBox && previousScroll && nextAccess) {
    const nextBox = nextAccess.getBoundingClientRect();
    const offset = previousBox.top + previousScroll.y - nextBox.top - window.scrollY;
    nextAccess.animate(
      [
        { height: `${previousBox.height}px`, transform: `translateY(${offset}px)`, overflow: "hidden" },
        { height: `${nextBox.height}px`, transform: "translateY(0)", overflow: "hidden" },
      ],
      { duration: 180, easing: "cubic-bezier(.2, .8, .2, 1)" },
    );
    nextAccess.querySelector(".access-details")?.animate(
      [{ opacity: 0.4 }, { opacity: 1 }],
      { duration: 160, easing: "ease-out" },
    );
    if (window.scrollX !== previousScroll.x || window.scrollY !== previousScroll.y)
      window.scrollTo({ left: previousScroll.x, top: previousScroll.y, behavior: "instant" });
  }
  if (renderedSection !== section) {
    content.getAnimations().forEach((animation) => animation.cancel());
    if (!reducedMotion.matches)
      content.animate(
        [{ opacity: 0.65, transform: "translateY(4px)" }, { opacity: 1, transform: "translateY(0)" }],
        { duration: 180, easing: "cubic-bezier(.2, .8, .2, 1)" },
      );
    renderedSection = section;
  }
  dialog.querySelector("p")!.textContent =
    state.dashboard.mode === "mock"
      ? "Terjemahan simulasi dihapus dan UID direset. File game tidak diubah."
      : "Paket dan mount WuwaIDMobile dihapus. File game asli tetap utuh.";
  if (focusAction) {
    const target = Array.from(app.querySelectorAll<HTMLElement>("[data-action]"))
      .find((el) => el.dataset.action === focusAction);
    if (focusAction.startsWith("use_") && target instanceof HTMLButtonElement && target.disabled)
      nextAccess?.querySelector<HTMLElement>(".access-methods")?.focus({ preventScroll: true });
    else target?.focus({ preventScroll: true });
  } else if (accessSelectorFocused) {
    nextAccess?.querySelector<HTMLElement>(".access-methods")?.focus({ preventScroll: true });
  }
  if (uidSelection) {
    const input = app.querySelector<HTMLInputElement>("#uid-input");
    input?.focus({ preventScroll: true });
    input?.setSelectionRange(uidSelection.start, uidSelection.end, uidSelection.direction ?? "none");
  }
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
    notify(error);
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
    section = (button.dataset.section as Section) || "home";
    inspectionRequested = section !== "home" && state.dashboard.checks.storage.status === "not_checked";
    render();
    window.scrollTo({ top: 0, behavior: "instant" });
    void inspectWhenNeeded();
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
    section = "tools";
    inspectionRequested = state.dashboard.checks.storage.status === "not_checked";
    render();
    void inspectWhenNeeded();
  } else if (action === "bootstrap") {
    void loadBootstrap();
  } else if (action === "release") {
    if (!state.busy && !appUpdateBusy()) void loadRelease();
  } else if (action === "check-access") {
    setupRefreshPending = true;
    void refreshSetupReturn();
  } else if (action === "refresh") {
    await inspectStorage();
  }
  else if (action === "restore-uid") await operate("restore_uid");
  else if (action === "uninstall") dialog.showModal();
  else if (action === "save-diagnostics" || action === "share-diagnostics")
    await execute(async () => {
      const result = await api.exportDiagnostics(action === "save-diagnostics" ? "save" : "share");
      const message = diagnosticExportMessage(result);
      if (result.status === "failed") throw new Error(message);
      notify(message);
    }, "Menyiapkan laporan diagnostik…");
  else if (action === "setup-game" || action === "setup-shizuku" || action === "setup-axmanager") {
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
      section = "tools";
      render();
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
    const card = app.querySelector<HTMLElement>(".progress-card");
    if (card) card.outerHTML = renderProgress(busyLabel, progress);
  }
});

function start() {
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
