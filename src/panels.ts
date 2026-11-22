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
import { escapeHtml, icon, statusBadge } from "./ui";

const scenarios: Array<{ id: Scenario; label: string }> = [
  { id: "ready", label: "Semua siap" },
  { id: "installed", label: "Terjemahan terpasang" },
  { id: "game_missing", label: "Game belum terpasang" },
  { id: "unsupported", label: "Versi tidak didukung" },
  { id: "resource_missing", label: "File game belum siap" },
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
  return `<button type="button" class="${className}" data-action="${action}"${disabled ? " disabled" : ""}>${label}</button>`;
}

export function renderAccess(state: UiState): string {
  const d = state.dashboard;
  if (d.checks.bootstrap.status !== "ready" || !d.platformSupported) return "";
  const access = accessReadiness(d);
  const checked = d.checks.access.status === "ready";
  const methods = (["axmanager", "shizuku", "root"] as const).map((method) =>
    `<button type="button" data-action="use_${method}" aria-pressed="${d.access.method === method}" ${state.busy || d.access.method === method ? "disabled" : ""}>${ACCESS_LABELS[method]}</button>`,
  ).join("");
  let actions = "";
  if (checked && d.access.method === "axmanager") {
    actions = button("setup-axmanager", d.axmanager.installed ? "Buka AxManager" : "Unduh AxManager", state.busy);
    if (!access.ready)
      actions += button("request_axmanager", "Hubungkan", state.busy || !d.axmanager.installed || !d.axmanager.supported, "btn btn-primary");
  } else if (checked && d.access.method === "shizuku") {
    actions = button("setup-shizuku", d.shizuku.installed ? "Buka Shizuku" : "Unduh Shizuku", state.busy);
    if (!access.ready)
      actions += button("request_permission", d.shizuku.granted ? "Hubungkan Shizuku" : "Izinkan Shizuku", state.busy || !d.shizuku.installed || !d.shizuku.running, "btn btn-primary");
  } else if (checked && !access.ready) {
    actions = button("request_root", "Izinkan root", state.busy, "btn btn-primary");
  }
  const axRequirements = checked && d.access.method === "axmanager" && (d.axmanager.supported || !d.axmanager.installed)
    ? "Disarankan pada Android 8+ di profil utama. Aplikasi dan server: 3.0.0+." : "";
  return `<section id="access-setup" tabindex="-1" class="panel access-panel" data-access-method="${d.access.method}" aria-busy="${state.busy}" aria-labelledby="access-title">
    <div class="panel-heading"><h2 id="access-title">Akses game</h2>${icon("shield")}</div>
    <div class="access-methods" role="group" tabindex="-1" aria-label="Metode akses">${methods}</div>
    <div class="access-details">
      ${statusBadge(access.label, access.ready ? "good" : checked ? "warning" : d.checks.access.status === "error" ? "danger" : "neutral")}
      ${access.detail ? `<p class="muted">${escapeHtml(access.detail)}</p>` : ""}
      ${axRequirements ? `<p class="muted">${axRequirements}</p>` : ""}
      <div class="actions">
        ${actions}
        ${button("check-access", `${icon("refresh")} Periksa akses`, state.busy, actions ? "text-button" : "btn btn-secondary")}
      </div>
    </div>
  </section>`;
}

export function renderProgress(label: string, progress: Progress | null): string {
  return `<div class="progress-card">
    <div class="progress-line">${icon("refresh")}<strong role="status">${escapeHtml(progress?.label ?? label)}</strong>${progress ? `<span class="progress-percent" aria-hidden="true">${progress.percent}%</span>` : ""}</div>
    ${progress ? `<div class="progress-track" role="progressbar" aria-label="Progres operasi berkas" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress.percent}"><span class="progress-fill" style="transform: scaleX(${progress.percent / 100})"></span></div>` : ""}</div>`;
}

export function renderCustomize(state: UiState): string {
  const d = state.dashboard;
  const heading = `<header class="page-heading"><h1>Kustomisasi UID</h1><p>Ubah teks yang tampil di game, bukan UID akun.</p></header>`;
  if (d.checks.bootstrap.status !== "ready")
    return `${heading}<p class="muted">${escapeHtml(checkLabel(d.checks.bootstrap))}</p>`;
  if (!d.uidAvailable)
    return `${heading}<section class="panel unavailable-studio"><h2>Perlu Android</h2><p class="muted">Atur UID dari aplikasi Android.</p><button type="button" class="text-button" data-section="home">Kembali ke Beranda ${icon("arrow")}</button></section>`;

  const status = readiness(d);
  const canCustomize = !state.busy && status.ready;
  const draft = state.uidDraft.trim();
  const checked = d.checks.storage.status === "ready";
  const original = checked ? (d.uid.original || "UID asli game") : checkLabel(d.checks.storage);
  const current = checked ? (d.uid.custom ?? original) : original;
  const canApply = canCustomize && draft.length > 0 && draft.length <= UID_MAX_LENGTH &&
    draft !== (d.uid.custom ?? d.uid.original);
  const next = !d.game.installed ? button("setup-game", "Pasang game", state.busy)
    : !accessReadiness(d).ready ? button("access-setup", "Atur akses game", state.busy)
      : !checked ? button("refresh", "Periksa file game", state.busy)
        : d.patch.legacyPending || d.patch.recoveryPending || (d.patch.installed && !d.patch.healthy)
          ? button("tools", "Buka Perawatan", state.busy)
          : d.checks.release.status === "error" ? button("release", "Periksa rilis", state.busy) : "";

  return `${heading}
    ${!status.ready ? `<div class="uid-notice">
      ${icon("info")}<div><strong>${escapeHtml(status.label)}</strong>${status.detail ? `<p class="muted uid-gate">${escapeHtml(status.detail)}</p>` : ""}</div>${next}
    </div>` : ""}
    <div class="uid-grid">
      <section class="uid-ticket" aria-labelledby="uid-title">
        <div class="panel-heading"><h2 id="uid-title">Tampilan UID</h2>${icon("id")}</div>
        <dl class="uid-info">
          <div><dt>Aktif di game</dt><dd id="uid-active">${escapeHtml(current)}</dd></div>
          <div><dt>Bawaan</dt><dd>${escapeHtml(original)}</dd></div>
        </dl>
      </section>
      <section class="panel uid-editor" aria-labelledby="uid-edit-title">
        <div class="panel-heading"><h2 id="uid-edit-title">Edit tampilan</h2></div>
        <form id="uid-form">
          <label class="field-label" for="uid-input">Teks pengganti</label>
          <input class="uid-input" id="uid-input" name="customUid" type="text" maxlength="${UID_MAX_LENGTH}" value="${escapeHtml(state.uidDraft)}" placeholder="Rover Indonesia" autocomplete="off" spellcheck="false" aria-describedby="uid-help" ${state.busy ? "disabled" : ""} />
          <div class="uid-input-meta"><span id="uid-help">Maksimal ${UID_MAX_LENGTH} karakter</span><span id="uid-count">${state.uidDraft.length}/${UID_MAX_LENGTH}</span></div>
          <div class="actions"><button class="btn btn-primary" id="apply-uid" type="submit" ${canApply ? "" : "disabled"}>${icon("check")} Terapkan UID</button>${button("restore-uid", "Pulihkan bawaan", !canCustomize || d.uid.custom === null)}</div>
        </form>
      </section>
    </div>`;
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
        ? "Pulihkan transaksi yang tertunda."
        : d.patch.legacyPending ? "Migrasikan berkas terverifikasi tanpa unduhan." : "Periksa dan pulihkan paket terjemahan.",
      actions: button("repair", d.patch.legacyPending && !d.patch.recoveryPending ? "Migrasikan" : "Perbaiki", !canRepair),
      icon: "shield",
    },
    {
      name: "Diagnostik",
      detail: "Ekspor laporan JSON dengan UID disamarkan.",
      actions: button("save-diagnostics", "Simpan", state.busy) + button("share-diagnostics", "Bagikan", state.busy),
      icon: "download",
    },
    {
      name: "Hapus terjemahan",
      detail: "Hanya menghapus berkas yang dikelola WuwaID Mobile.",
      actions: button("uninstall", "Hapus", state.busy || !d.patch.installed || !canUninstall, "btn btn-danger"),
      icon: "trash",
    },
  ].map((row) =>
    `<div class="tool-row ${row.icon === "trash" ? "tool-destructive" : ""}"><span class="tool-icon">${icon(row.icon)}</span><div class="tool-copy"><h3>${row.name}</h3>${row.detail ? `<p class="muted">${row.detail}</p>` : ""}</div><div class="actions">${row.actions}</div></div>`,
  ).join("");
  const access = accessReadiness(d);
  const checkedStorage = d.checks.storage.status === "ready";
  const statusAction = d.checks.bootstrap.status !== "ready" || !d.platformSupported ? ""
    : !d.game.installed ? button("setup-game", "Pasang game", state.busy)
      : !access.ready ? button("access-setup", "Atur akses game", state.busy)
        : button("refresh", `${icon("refresh")} Periksa file game`, state.busy);
  return `<header class="page-heading"><h1>Perawatan</h1><p>Kelola pemasangan, laporan, dan pembaruan.</p></header>
    <section class="health-banner" aria-labelledby="health-title">${icon(status.ready ? "check" : "info")}
      <div><h2 id="health-title">${escapeHtml(status.label)}</h2>${status.detail ? `<p class="muted">${escapeHtml(status.detail)}</p>` : ""}</div>
      ${statusAction}
    </section>
    <div class="tools-grid">
      <section class="panel maintenance-panel" aria-labelledby="maintenance-title">
        <div class="panel-heading"><h2 id="maintenance-title">Pengelolaan terjemahan</h2></div>
        <div class="tool-list">${rows}</div>
      </section>
      <div class="tools-aside">
        <details class="panel technical-details">
          <summary id="technical-summary"><span>${icon("info")} Detail teknis</span>${icon("chevron")}</summary>
          <dl class="info-list">
            <div class="info-row"><dt>Game</dt><dd>${d.checks.bootstrap.status !== "ready" ? checkLabel(d.checks.bootstrap) : d.game.installed ? escapeHtml(d.game.version || "Versi tidak diketahui") : "Belum terpasang"}${d.game.packageName ? `<code>${escapeHtml(d.game.packageName)}</code>` : ""}</dd></div>
            <div class="info-row"><dt>Shizuku</dt><dd>${d.checks.access.status !== "ready" ? checkLabel(d.checks.access) : !d.shizuku.installed ? "Belum terpasang" : `${d.shizuku.running ? "Aktif" : "Tidak aktif"} · ${d.shizuku.granted ? "Diizinkan" : "Belum diizinkan"}`}</dd></div>
            <div class="info-row"><dt>AxManager</dt><dd>${d.checks.access.status !== "ready" ? checkLabel(d.checks.access) : !d.axmanager.installed ? "Tidak tersedia / AX-Stealth" : `${escapeHtml(d.axmanager.version)} · ${!d.axmanager.supported ? "Belum didukung" : d.access.method !== "axmanager" ? "Tidak dipilih" : d.axmanager.running ? "Akses server tersedia" : "Belum terhubung"}`}</dd></div>
            <div class="info-row"><dt>Akses terpilih</dt><dd>${d.checks.bootstrap.status !== "ready" ? checkLabel(d.checks.bootstrap) : `${ACCESS_LABELS[d.access.method]} · ${access.ready ? "Terhubung" : "Belum terhubung"}`}</dd></div>
            <div class="info-row"><dt>File game</dt><dd>${!checkedStorage ? checkLabel(d.checks.storage) : d.resource.ready ? `Siap · ${escapeHtml(d.resource.version)}` : "Belum siap"}</dd></div>
            <div class="info-row"><dt>Terjemahan</dt><dd>${!checkedStorage ? checkLabel(d.checks.storage)
              : d.patch.recoveryPending ? "Pemulihan tertunda"
                : d.patch.legacyPending ? "Versi lama"
                  : d.patch.installed ? `${escapeHtml(d.patch.version)} · ${d.patch.healthy ? "Terpasang" : "Perlu perbaikan"}` : "Belum terpasang"}</dd></div>
            <div class="info-row"><dt>Rilis terbaru</dt><dd>${escapeHtml(d.release?.tag ?? checkLabel(d.checks.release))}</dd></div>
            ${d.release ? `<div class="info-row"><dt>Untuk versi game</dt><dd>${escapeHtml(`${d.release.gameVersion.split(".").slice(0, 2).join(".")}.x`)}</dd></div>` : ""}
            <div class="info-row"><dt>Diperiksa</dt><dd>${d.lastChecked ? escapeHtml(new Date(d.lastChecked).toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" })) : "—"}</dd></div>
            ${d.uidAvailable && checkedStorage ? `<div class="info-row"><dt>Tampilan UID</dt><dd>${escapeHtml(d.uid.custom ?? d.uid.original)}</dd></div>` : ""}
            ${d.patch.message ? `<div class="info-row"><dt>Pemasangan</dt><dd>${escapeHtml(d.patch.message)}</dd></div>` : ""}
          </dl>
        </details>
        ${d.mode === "mock" ? `<section class="panel scenario-panel"><label class="field-label" for="scenario-select">Skenario simulasi</label><select class="select" id="scenario-select"${state.busy ? " disabled" : ""}>${scenarioOptions}</select></section>` : ""}
      </div>
    </div>`;
}
