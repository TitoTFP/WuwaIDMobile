import { api } from "./ipc";
import type { AppUpdateStatus, Progress } from "./model";
import { escapeHtml as esc, icon } from "./ui";

let status: AppUpdateStatus | undefined;
let pending: "check" | "download" | "install" | undefined;
let progress: Progress | null = null;
let failure = "";
let changed: () => void;
let initialized = false;

// A background version check must not delay access to game storage.
export function appUpdateBusy(): boolean {
  return pending === "download" || pending === "install";
}

function showProgress(value: Progress) {
  progress = value;
  const card = document.querySelector<HTMLElement>("#app-update-progress");
  if (!card) return;
  const label = card.querySelector("strong")!;
  if (label.textContent !== value.label) label.textContent = value.label;
  card.querySelector(".progress-percent")!.textContent = `${value.percent}%`;
  const track = card.querySelector<HTMLElement>(".progress-track")!;
  track.setAttribute("aria-valuenow", String(value.percent));
  track.querySelector<HTMLElement>(".progress-fill")!.style.transform = `scaleX(${value.percent / 100})`;
}

async function run(action: "check" | "download" | "install") {
  if (pending) return;
  pending = action;
  failure = "";
  progress = action === "check" ? null : { percent: 0, label: "Menyiapkan pembaruan…" };
  changed();
  try {
    status = await api.appUpdate(action, showProgress);
  } catch (cause) {
    failure = cause instanceof Error ? cause.message : String(cause);
    if (action !== "check") {
      try {
        status = await api.appUpdate("status");
      } catch (refreshError) {
        failure += ` Gagal memuat ulang status: ${String(refreshError)}`;
      }
    }
  } finally {
    pending = undefined;
    progress = null;
    changed();
  }
}

export async function initAppUpdates(render: () => void) {
  changed = render;
  if (initialized) return;
  initialized = true;
  try {
    status = await api.appUpdate("status");
    changed();
    if (status.supported) await run("check");
  } catch (cause) {
    failure = cause instanceof Error ? cause.message : String(cause);
    changed();
  }
}

export async function handleAppUpdate(action: string | undefined, gameBusy: boolean): Promise<boolean> {
  const operation = action === "app-update-check" ? "check"
    : action === "app-update-download" ? "download"
      : action === "app-update-install" ? "install" : undefined;
  if (!operation) return false;
  if (!gameBusy && !pending) await run(operation);
  return true;
}

export function renderAppUpdate(gameBusy: boolean, details: boolean): string {
  if (!details && !status?.availableVersion && !appUpdateBusy()) return "";
  const busy = gameBusy || !!pending;
  const version = status?.availableVersion;
  const supported = status?.supported !== false;
  const size = status?.size ? `${(status.size / (1024 * 1024)).toFixed(1)} MiB` : "";
  const message = pending === "check" ? "Memeriksa pembaruan…"
    : status?.message || (version ? "" : status?.checked ? "Aplikasi sudah versi terbaru." : "Belum diperiksa.");
  return `<section id="app-update" class="panel app-update-card" aria-labelledby="app-update-title">
    <div class="app-update-head">
      <span class="tool-icon">${icon("download")}</span>
      <div class="app-update-copy">
        <h2 id="app-update-title">Pembaruan aplikasi</h2>
        <div role="status">
          ${status?.currentVersion ? `<p class="muted">Terpasang ${esc(status.currentVersion)}${status.currentVersionCode !== null ? ` · kode ${status.currentVersionCode}` : ""}</p>` : ""}
          ${version ? `<p><strong>${esc(version)} tersedia${size ? ` · ${size}` : ""}</strong></p>` : ""}
          ${message ? `<p class="muted">${esc(message)}</p>` : ""}
        </div>
        ${failure ? `<p class="app-update-error" role="alert">${esc(failure)}</p>` : ""}
      </div>
      <div class="actions">
        <button type="button" class="btn btn-secondary" data-action="app-update-check" ${busy || !supported ? "disabled" : ""}>${icon("refresh")} Periksa pembaruan</button>
        ${supported && version ? `<button type="button" class="btn btn-primary" data-action="${status?.ready ? "app-update-install" : "app-update-download"}" ${busy ? "disabled" : ""}>${icon(status?.ready ? "external" : "download")} ${status?.ready ? "Pasang pembaruan" : "Unduh APK"}</button>` : ""}
      </div>
    </div>
    ${progress ? `<div class="progress-card" id="app-update-progress"><div class="progress-line">${icon("refresh")}<strong role="status">${esc(progress.label)}</strong><span class="progress-percent" aria-hidden="true">${progress.percent}%</span></div><div class="progress-track" role="progressbar" aria-label="Progres pembaruan aplikasi" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress.percent}"><span class="progress-fill" style="transform: scaleX(${progress.percent / 100})"></span></div></div>` : ""}
  </section>`;
}
