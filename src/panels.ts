import {
  ACCESS_LABELS,
  UID_MAX_LENGTH,
  compatibleGameVersion,
  installationPrerequisites,
  readiness,
  type Scenario,
  type UiState,
} from "./model";
import { escapeHtml, icon } from "./ui";

const scenarios: Array<{ id: Scenario; label: string }> = [
  { id: "ready", label: "Semua siap" },
  { id: "installed", label: "Terjemahan terpasang" },
  { id: "game_missing", label: "Game belum terpasang" },
  { id: "unsupported", label: "Versi tidak didukung" },
  { id: "resource_missing", label: "Resource belum siap" },
  { id: "shizuku_off", label: "Shizuku belum aktif" },
  { id: "permission", label: "Perlu izin Shizuku" },
  { id: "root_permission", label: "Perlu izin root" },
  { id: "root_ready", label: "Root siap" },
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

export function renderCustomize(state: UiState): string {
  const d = state.dashboard;
  if (!d.uidAvailable)
    return `<section class="page-heading"><h1>Kustomisasi</h1></section><section class="card unavailable-studio"><h2>Perlu Android</h2><p class="muted">Gunakan aplikasi Android dengan Shizuku, Root, atau AxManager.</p><button class="text-button" data-section="home">Ke beranda ${icon("arrow")}</button></section>`;
  const status = readiness(d);
  const canCustomize = !state.busy && status.ready;
  const draft = state.uidDraft.trim();
  const original = d.uid.original || "UID asli game";
  const current = d.uid.custom ?? original;
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
    d.storageWritable &&
    d.game.installed &&
    d.access.granted &&
    d.resource.ready &&
    !d.patch.recoveryPending;
  const canRecover =
    d.platformSupported &&
    d.storageWritable &&
    d.game.installed &&
    d.access.granted &&
    d.resource.ready &&
    (d.patch.recoveryPending || d.patch.legacyPending);
  const canRepair =
    !state.busy && (canRecover || (dependenciesReady && d.patch.installed));
  const currentScenario: Scenario = !d.game.installed
    ? "game_missing"
    : !compatibleGameVersion(d, d.game.version)
      ? "unsupported"
      : d.access.method === "root" && !d.access.granted
        ? "root_permission"
        : d.access.method === "shizuku" && !d.shizuku.running
          ? "shizuku_off"
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
  const scenarioOptions = scenarios
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
  const methods = (["shizuku", "root", "axmanager"] as const).map((method) =>
    `<button type="button" data-action="use_${method}" aria-pressed="${d.access.method === method}" ${state.busy || d.access.method === method ? "disabled" : ""}>${ACCESS_LABELS[method]}</button>`,
  ).join("");
  return `<section class="page-heading"><h1>Perawatan</h1></section>
    <section class="health-banner" aria-label="Status pemasangan"><span class="health-icon ${status.ready ? "healthy" : "needs-attention"}">${icon(status.ready ? "check" : "info")}</span><div><h2>${escapeHtml(status.label)}</h2>${!status.ready ? `<p class="muted">${escapeHtml(status.detail)}</p>` : ""}</div><button class="icon-btn" data-action="refresh" aria-label="Periksa perangkat" title="Periksa perangkat" ${state.busy ? "disabled" : ""}>${icon("refresh")}</button></section>
    <div class="maintenance-grid">
      ${d.platformSupported ? `<section class="card access-card" aria-labelledby="access-title">
        <div class="card-header"><h2 id="access-title">Akses game</h2>${d.access.granted ? `<span class="status-badge good">${icon("check")}Terhubung</span>` : ""}</div>
        <div class="access-methods" role="group" aria-label="Metode akses">${methods}</div>
        <p class="muted access-description">${d.access.method === "root" ? "Akses langsung melalui su."
          : d.access.method === "axmanager" ? "Android 8+ · user utama · AxManager 3.0.0+"
          : "Akses tanpa root melalui Shizuku."}</p>
        ${d.access.method === "root" && !d.access.granted ? `<p class="muted">Minta izin lewat tombol di bawah. Jika ditolak atau su tidak tersedia, pilih Shizuku.</p><div class="actions">${button("request_root", "Izinkan root", state.busy)}</div>` : ""}
        ${d.access.method === "axmanager" ? `<p class="muted">${!d.axmanager.installed ? "Pasang AxManager untuk melanjutkan."
          : !d.axmanager.supported ? "Versi atau perangkat belum didukung."
          : "Aktifkan server, lalu pilih Allow all the time. Hubungkan ulang setiap aplikasi dibuka."}</p><div class="actions">${button("setup-axmanager", "Buka AxManager", state.busy || !d.axmanager.installed)}${button("request_axmanager", "Hubungkan", state.busy || !d.axmanager.supported || d.access.granted)}</div>` : ""}
      </section>` : ""}
      <section class="card maintenance-card" aria-labelledby="maintenance-title"><div class="card-header"><h2 id="maintenance-title">Pengelolaan</h2></div><div class="tool-list">${rows}</div></section>
    </div>
    <div class="tools-detail-grid">
      <details class="card technical-details"><summary>Detail teknis</summary><dl class="info-list">
        <div class="info-row"><dt>Game</dt><dd>${d.game.installed ? "Terpasang" : "Belum terpasang"} · ${escapeHtml(d.game.version)}<code>${escapeHtml(d.game.packageName)}</code></dd></div>
        <div class="info-row"><dt>Shizuku</dt><dd>${d.shizuku.running ? "Aktif" : "Tidak aktif"} · ${d.shizuku.granted ? "Diizinkan" : "Belum diizinkan"}</dd></div>
        <div class="info-row"><dt>AxManager</dt><dd>${!d.axmanager.installed ? "Tidak tersedia / AX-Stealth" : `${escapeHtml(d.axmanager.version)} · ${!d.axmanager.supported ? "Belum didukung" : d.access.method !== "axmanager" ? "Tidak dipilih" : d.axmanager.running ? "Akses server tersedia" : "Belum tersambung / belum diizinkan"}`}</dd></div>
        <div class="info-row"><dt>Akses terpilih</dt><dd>${ACCESS_LABELS[d.access.method]} · ${d.access.granted ? "Diizinkan" : "Belum diizinkan"}</dd></div>
        <div class="info-row"><dt>Resource</dt><dd>${d.resource.ready ? "Siap" : "Belum siap"} · ${escapeHtml(d.resource.version)}</dd></div>
        <div class="info-row"><dt>Terjemahan</dt><dd>${d.patch.installed ? "Terpasang" : "Belum terpasang"} · ${d.patch.healthy ? "Sehat" : "Perlu pemeriksaan"} · ${escapeHtml(d.patch.version)}${d.patch.recoveryPending ? " · Pemulihan tertunda" : ""}</dd></div>
        <div class="info-row"><dt>Rilis terbaru</dt><dd>${escapeHtml(d.mode === "mock" ? "3.7.0-R2 (contoh)" : (d.release?.tag ?? "Belum dimuat"))}</dd></div>
        <div class="info-row"><dt>Diperiksa</dt><dd>${escapeHtml(new Date(d.lastChecked).toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" }))}</dd></div>
        ${d.uidAvailable ? `<div class="info-row"><dt>Tampilan UID</dt><dd>${escapeHtml(d.uid.custom ?? d.uid.original)}</dd></div>` : ""}
        ${d.patch.message ? `<div class="info-row"><dt>Pemasangan</dt><dd>${escapeHtml(d.patch.message)}</dd></div>` : ""}
      </dl></details>
      ${d.mode === "mock" ? `<section class="card scenario-card"><label class="field-label" for="scenario-select">Skenario simulasi</label><select class="select" id="scenario-select"${state.busy ? " disabled" : ""}>${scenarioOptions}</select></section>` : ""}
    </div>`;
}
