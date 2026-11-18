import {
  ACCESS_LABELS,
  UID_MAX_LENGTH,
  compatibleGameVersion,
  installationPrerequisites,
  accessReadiness,
  readiness,
  checkLabel,
  type Scenario,
  type Progress,
  type UiState,
} from "./model";
import { escapeHtml, icon } from "./ui";

const scenarios: Array<{ id: Scenario; label: string }> = [
  { id: "ready", label: "Semua siap" },
  { id: "installed", label: "Terjemahan terpasang" },
  { id: "game_missing", label: "Game belum terpasang" },
  { id: "unsupported", label: "Versi tidak didukung" },
  { id: "resource_missing", label: "Resource belum siap" },
  { id: "shizuku_missing", label: "Shizuku belum terpasang" },
  { id: "shizuku_off", label: "Shizuku belum aktif" },
  { id: "permission", label: "Perlu izin Shizuku" },
  { id: "root_permission", label: "Perlu izin root" },
  { id: "root_ready", label: "Root siap" },
  { id: "axmanager_missing", label: "AxManager belum terpasang" },
  { id: "axmanager_off", label: "AxManager belum aktif" },
  { id: "axmanager_permission", label: "Perlu izin AxManager" },
  { id: "axmanager_ready", label: "AxManager siap" },
  { id: "recovery", label: "Perlu pemulihan" },
  { id: "legacy", label: "Pemasangan versi lama" },
];

function button(
  action: string,
  label: string,
  disabled: boolean,
  className = "btn btn-secondary",
): string {
  return `<button type="button" class="${className}" data-action="${action}"${disabled ? ' disabled aria-disabled="true"' : ""}>${label}</button>`;
}

export function renderAccess(state: UiState): string {
  const d = state.dashboard;
  if (d.checks.bootstrap.status !== "ready")
    return `<section id="access-setup" class="card access-card"><h2>Akses game</h2><p class="muted">${escapeHtml(checkLabel(d.checks.bootstrap))}</p></section>`;
  if (!d.platformSupported) return "";
  const access = accessReadiness(d);
  const methods = (["axmanager", "shizuku", "root"] as const).map((method) =>
    `<button type="button" data-action="use_${method}" aria-pressed="${d.access.method === method}" ${state.busy || d.access.method === method ? "disabled" : ""}>${ACCESS_LABELS[method]}</button>`,
  ).join("");
  let guidance: string;
  let actions: string;
  if (d.checks.access.status !== "ready") {
    guidance = d.checks.access.error ? `<p class="muted">${escapeHtml(d.checks.access.error)}</p>` : "";
    actions = button("check-access", "Periksa akses", state.busy);
  } else if (d.access.method === "axmanager") {
    guidance = `<p class="muted access-description">Direkomendasikan · Android 8+ · Profil utama · Aplikasi/server 3.0.0+</p>
      ${d.axmanager.installed && !d.axmanager.supported
        ? `<p class="muted">Periksa syarat di atas. Perbarui aplikasi/server bila perlu, atau pilih metode lain.</p>` : ""}`;
    actions = button("setup-axmanager", d.axmanager.installed ? "Buka AxManager" : "Unduh AxManager", state.busy) +
      button("request_axmanager", "Hubungkan", state.busy || !d.axmanager.installed || !d.axmanager.supported || access.ready, "btn btn-primary");
  } else if (d.access.method === "shizuku") {
    guidance = !d.shizuku.installed ? `<p class="muted">Pasang Shizuku, lalu aktifkan layanannya.</p>`
      : !d.shizuku.running ? `<p class="muted">Aktifkan layanan di Shizuku.</p>`
      : access.ready ? ""
      : d.shizuku.granted ? `<p class="muted">Izin sudah ada. Hubungkan untuk melanjutkan.</p>`
      : `<p class="muted">Setujui izin Shizuku untuk melanjutkan.</p>`;
    actions = button("setup-shizuku", d.shizuku.installed ? "Buka Shizuku" : "Unduh Shizuku", state.busy) +
      button("request_permission", d.shizuku.granted ? "Hubungkan Shizuku" : "Izinkan Shizuku", state.busy || !d.shizuku.installed || !d.shizuku.running || access.ready, "btn btn-primary");
  } else {
    guidance = !access.ready ? `<p class="muted">Izinkan akses melalui pengelola root.</p>` : "";
    actions = button("request_root", "Izinkan root", state.busy || access.ready, "btn btn-primary");
  }
  return `<section id="access-setup" tabindex="-1" class="card access-card" data-access-method="${d.access.method}" aria-busy="${state.busy}" aria-labelledby="access-title">
    <div class="card-header"><h2 id="access-title">Akses game</h2></div>
    <div class="access-methods" role="group" tabindex="-1" aria-label="Metode akses">${methods}</div>
    <div class="access-details">
      <span class="status-badge ${access.ready ? "good" : "warning"}">${icon(access.ready ? "check" : "info")}${escapeHtml(access.label)}</span>
      ${guidance}
      <div class="actions">${actions}</div>
      ${d.checks.access.status === "ready" ? button("refresh", `${icon("refresh")} Periksa akses`, state.busy, "text-button") : ""}
    </div>
  </section>`;
}

export function renderProgress(label: string, progress: Progress | null): string {
  return `<div class="progress-card" role="status" aria-live="polite"><div><strong>${escapeHtml(progress?.label ?? label)}</strong>${progress ? `<span>${progress.percent}%</span>` : icon("refresh")}</div>
    ${progress ? `<div class="progress-track" role="progressbar" aria-label="Progres operasi berkas" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress.percent}"><span class="progress-fill" style="transform: scaleX(${progress.percent / 100})"></span></div>` : ""}</div>`;
}

export function renderCustomize(state: UiState): string {
  const d = state.dashboard;
  if (d.checks.bootstrap.status !== "ready")
    return `<section class="page-heading"><h1>Kustomisasi</h1></section><section class="card"><p class="muted">${escapeHtml(checkLabel(d.checks.bootstrap))}</p></section>`;
  if (!d.uidAvailable)
    return `<section class="page-heading"><h1>Kustomisasi</h1></section><section class="card unavailable-studio"><h2>Perlu Android</h2><p class="muted">Gunakan aplikasi Android dengan Shizuku, Root, atau AxManager.</p><button class="text-button" data-section="home">Ke beranda ${icon("arrow")}</button></section>`;
  const status = readiness(d);
  const canCustomize = !state.busy && status.ready;
  const draft = state.uidDraft.trim();
  const original = d.checks.storage.status === "ready" ? (d.uid.original || "UID asli game") : checkLabel(d.checks.storage);
  const current = d.checks.storage.status === "ready" ? (d.uid.custom ?? original) : original;
  const canApply =
    canCustomize &&
    draft.length > 0 &&
    draft.length <= UID_MAX_LENGTH &&
    draft !== current;
  const gate = !status.ready ? status.detail : "";

  return `<section class="page-heading"><h1>Kustomisasi</h1></section>
      <section class="card uid-editor" aria-labelledby="uid-title">
        <div class="card-header"><h2 id="uid-title">UID kustom</h2>${icon("customize")}</div>
        ${gate ? `<p class="muted uid-gate">${escapeHtml(gate)}</p>` : ""}
        ${d.checks.storage.status !== "ready" && accessReadiness(d).ready ? button("refresh", "Periksa resource", state.busy) : ""}
        <form id="uid-form">
          <label class="field-label" for="uid-input">Teks pengganti</label>
          <input class="uid-input" id="uid-input" name="customUid" type="text" maxlength="${UID_MAX_LENGTH}" value="${escapeHtml(state.uidDraft)}" placeholder="Rover Indonesia" autocomplete="off" spellcheck="false" aria-describedby="uid-help" ${state.busy ? "disabled" : ""} />
          <div class="uid-input-meta"><span id="uid-help">Maks. ${UID_MAX_LENGTH} karakter</span><span id="uid-count">${state.uidDraft.length}/${UID_MAX_LENGTH}</span></div>
          <div class="actions"><button class="btn btn-primary" id="apply-uid" type="submit" ${canApply ? "" : "disabled"}>${icon("check")} Terapkan</button>${button("restore-uid", `${icon("refresh")} Pulihkan`, !canCustomize || d.uid.custom === null)}</div>
        </form>
        <dl class="info-list uid-info">
          <div class="info-row"><dt>Bawaan</dt><dd>${escapeHtml(original)}</dd></div>
          <div class="info-row"><dt>Aktif</dt><dd id="uid-active">${escapeHtml(current)}</dd></div>
        </dl>
      </section>`;
}

export function renderTools(state: UiState): string {
  const d = state.dashboard;
  const status = readiness(d);
  const dependenciesReady = installationPrerequisites(d);
  const canUninstall =
    d.platformSupported &&
    d.checks.storage.status === "ready" &&
    accessReadiness(d).ready &&
    d.storageWritable &&
    d.game.installed &&
    d.access.granted &&
    d.resource.ready &&
    !d.patch.recoveryPending;
  const canRecover =
    d.platformSupported &&
    d.storageWritable &&
    d.checks.storage.status === "ready" &&
    accessReadiness(d).ready &&
    d.game.installed &&
    d.access.granted &&
    d.resource.ready &&
    (d.patch.recoveryPending || d.patch.legacyPending);
  const canRepair =
    !state.busy && (canRecover || (dependenciesReady && d.patch.installed));
  const currentScenario: Scenario | null = d.checks.storage.status !== "ready" ? null : !d.game.installed
    ? "game_missing"
    : !compatibleGameVersion(d, d.game.version)
      ? "unsupported"
      : d.access.method === "root" && !d.access.granted
        ? "root_permission"
        : d.access.method === "shizuku" && !d.shizuku.installed
          ? "shizuku_missing"
        : d.access.method === "shizuku" && !d.shizuku.running
          ? "shizuku_off"
          : d.access.method === "axmanager" && !d.axmanager.installed
            ? "axmanager_missing"
          : d.access.method === "axmanager" && !d.axmanager.running
            ? "axmanager_off"
          : !d.access.granted
            ? d.access.method === "axmanager" ? "axmanager_permission" : "permission"
          : !d.resource.ready
            ? "resource_missing"
            : d.patch.recoveryPending
              ? "recovery"
              : d.patch.legacyPending
                ? "legacy"
              : d.patch.installed
                ? "installed"
                : d.access.method === "root" ? "root_ready" : d.access.method === "axmanager" ? "axmanager_ready" : "ready";
  const scenarioOptions = `<option value="" disabled${currentScenario === null ? " selected" : ""}>Pilih skenario</option>` + scenarios
    .map(
      ({ id, label }) =>
        `<option value="${id}"${id === currentScenario ? " selected" : ""}>${label}</option>`,
    )
    .join("");
  const rows = [
    {
      name: d.patch.legacyPending && !d.patch.recoveryPending ? "Migrasi versi lama" : "Perbaiki pemasangan",
      detail: d.patch.recoveryPending
        ? "Pulihkan transaksi tertunda."
        : d.patch.legacyPending ? "Migrasikan berkas terverifikasi, tanpa unduhan." : "Periksa dan pulihkan paket.",
      actions: button("repair", d.patch.legacyPending && !d.patch.recoveryPending ? "Migrasikan" : "Perbaiki", !canRepair),
      icon: "shield",
    },
    {
      name: "Diagnostik",
      detail: "Laporan JSON · UID disamarkan",
      actions: button("save-diagnostics", "Simpan", state.busy) + button("share-diagnostics", "Bagikan", state.busy),
      icon: "download",
    },
    {
      name: "Hapus terjemahan",
      detail: "",
      actions: button("uninstall", "Hapus", state.busy || !d.patch.installed || !canUninstall, "btn btn-danger"),
      icon: "trash",
    },
  ].map((row) =>
    `<div class="tool-row ${row.icon === "trash" ? "tool-destructive" : ""}"><span class="tool-icon">${icon(row.icon)}</span><div class="tool-copy"><h3>${row.name}</h3>${row.detail ? `<p class="muted">${row.detail}</p>` : ""}</div><div class="actions">${row.actions}</div></div>`,
  ).join("");
  const needsAccess = d.platformSupported && !accessReadiness(d).ready;
  return `<section class="page-heading"><h1>Perawatan</h1></section>
    <section class="health-banner" aria-label="Status pemasangan"><span class="health-icon ${status.ready ? "healthy" : "needs-attention"}">${icon(status.ready ? "check" : "info")}</span><div><h2>${escapeHtml(status.label)}</h2>${!status.ready ? `<p class="muted">${escapeHtml(status.detail)}</p>` : ""}</div><button class="icon-btn" data-action="${needsAccess ? "access-setup" : "refresh"}" aria-label="${needsAccess ? "Atur akses game di Beranda" : "Periksa perangkat"}" title="${needsAccess ? "Atur akses game di Beranda" : "Periksa perangkat"}" ${state.busy ? "disabled" : ""}>${icon(needsAccess ? "arrow" : "refresh")}</button></section>
      <section class="card maintenance-card" aria-labelledby="maintenance-title"><div class="card-header"><h2 id="maintenance-title">Pengelolaan</h2></div><div class="tool-list">${rows}</div></section>
    <div class="tools-detail-grid">
      <details class="card technical-details"><summary>Detail teknis</summary><dl class="info-list">
        <div class="info-row"><dt>Game</dt><dd>${d.checks.bootstrap.status !== "ready" ? checkLabel(d.checks.bootstrap) : `${d.game.installed ? "Terpasang" : "Belum terpasang"} · ${escapeHtml(d.game.version)}`}<code>${escapeHtml(d.game.packageName)}</code></dd></div>
        <div class="info-row"><dt>Shizuku</dt><dd>${d.checks.access.status !== "ready" ? checkLabel(d.checks.access) : !d.shizuku.installed ? "Belum terpasang" : `${d.shizuku.running ? "Aktif" : "Tidak aktif"} · ${d.shizuku.granted ? "Diizinkan" : "Belum diizinkan"}`}</dd></div>
        <div class="info-row"><dt>AxManager</dt><dd>${d.checks.access.status !== "ready" ? checkLabel(d.checks.access) : !d.axmanager.installed ? "Tidak tersedia / AX-Stealth" : `${escapeHtml(d.axmanager.version)} · ${!d.axmanager.supported ? "Belum didukung" : d.access.method !== "axmanager" ? "Tidak dipilih" : d.axmanager.running ? "Akses server tersedia" : "Belum tersambung / belum diizinkan"}`}</dd></div>
        <div class="info-row"><dt>Akses terpilih</dt><dd>${d.checks.bootstrap.status !== "ready" ? checkLabel(d.checks.bootstrap) : `${ACCESS_LABELS[d.access.method]} · ${d.checks.access.status !== "ready" ? checkLabel(d.checks.access) : accessReadiness(d).ready ? "Terhubung" : "Belum terhubung"}`}</dd></div>
        <div class="info-row"><dt>Resource</dt><dd>${d.checks.storage.status !== "ready" ? checkLabel(d.checks.storage) : `${d.resource.ready ? "Siap" : "Belum siap"} · ${escapeHtml(d.resource.version)}`}</dd></div>
        <div class="info-row"><dt>Terjemahan</dt><dd>${d.checks.storage.status !== "ready" ? checkLabel(d.checks.storage) : `${d.patch.installed ? "Terpasang" : "Belum terpasang"} · ${d.patch.healthy ? "Sehat" : "Perlu pemeriksaan"} · ${escapeHtml(d.patch.version)}${d.patch.recoveryPending ? " · Pemulihan tertunda" : ""}`}</dd></div>
        <div class="info-row"><dt>Rilis terbaru</dt><dd>${escapeHtml(d.release?.tag ?? checkLabel(d.checks.release))}</dd></div>
        <div class="info-row"><dt>Diperiksa</dt><dd>${d.lastChecked ? escapeHtml(new Date(d.lastChecked).toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" })) : "—"}</dd></div>
        ${d.uidAvailable && d.checks.storage.status === "ready" ? `<div class="info-row"><dt>Tampilan UID</dt><dd>${escapeHtml(d.uid.custom ?? d.uid.original)}</dd></div>` : ""}
        ${d.patch.message ? `<div class="info-row"><dt>Pemasangan</dt><dd>${escapeHtml(d.patch.message)}</dd></div>` : ""}
      </dl></details>
      ${d.mode === "mock" ? `<section class="card scenario-card"><label class="field-label" for="scenario-select">Skenario simulasi</label><select class="select" id="scenario-select"${state.busy ? " disabled" : ""}>${scenarioOptions}</select></section>` : ""}
    </div>`;
}
