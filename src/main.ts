import { api } from "./ipc";
import { diagnosticExportMessage } from "./diagnostics";
import { appUpdateBusy, handleAppUpdate, initAppUpdates, renderAppUpdate } from "./app-update";
import {
  ACCESS_LABELS,
  CONTENT_VERSION,
  UID_MAX_LENGTH,
  compatibleGameVersion,
  accessReadiness,
  readiness,
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
let state: UiState;
let section: Section = "home";
let renderedSection: Section | undefined;
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
reducedMotion.addEventListener("change", () => {
  if (reducedMotion.matches)
    app.querySelector("#main-content")?.getAnimations().forEach((animation) => animation.cancel());
});
let error = "";
let toastTimer: number;
let busyLabel = "";
let fileProgress = false;
let setupArmed = false;
let setupLeft = false;
let setupRefreshPending = false;

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

async function refreshSetupReturn() {
  if (!state || !setupRefreshPending || state.busy || appUpdateBusy() ||
    document.visibilityState === "hidden") return;
  setupRefreshPending = false;
  await execute(async () => {
    const snapshot = await api.accessStatus();
    state.dashboard = {
      ...state.dashboard, ...snapshot,
      storageWritable: state.dashboard.storageWritable && snapshot.access.granted,
      lastChecked: Date.now(),
    };
    notify("Status akses diperbarui.");
  }, "Memeriksa akses setelah kembali…", { reloadOnError: false });
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
  const disabled = state.busy || appUpdateBusy() || !d.platformSupported ? "disabled" : "";
  let primary: { action: string; label: string } = {
    action: "install",
    label: "Pasang terjemahan",
  };
  if (!d.platformSupported)
    primary = { action: "refresh", label: "Perlu Android" };
  else if (!d.game.installed)
    primary = { action: "setup-game", label: "Pasang game" };
  else if (!access.ready)
    primary = { action: "access-setup", label: "Atur akses game" };
  else if (d.patch.legacyPending || d.patch.recoveryPending)
    primary = { action: "tools", label: "Buka Perawatan" };
  else if (d.mode === "native" && !d.release)
    primary = { action: "refresh", label: "Muat ulang rilis" };
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
      value: !d.platformSupported
        ? "Perlu Android"
        : d.game.installed
          ? `${d.game.packageName.endsWith(".samsung") ? "Galaxy Store" : "Global"} · ${d.game.version}`
          : "Belum terpasang",
      ok: d.game.installed && compatibleGameVersion(d, d.game.version),
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
      value: !d.platformSupported
        ? "Perlu Android"
        : d.resource.ready
          ? d.resource.version
          : "Unduhan belum selesai",
      ok: d.resource.ready && compatibleGameVersion(d, d.resource.version),
      icon: "folder",
    },
  ];
  const contentVersion =
    d.mode === "mock" ? CONTENT_VERSION : (d.release?.tag ?? "Belum dimuat");
  const gameLine =
    d.mode === "mock"
      ? "3.7.0"
      : d.release
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
        <dl class="package-facts"><div><dt>Terbaru</dt><dd>${esc(contentVersion)}</dd></div><div><dt>Untuk game</dt><dd>${esc(gameLine)}</dd></div>${d.patch.installed ? `<div><dt>Terpasang</dt><dd>${esc(d.patch.version)}</dd></div>` : ""}</dl>
        <div class="package-actions"><button class="btn btn-primary" data-action="${primary.action}" ${disabled}>${icon(primaryIcon)} ${primary.label}</button>${d.patch.installed && ready.ready ? `<button class="text-button" data-action="install" ${disabled}>${icon("refresh")} Pasang ulang</button>` : ""}</div>
      </section>
      <section class="card device-card" aria-labelledby="device-title">
        <div class="card-header"><h2 id="device-title">Perangkat</h2><button class="icon-btn" data-action="refresh" aria-label="Periksa perangkat" title="Periksa perangkat" ${state.busy || appUpdateBusy() ? "disabled" : ""}>${icon("refresh")}</button></div>
        <div class="device-list">${deviceRows.map((row) => `<div class="device-row"><span class="device-icon">${icon(row.icon)}</span><div><strong>${row.name}</strong><p>${esc(row.value)}</p></div><span class="device-check ${row.ok ? "" : "pending"}" aria-label="${row.ok ? "Siap" : "Perlu perhatian"}">${icon(row.ok ? "check" : "info")}</span></div>`).join("")}</div>
        ${d.platformSupported ? `<button class="text-button" data-action="access-setup" ${panelState.busy ? "disabled" : ""}>Atur akses game ${icon("arrow")}</button>` : ""}
      </section>
    </div>${renderAccess(panelState)}`;
}

function render() {
  const focused = document.activeElement as HTMLElement | null;
  const focusAction = focused?.dataset.action;
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
  const panelState = appUpdateBusy() ? { ...state, busy: true } : state;
  content.innerHTML = `
    ${error ? `<div class="error-banner" role="alert">${icon("info")}<span>${esc(error)}</span><button class="icon-btn" data-action="dismiss-error" aria-label="Tutup pesan">${icon("close")}</button></div>` : ""}
    ${state.busy ? renderProgress(busyLabel, fileProgress ? state.progress : null) : ""}
    ${section === "home" ? renderHome(panelState) : section === "customize" ? renderCustomize(panelState) : renderTools(panelState)}
    ${renderAppUpdate(state.busy, section === "tools")}`;
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
  if (focusAction)
    Array.from(app.querySelectorAll<HTMLElement>("[data-action]"))
      .find((el) => el.dataset.action === focusAction)
      ?.focus({ preventScroll: true });
  if (uidSelection) {
    const input = app.querySelector<HTMLInputElement>("#uid-input");
    input?.focus({ preventScroll: true });
    input?.setSelectionRange(uidSelection.start, uidSelection.end, uidSelection.direction ?? "none");
  }
}

async function execute(work: () => Promise<void>, label = "Memeriksa status…",
  options: { fileProgress?: boolean; reloadOnError?: boolean } = {}) {
  if (state.busy || appUpdateBusy()) return;
  const busyTrace = api.beginBusy();
  state.busy = true;
  state.progress = null;
  busyLabel = label;
  fileProgress = options.fileProgress ?? false;
  error = "";
  render();
  try {
    await busyTrace;
    await work();
  } catch (cause) {
    error = cause instanceof Error ? cause.message : String(cause);
    notify(error);
    if (state.dashboard.mode === "native" && options.reloadOnError !== false) {
      try {
        state.dashboard = await api.load();
      } catch (refreshError) {
        error += ` Gagal memuat ulang status: ${String(refreshError)}`;
      }
    }
  } finally {
    try {
      await api.endBusy();
    } finally {
      state.busy = false;
      state.progress = null;
      render();
      void refreshSetupReturn();
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
    request_root: "Menunggu izin root dan menghubungkan akses…",
    request_axmanager: "Menghubungkan AxManager; setujui izin jika diminta…",
    use_root: "Memilih Root…",
    use_shizuku: "Memilih Shizuku…",
    use_axmanager: "Memilih AxManager…",
  };
  await execute(async () => {
    state.dashboard = await api.run(operation, uidText);
    if (
      operation === "apply_uid" ||
      operation === "restore_uid" ||
      operation === "uninstall"
    )
      state.uidDraft = state.dashboard.uid.custom ?? "";
    const messages: Record<Operation, string> = {
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
      use_root: "Root dipilih. Izinkan akses untuk melanjutkan.",
      use_shizuku: "Shizuku dipilih.",
      request_axmanager: "AxManager terhubung.",
      use_axmanager: "AxManager dipilih. Hubungkan akses untuk melanjutkan.",
    };
    notify(messages[operation]);
  }, labels[operation], { fileProgress: ["install", "repair", "uninstall", "apply_uid", "restore_uid"].includes(operation) });
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
  if (!state) return;
  if (button.dataset.section || button.classList.contains("brand")) {
    event.preventDefault();
    section = (button.dataset.section as Section) || "home";
    render();
    window.scrollTo({ top: 0, behavior: "instant" });
    return;
  }
  const action = button.dataset.action;
  if (await handleAppUpdate(action, state.busy)) {
    void refreshSetupReturn();
    return;
  }
  if (action === "dismiss-error") {
    error = "";
    render();
  } else if (action === "access-setup") {
    const setup = app.querySelector<HTMLElement>("#access-setup");
    setup?.scrollIntoView({ behavior: reducedMotion.matches ? "instant" : "smooth", block: "start" });
    setup?.focus({ preventScroll: true });
  } else if (action === "tools") {
    section = "tools";
    render();
  } else if (action === "refresh")
    await execute(async () => {
      state.dashboard = await api.load();
      notify("Status diperbarui.");
    });
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
        notify("Selesaikan pengaturan. Status diperiksa saat kembali ke aplikasi.");
      }, "Membuka pengaturan…", { reloadOnError: false });
    } else {
      section = "tools";
      render();
      notify("Simulasi tidak membuka aplikasi lain. Pilih kondisi layanan melalui Skenario simulasi.");
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

async function start() {
  try {
    const dashboard = await api.load();
    state = {
      dashboard,
      busy: false,
      progress: null,
      uidDraft: dashboard.uid.custom ?? "",
    };
    render();
    void initAppUpdates(() => { render(); void refreshSetupReturn(); });
  } catch (cause) {
    app.innerHTML = `<main class="startup-error"><h1>Gagal memuat</h1><p>${esc(String(cause))}</p><button class="btn btn-primary" id="retry-start">Coba lagi</button></main>`;
    document.querySelector("#retry-start")!.addEventListener("click", start);
  }
}
void start();
