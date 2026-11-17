// @vitest-environment jsdom
import { afterAll, expect, it, vi } from "vitest";
import type { Dashboard, Progress } from "../src/model";

const backend = vi.hoisted(() => ({
  load: vi.fn(), accessStatus: vi.fn(), run: vi.fn(), openSetup: vi.fn(),
  beginBusy: vi.fn(), endBusy: vi.fn(),
  progress: undefined as ((progress: Progress) => void) | undefined,
  updating: false,
  updaterChanged: undefined as (() => void) | undefined,
}));
vi.mock("../src/ipc", () => ({ api: {
  ...backend,
  subscribeProgress: (listener: (progress: Progress) => void) => { backend.progress = listener; },
} }));
vi.mock("../src/app-update", () => ({
  appUpdateBusy: () => backend.updating,
  handleAppUpdate: async () => false,
  initAppUpdates: async (changed: () => void) => { backend.updaterChanged = changed; },
  renderAppUpdate: () => "",
}));
afterAll(() => { vi.unstubAllGlobals(); });

it("sets up access from Home and refreshes once on return, after outstanding operations finish", async () => {
  const d: Dashboard = {
    mode: "native", platformSupported: true, storageWritable: false, blocker: null,
    release: null, uidAvailable: true,
    game: { installed: true, version: "3.7.0", packageName: "game.fixture" },
    resource: { ready: false, version: "" },
    shizuku: { installed: true, running: false, granted: false },
    axmanager: { installed: false, supported: false, running: false, version: "" },
    access: { method: "shizuku", granted: false },
    patch: { installed: false, healthy: false, legacyPending: false, recoveryPending: false, version: "" },
    uid: { original: "", custom: null }, lastChecked: 0,
  };
  backend.load.mockImplementation(async () => structuredClone(d));
  backend.accessStatus.mockImplementation(async () => ({
    shizuku: { ...d.shizuku }, axmanager: { ...d.axmanager }, access: { ...d.access },
  }));
  backend.beginBusy.mockResolvedValue(undefined);
  backend.endBusy.mockResolvedValue(undefined);
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: () => {} }));
  vi.stubGlobal("scrollTo", () => {});
  Object.defineProperty(HTMLElement.prototype, "getAnimations", { configurable: true, value: () => [] });
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: () => {} });
  document.documentElement.dataset.themePreference = "system";
  document.body.innerHTML = `<div id="app"></div><div id="toast" hidden></div><dialog id="confirm-dialog"><p></p><button id="cancel-uninstall"></button><button id="confirm-uninstall"></button></dialog>`;
  const click = (selector: string) => document.querySelector<HTMLButtonElement>(selector)!.click();
  const settle = () => vi.waitFor(() => expect(document.querySelector(".progress-card")).toBeNull());
  const leave = () => window.dispatchEvent(new Event("blur"));
  const back = () => {
    window.dispatchEvent(new Event("focus"));
    document.dispatchEvent(new Event("visibilitychange"));
  };
  // Exercise startup after the test DOM and IPC are ready; a static import starts the UI too early.
  await import("../src/main");
  await vi.waitFor(() => expect(document.querySelector("#access-setup")).not.toBeNull());
  click('.package-actions [data-action="access-setup"]');
  await vi.waitFor(() => expect(document.activeElement?.id).toBe("access-setup"));
  expect(document.querySelectorAll("#access-setup")).toHaveLength(1);
  leave(); back();
  expect(backend.load).toHaveBeenCalledTimes(1); // Unrelated focus does not refresh.

  const launch = Promise.withResolvers<void>();
  const traceEnd = Promise.withResolvers<void>();
  backend.openSetup.mockReturnValueOnce(launch.promise);
  backend.endBusy.mockReturnValueOnce(traceEnd.promise);
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledWith("shizuku"));
  backend.progress?.({ percent: 58, label: "File phase must not replace setup feedback" });
  expect(document.querySelector('.progress-card [role="progressbar"]')).toBeNull();
  expect(document.querySelector(".progress-card")!.textContent).not.toContain("%");
  leave(); back(); back();
  expect(backend.load).toHaveBeenCalledTimes(1);
  expect(backend.accessStatus).not.toHaveBeenCalled();
  d.shizuku.running = true;
  launch.resolve();
  await vi.waitFor(() => expect(backend.endBusy).toHaveBeenCalledTimes(1));
  expect(backend.load).toHaveBeenCalledTimes(1);
  traceEnd.resolve();
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(1));
  await settle();
  expect(document.querySelector<HTMLButtonElement>('[data-action="request_permission"]')!.disabled).toBe(false);
  expect(backend.run).not.toHaveBeenCalled();
  back(); back();
  expect(backend.load).toHaveBeenCalledTimes(1);
  expect(backend.accessStatus).toHaveBeenCalledTimes(1);

  // A later return must wait for a user-started permission operation, not overlap it.
  backend.openSetup.mockResolvedValue(undefined);
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(2));
  await settle();
  leave();
  const permission = Promise.withResolvers<Dashboard>();
  backend.run.mockReturnValueOnce(permission.promise);
  click('[data-action="request_permission"]');
  await vi.waitFor(() => expect(backend.run).toHaveBeenCalledTimes(1));
  back();
  expect(backend.load).toHaveBeenCalledTimes(1);
  expect(backend.accessStatus).toHaveBeenCalledTimes(1);
  d.shizuku.granted = true;
  d.access.granted = true;
  permission.resolve(structuredClone(d));
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(2));
  await settle();
  expect(backend.run).toHaveBeenCalledExactlyOnceWith("request_permission", undefined);
  expect(document.querySelector('.access-card .status-badge.good')).not.toBeNull();

  click('.mobile-nav [data-section="tools"]');
  expect(document.querySelector("#access-setup")).toBeNull();
  click('.mobile-nav [data-section="home"]');
  expect(document.querySelectorAll("#access-setup")).toHaveLength(1);
  expect(document.querySelector('[data-action="setup-shizuku"]')).not.toBeNull();
  backend.openSetup.mockRejectedValueOnce(new Error("Manager could not open"));
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(document.querySelector(".error-banner")?.textContent).toContain("Manager could not open"));
  await settle();
  const loadsAfterFailure = backend.load.mock.calls.length;
  leave(); back();
  expect(backend.load).toHaveBeenCalledTimes(loadsAfterFailure);
  expect(backend.accessStatus).toHaveBeenCalledTimes(2);
  expect(document.querySelector('.access-methods [aria-pressed="true"]')?.getAttribute("data-action")).toBe("use_shizuku");

  backend.openSetup.mockResolvedValue(undefined);
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(4));
  await settle();
  leave();
  backend.updating = true;
  back();
  expect(backend.load).toHaveBeenCalledTimes(loadsAfterFailure);
  expect(backend.accessStatus).toHaveBeenCalledTimes(2);
  backend.updating = false;
  backend.updaterChanged?.();
  await vi.waitFor(() => expect(backend.accessStatus).toHaveBeenCalledTimes(3));
  await settle();
  expect(backend.run).toHaveBeenCalledTimes(1);
  expect(backend.load).toHaveBeenCalledTimes(1);

  backend.accessStatus.mockRejectedValueOnce(new Error("Status unavailable"));
  click('[data-action="setup-shizuku"]');
  await vi.waitFor(() => expect(backend.openSetup).toHaveBeenCalledTimes(5));
  await settle();
  leave(); back();
  await vi.waitFor(() => expect(document.querySelector(".error-banner")?.textContent).toContain("Status unavailable"));
  await settle();
  expect(backend.load).toHaveBeenCalledTimes(1); // A failed passive check must not fall back to a full scan/bind.
  back();
  expect(backend.accessStatus).toHaveBeenCalledTimes(4);

  // Provider selection stays local while the same busy guard blocks every privileged action.
  const selection = Promise.withResolvers<Dashboard>();
  backend.run.mockReturnValueOnce(selection.promise);
  document.querySelector<HTMLButtonElement>('[data-action="use_root"]')!.focus();
  click('[data-action="use_root"]');
  await vi.waitFor(() => expect(backend.run).toHaveBeenCalledTimes(2));
  expect(document.querySelector(".progress-card")).toBeNull();
  expect(document.querySelector("#access-setup")?.getAttribute("aria-busy")).toBe("true");
  expect(document.activeElement?.classList.contains("access-methods")).toBe(true);
  for (const action of ["use_root", "use_shizuku", "use_axmanager", "request_permission", "refresh"])
    expect(document.querySelector<HTMLButtonElement>(`#access-setup [data-action="${action}"]`)!.disabled).toBe(true);
  click('[data-action="use_axmanager"]');
  expect(backend.run).toHaveBeenCalledTimes(2);
  d.access = { method: "root", granted: false };
  selection.resolve(structuredClone(d));
  await vi.waitFor(() => expect(document.querySelector("#access-setup")?.getAttribute("aria-busy")).toBe("false"));
  expect(document.querySelector('[data-action="use_root"]')?.getAttribute("aria-pressed")).toBe("true");
  expect(document.activeElement?.classList.contains("access-methods")).toBe(true);
  expect(backend.run).toHaveBeenLastCalledWith("use_root", undefined);

  backend.run.mockRejectedValueOnce(new Error("Switch failed"));
  click('[data-action="use_axmanager"]');
  await vi.waitFor(() => expect(document.querySelector(".error-banner")?.textContent).toContain("Switch failed"));
  await vi.waitFor(() => expect(document.querySelector("#access-setup")?.getAttribute("aria-busy")).toBe("false"));
  expect(document.querySelector('[data-action="use_root"]')?.getAttribute("aria-pressed")).toBe("true");
  click('.mobile-nav [data-section="tools"]');
  expect(document.querySelector("#access-setup")).toBeNull();
  click('[data-action="access-setup"]');
  await vi.waitFor(() => expect(document.activeElement?.id).toBe("access-setup"));
  expect(document.querySelectorAll("#access-setup")).toHaveLength(1);
  expect(backend.run).toHaveBeenCalledTimes(3);
});
