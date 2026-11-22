// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { MockInstance } from "vitest";
import { initialDashboard, loadCheck } from "../src/model";
import type { AccessSnapshot, BootstrapSnapshot, Dashboard, InspectionSnapshot, OperationResult, Progress, ReleaseInfo } from "../src/model";

const backend = vi.hoisted(() => ({
  bootstrap: vi.fn(), release: vi.fn(), inspect: vi.fn(),
  accessStatus: vi.fn(), run: vi.fn(), openSetup: vi.fn(),
  beginBusy: vi.fn(), endBusy: vi.fn(),
  progress: undefined as ((progress: Progress) => void) | undefined,
  updating: false,
  updaterChanged: undefined as (() => void) | undefined,
}));
vi.mock("../src/ipc", () => ({ api: {
  ...backend, mode: "native",
  subscribeProgress: (listener: (progress: Progress) => void) => { backend.progress = listener; },
} }));
vi.mock("../src/app-update", () => ({
  appUpdateBusy: () => backend.updating,
  handleAppUpdate: async () => false,
  initAppUpdates: async (changed: () => void) => { backend.updaterChanged = changed; },
  renderAppUpdate: () => "",
}));

let truth: Dashboard;
let windowEvents: MockInstance<Window["addEventListener"]>;
let documentEvents: MockInstance<Document["addEventListener"]>;

function bootstrap(): BootstrapSnapshot {
  return { mode: "native", platformSupported: true, uidAvailable: true,
    device: { game: { ...truth.game }, accessMethod: truth.access.method } };
}
function access(): AccessSnapshot {
  return { shizuku: { ...truth.shizuku }, axmanager: { ...truth.axmanager }, access: { ...truth.access } };
}
function inspection(): InspectionSnapshot {
  return { ...access(), game: { ...truth.game }, resource: { ...truth.resource },
    storageWritable: truth.storageWritable, blocker: truth.blocker, patch: { ...truth.patch },
    uid: { ...truth.uid }, lastChecked: Date.now(), inspection: "ready" };
}
function click(selector: string): void {
  const button = document.querySelector<HTMLButtonElement>(selector);
  expect(button).not.toBeNull();
  button!.click();
}
function go(section: "home" | "customize" | "tools"): void {
  click(`nav[aria-label="Navigasi utama"] [data-section="${section}"]`);
}
async function idle(): Promise<void> {
  await vi.waitFor(() => {
    expect(document.querySelector(".progress-card")).toBeNull();
    expect(document.querySelector("#access-setup")?.getAttribute("aria-busy")).toBe("false");
  });
}
function leave(): void { window.dispatchEvent(new Event("blur")); }
function back(): void {
  window.dispatchEvent(new Event("focus"));
  document.dispatchEvent(new Event("visibilitychange"));
}
async function mount(): Promise<void> {
  // Startup needs the test DOM and IPC before evaluating the entry point.
  await import("../src/main");
}

beforeEach(() => {
  vi.resetModules();
  vi.resetAllMocks();
  vi.useFakeTimers();
  windowEvents = vi.spyOn(window, "addEventListener");
  documentEvents = vi.spyOn(document, "addEventListener");
  backend.updating = false;
  backend.updaterChanged = undefined;
  backend.progress = undefined;
  truth = { ...initialDashboard("native"), platformSupported: true, storageWritable: true, uidAvailable: true,
    checks: { bootstrap: loadCheck("ready"), access: loadCheck("ready"), release: loadCheck("ready"), storage: loadCheck("ready") },
    game: { installed: true, version: "3.7.0", packageName: "game.fixture" },
    resource: { ready: true, version: "3.7.0" },
    shizuku: { installed: true, running: false, granted: false },
    axmanager: { installed: true, supported: true, running: true, version: "3.0.0" },
    access: { method: "axmanager", granted: true },
    release: { tag: "3.7.0-R2", gameVersion: "3.7.0", assetName: "translation.pak",
      downloadUrl: "https://github.com/TitoTFP/WuwaID/releases/download/3.7.0-R2/translation.pak",
      htmlUrl: "https://github.com/TitoTFP/WuwaID/releases/tag/3.7.0-R2", size: 100, sha256: "a".repeat(64) },
  };
  backend.bootstrap.mockImplementation(async () => bootstrap());
  backend.release.mockImplementation(async () => ({ ...truth.release! }));
  backend.accessStatus.mockImplementation(async () => access());
  backend.inspect.mockImplementation(async () => inspection());
  backend.beginBusy.mockResolvedValue(undefined);
  backend.endBusy.mockResolvedValue(undefined);
  backend.openSetup.mockResolvedValue(undefined);
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: () => {} }));
  vi.stubGlobal("scrollTo", () => {});
  Object.defineProperty(HTMLElement.prototype, "getAnimations", { configurable: true, value: () => [] });
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: () => {} });
  document.documentElement.dataset.themePreference = "system";
  document.body.innerHTML = `<div id="app"></div><div id="toast" hidden></div><dialog id="confirm-dialog"><p></p><button id="cancel-uninstall"></button><button id="confirm-uninstall"></button></dialog>`;
});

afterEach(() => {
  for (const args of windowEvents.mock.calls) window.removeEventListener(...args);
  for (const args of documentEvents.mock.calls) document.removeEventListener(...args);
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("renders navigation immediately and keeps release, bootstrap, and passive access independent", async () => {
  const local = Promise.withResolvers<BootstrapSnapshot>();
  const remote = Promise.withResolvers<ReleaseInfo>();
  const permission = Promise.withResolvers<AccessSnapshot>();
  backend.bootstrap.mockReturnValueOnce(local.promise);
  backend.release.mockReturnValueOnce(remote.promise);
  backend.accessStatus.mockReturnValueOnce(permission.promise);
  await mount();
  expect(document.querySelector('nav[aria-label="Navigasi utama"] [aria-current="page"]')?.textContent).toBe("Beranda");
  expect(document.querySelector('[data-action="refresh"]')).toBeNull();
  expect(document.querySelector('[data-action="setup-axmanager"]')).toBeNull();
  go("tools");
  expect(document.querySelector(".page-heading")?.textContent).toContain("Perawatan");
  go("home");
  expect(backend.inspect).not.toHaveBeenCalled();
  expect(backend.run).not.toHaveBeenCalled();
  local.resolve(bootstrap());
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(1));
  expect(document.querySelector<HTMLButtonElement>('.device-card [data-action="refresh"]')!.disabled).toBe(true);
  expect(document.querySelector('[data-action="setup-axmanager"]')).toBeNull();
  permission.resolve(access());
  await idle();
  expect(document.querySelector<HTMLButtonElement>('.package-actions [data-action="refresh"]')!.disabled).toBe(false);
  expect(backend.beginBusy).not.toHaveBeenCalled();
  expect(backend.inspect).not.toHaveBeenCalled();
  remote.reject(new Error("GitHub offline"));
  await vi.waitFor(() => expect(document.body.textContent).toContain("GitHub offline"));
  expect(document.querySelector<HTMLButtonElement>('[data-action="use_root"]')!.disabled).toBe(false);
  expect(backend.run).not.toHaveBeenCalled();
});

it("inspects only on demand, preserves edited UID drafts, and keeps offline removal available", async () => {
  truth.patch = { ...truth.patch, installed: true, healthy: true, version: "3.7.0-R2" };
  truth.uid.custom = "Existing";
  const remote = Promise.withResolvers<ReleaseInfo>();
  const scan = Promise.withResolvers<InspectionSnapshot>();
  backend.release.mockReturnValueOnce(remote.promise);
  backend.inspect.mockReturnValueOnce(scan.promise);
  await mount();
  await idle();
  expect(backend.inspect).not.toHaveBeenCalled();
  go("customize");
  await vi.waitFor(() => expect(backend.inspect).toHaveBeenCalledTimes(1));
  go("home");
  scan.resolve(inspection());
  remote.reject(new Error("Release unavailable"));
  await idle();
  go("tools");
  expect(document.querySelector<HTMLButtonElement>('[data-action="uninstall"]')!.disabled).toBe(false);
  expect(document.querySelector<HTMLButtonElement>('[data-action="repair"]')!.disabled).toBe(true);
  expect(backend.inspect).toHaveBeenCalledTimes(1);
  go("customize");
  const input = document.querySelector<HTMLInputElement>("#uid-input")!;
  input.value = "My draft";
  input.dispatchEvent(new Event("input", { bubbles: true }));
  go("home");
  const rescan = Promise.withResolvers<InspectionSnapshot>();
  backend.inspect.mockReturnValueOnce(rescan.promise);
  click('[aria-label="Periksa ulang perangkat"]');
  await vi.waitFor(() => expect(backend.inspect).toHaveBeenCalledTimes(2));
  go("customize");
  truth.uid.custom = "Changed outside";
  rescan.resolve(inspection());
  await vi.waitFor(() => expect(document.querySelector<HTMLInputElement>("#uid-input")?.disabled).toBe(false));
  expect(document.querySelector<HTMLInputElement>("#uid-input")!.value).toBe("My draft");
  expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
  expect(backend.release).toHaveBeenCalledTimes(1);
});

it("keeps failed inspections unknown, blocks stale mutations, and retries only explicitly", async () => {
  truth.patch = { ...truth.patch, installed: true, healthy: true, version: "3.7.0-R2" };
  truth.uid.custom = "Checked UID";
  truth.access = { method: "shizuku", granted: true };
  truth.shizuku = { installed: true, running: true, granted: true };
  await mount();
  await idle();
  go("tools");
  await vi.waitFor(() => expect(document.querySelector<HTMLButtonElement>('[data-action="uninstall"]')?.disabled).toBe(false));
  go("home");
  backend.inspect.mockRejectedValueOnce(new Error("Storage unavailable"));
  click('[aria-label="Periksa ulang perangkat"]');
  await vi.waitFor(() => expect(document.querySelector(".error-banner")?.textContent).toContain("Storage unavailable"));
  await idle();
  expect(document.querySelector('[aria-label="Metode akses"] + .access-details')?.textContent).toContain("Terhubung");
  const failedStep = document.querySelector('[aria-current="step"]')?.textContent;
  expect(failedStep).toContain("File game");
  expect(failedStep).toContain("Gagal diperiksa");
  go("tools");
  expect(document.querySelector<HTMLButtonElement>('[data-action="uninstall"]')!.disabled).toBe(true);
  expect(document.querySelector<HTMLButtonElement>('[data-action="repair"]')!.disabled).toBe(true);
  go("customize");
  expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
  expect(document.querySelector("#uid-active")!.textContent).not.toContain("Checked UID");
  expect(document.querySelector<HTMLInputElement>("#uid-input")!.value).toBe("Checked UID");
  expect(backend.inspect).toHaveBeenCalledTimes(2);
  click('[data-action="refresh"]');
  await vi.waitFor(() => expect(document.querySelector<HTMLButtonElement>('[data-action="refresh"]')).toBeNull());
  expect(backend.inspect).toHaveBeenCalledTimes(3);
  go("tools");
  expect(document.querySelector<HTMLButtonElement>('[data-action="uninstall"]')!.disabled).toBe(false);
  expect(backend.run).not.toHaveBeenCalled();
});

it("coalesces navigation during busy tracing and timestamps only completed storage inspections", async () => {
  const remote = Promise.withResolvers<ReleaseInfo>();
  backend.release.mockReturnValueOnce(remote.promise);
  await mount();
  await idle();
  const trace = Promise.withResolvers<void>();
  const scan = Promise.withResolvers<InspectionSnapshot>();
  backend.beginBusy.mockReturnValueOnce(trace.promise);
  backend.inspect.mockReturnValueOnce(scan.promise);
  go("tools");
  const inspectedRow = Array.from(document.querySelectorAll(".technical-details .info-row"))
    .find((row) => row.querySelector("dt")?.textContent === "Diperiksa")!;
  expect(inspectedRow.querySelector("dd")!.textContent).toBe("—");
  go("customize");
  trace.resolve();
  await vi.waitFor(() => expect(backend.inspect).toHaveBeenCalledTimes(1));
  scan.resolve(inspection());
  await vi.waitFor(() => expect(document.querySelector<HTMLInputElement>("#uid-input")?.disabled).toBe(false));
  expect(backend.inspect).toHaveBeenCalledTimes(1);
  go("home");
  expect(document.querySelector<HTMLButtonElement>('.package-actions [data-action="release"]')!.disabled).toBe(true);
  remote.resolve({ ...truth.release! });
  await vi.waitFor(() => expect(document.querySelector<HTMLButtonElement>('.package-actions [data-action="install"]')?.disabled).toBe(false));
  go("tools");
  const checkedAt = Array.from(document.querySelectorAll(".technical-details .info-row"))
    .find((row) => row.querySelector("dt")?.textContent === "Diperiksa")!.querySelector("dd")!.textContent;
  go("home");
  click('[data-action="setup-axmanager"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(1));
  await idle();
  leave();
  vi.setSystemTime(Date.now() + 60_000);
  back();
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(2));
  await idle();
  go("tools");
  expect(Array.from(document.querySelectorAll(".technical-details .info-row"))
    .find((row) => row.querySelector("dt")?.textContent === "Diperiksa")!.querySelector("dd")!.textContent).toBe(checkedAt);
  expect(backend.inspect).toHaveBeenCalledTimes(1);
});

it("coalesces passive returns behind operations and updates without scanning or requesting permission", async () => {
  truth.access = { method: "shizuku", granted: false };
  truth.axmanager.installed = false;
  await mount();
  await idle();
  expect(backend.accessStatus).toHaveBeenCalledTimes(1);
  leave(); back();
  expect(backend.accessStatus).toHaveBeenCalledTimes(1);
  const launch = Promise.withResolvers<void>();
  const trace = Promise.withResolvers<void>();
  backend.openSetup.mockReturnValueOnce(launch.promise);
  backend.endBusy.mockReturnValueOnce(trace.promise);
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(1));
  backend.progress?.({ percent: 58, label: "File progress" });
  expect(document.querySelector('.progress-card [role="progressbar"]')).toBeNull();
  leave(); back(); back();
  expect(backend.accessStatus).toHaveBeenCalledTimes(1);
  truth.shizuku.running = true;
  launch.resolve();
  await vi.waitFor(() => expect(backend.endBusy).toHaveBeenCalledTimes(1));
  expect(backend.accessStatus).toHaveBeenCalledTimes(1);
  trace.resolve();
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(2));
  await idle();
  expect(document.querySelector<HTMLButtonElement>('[data-action="request_permission"]')!.disabled).toBe(false);
  expect(backend.run).not.toHaveBeenCalled();
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(2));
  await idle();
  leave();
  const permission = Promise.withResolvers<OperationResult>();
  backend.run.mockReturnValueOnce(permission.promise);
  click('[data-action="request_permission"]');
  await vi.waitFor(() => expect(backend.run).toHaveBeenCalledTimes(1));
  back();
  expect(backend.accessStatus).toHaveBeenCalledTimes(2);
  truth.shizuku.granted = true;
  truth.access.granted = true;
  permission.resolve({ kind: "inspection", snapshot: inspection(), release: null });
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(3));
  await idle();
  expect(backend.run).toHaveBeenCalledExactlyOnceWith("request_permission", undefined);
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(3));
  await idle();
  leave();
  backend.updating = true;
  back();
  expect(backend.accessStatus).toHaveBeenCalledTimes(3);
  backend.updating = false;
  backend.updaterChanged?.();
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(4));
  await idle();
  backend.accessStatus.mockRejectedValueOnce(new Error("Status unavailable"));
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(4));
  await idle();
  leave(); back();
  await vi.waitFor(() => expect(document.body.textContent).toContain("Status unavailable"));
  back();
  expect(backend.accessStatus).toHaveBeenCalledTimes(5);
  click('#access-setup [data-action="check-access"]');
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(6));
  await idle();
  expect(backend.inspect).not.toHaveBeenCalled();
  expect(backend.run).toHaveBeenCalledTimes(1);
});

it("retries bootstrap independently and keeps provider switches silent and guarded", async () => {
  backend.bootstrap.mockRejectedValueOnce(new Error("Package metadata unavailable"));
  await mount();
  await vi.waitFor(() => expect(document.body.textContent).toContain("Package metadata unavailable"));
  expect(backend.accessStatus).not.toHaveBeenCalled();
  click('[data-action="bootstrap"]');
  await idle();
  const selection = Promise.withResolvers<OperationResult>();
  backend.run.mockReturnValueOnce(selection.promise);
  document.querySelector<HTMLButtonElement>('[data-action="use_root"]')!.focus();
  click('[data-action="use_root"]');
  await vi.waitFor(() => expect(backend.run).toHaveBeenCalledTimes(1));
  expect(document.querySelector(".progress-card")).toBeNull();
  expect(document.querySelector("#access-setup")?.getAttribute("aria-busy")).toBe("true");
  expect(document.activeElement?.classList.contains("access-methods")).toBe(true);
  for (const action of ["use_root", "use_shizuku", "use_axmanager", "check-access"])
    expect(document.querySelector<HTMLButtonElement>(`#access-setup [data-action="${action}"]`)!.disabled).toBe(true);
  click('[data-action="use_axmanager"]');
  expect(backend.run).toHaveBeenCalledTimes(1);
  truth.access = { method: "root", granted: false };
  selection.resolve({ kind: "access", snapshot: access() });
  await idle();
  expect(document.querySelector('[data-action="use_root"]')?.getAttribute("aria-pressed")).toBe("true");
  expect(document.activeElement?.classList.contains("access-methods")).toBe(true);
  expect(document.querySelector<HTMLElement>("#toast")!.hidden).toBe(true);
  backend.run.mockRejectedValueOnce(new Error("Switch failed"));
  click('[data-action="use_axmanager"]');
  await vi.waitFor(() => expect(document.querySelector(".error-banner")?.textContent).toContain("Switch failed"));
  await idle();
  expect(backend.accessStatus).toHaveBeenCalledTimes(2);
  expect(document.querySelector('[data-action="use_root"]')?.getAttribute("aria-pressed")).toBe("true");
  go("tools");
  expect(document.querySelector("#access-setup")).toBeNull();
  click('[data-action="access-setup"]');
  await vi.waitFor(() => expect(document.activeElement?.id).toBe("access-setup"));
  expect(backend.inspect).not.toHaveBeenCalled();
});
