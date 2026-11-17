// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { compatibleGameVersion, installationPrerequisites, readiness } from "../src/model";
import type { Dashboard, UiState } from "../src/model";
import { renderCustomize, renderTools } from "../src/panels";

function dashboard(): Dashboard {
  return {
    mode: "native", platformSupported: true, storageWritable: true, blocker: null,
    release: { tag: "v3.7.1-id.1", gameVersion: "3.7.1", assetName: "translation.pak",
      downloadUrl: "https://github.com/TitoTFP/WuwaID/releases/download/v3.7.1-id.1/translation.pak",
      htmlUrl: "https://github.com/TitoTFP/WuwaID/releases/tag/v3.7.1-id.1", size: 100, sha256: "a".repeat(64) },
    uidAvailable: true, game: { installed: true, version: "3.7.0", packageName: "com.kurogame.wutheringwaves.global" },
    resource: { ready: true, version: "3.7.0" }, shizuku: { running: true, granted: true },
    axmanager: { installed: false, supported: false, running: false, version: "" },
    access: { method: "shizuku", granted: true },
    patch: { installed: false, healthy: false, recoveryPending: false, legacyPending: false, version: "" },
    uid: { original: "123456789", custom: null }, lastChecked: 0,
  };
}

function state(d = dashboard(), draft = "Rover Indonesia", busy = false): UiState {
  return { dashboard: d, uidDraft: draft, busy, progress: null };
}

function customize(s: UiState) { document.body.innerHTML = renderCustomize(s); }
function tools(s: UiState) { document.body.innerHTML = renderTools(s); }
function action(name: string): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>(`[data-action="${name}"]`)!;
}

describe("installation prerequisites", () => {
  it("accepts patch-level differences but rejects other version lines and malformed versions", () => {
    const d = dashboard();
    expect(installationPrerequisites(d)).toBe(true);
    for (const version of ["3.7.0", "3.7.10"]) expect(compatibleGameVersion(d, version)).toBe(true);
    for (const version of ["3.8.0", "4.7.0", "3.7", "3.7.0-beta", "3.7.x"])
      expect(compatibleGameVersion(d, version)).toBe(false);
    d.resource.version = "3.8.0";
    expect(installationPrerequisites(d)).toBe(false);
    expect(readiness(d).ready).toBe(false);
  });

  it("keeps mock compatibility separate from real release metadata", () => {
    const d = dashboard();
    d.mode = "mock";
    d.release = null;
    expect(installationPrerequisites(d)).toBe(true);
    expect(compatibleGameVersion(d, "3.7.1")).toBe(false);
    d.mode = "native";
    expect(installationPrerequisites(d)).toBe(false);
    expect(readiness(d).ready).toBe(false);
  });

  it("permits explicitly granted root without Shizuku and rejects the other provider's grant", () => {
    const d = dashboard();
    d.access = { method: "root", granted: true };
    d.shizuku = { running: false, granted: false };
    expect(installationPrerequisites(d)).toBe(true);
    expect(readiness(d).ready).toBe(true);
    d.access.granted = false;
    d.shizuku = { running: true, granted: true };
    expect(installationPrerequisites(d)).toBe(false);
    expect(readiness(d).ready).toBe(false);
    d.access.method = "shizuku";
    expect(installationPrerequisites(d)).toBe(false);
  });
  it("uses an explicit AxManager grant without Shizuku and rejects unavailable AxManager despite a stale grant", () => {
    const d = dashboard();
    d.access = { method: "axmanager", granted: true };
    d.axmanager = { installed: true, supported: true, running: true, version: "3.0.0" };
    d.shizuku = { running: false, granted: false };
    expect(installationPrerequisites(d)).toBe(true);
    expect(readiness(d).ready).toBe(true);
    for (const field of ["installed", "supported", "running"] as const) {
      d.axmanager[field] = false;
      expect(installationPrerequisites(d)).toBe(false);
      expect(readiness(d).ready).toBe(false);
      d.axmanager[field] = true;
    }
    d.access.granted = false;
    d.shizuku = { running: true, granted: true };
    expect(installationPrerequisites(d)).toBe(false);
    expect(readiness(d).ready).toBe(false);
  });


  it.each(["platform", "game", "resource", "write", "blocker"])("requires the %s prerequisite", (missing) => {
    const d = dashboard();
    if (missing === "platform") d.platformSupported = false;
    if (missing === "game") d.game.installed = false;
    if (missing === "resource") d.resource.ready = false;
    if (missing === "write") d.storageWritable = false;
    if (missing === "blocker") d.blocker = "Unsafe ownership";
    expect(installationPrerequisites(d)).toBe(false);
    expect(readiness(d).ready).toBe(false);
  });
});

describe("custom UID controls", () => {
  it("allows the first UID application without an existing translation", () => {
    customize(state());
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(false);
    expect(action("restore-uid").disabled).toBe(true);
  });

  it.each([
    ["", false], ["   ", false], [" 123456789 ", false],
    ["x".repeat(32), true], ["x".repeat(33), false],
    ["😀".repeat(16), true], ["😀".repeat(16) + "x", false],
  ])("gates draft %j using trimmed text and UTF-16 length", (draft, enabled) => {
    customize(state(dashboard(), draft));
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(!enabled);
  });

  it("disables redundant application but permits resetting an active custom UID", () => {
    const d = dashboard();
    d.patch.installed = true;
    d.patch.healthy = true;
    d.uid.custom = "Rover Indonesia";
    customize(state(d, " Rover Indonesia "));
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
    expect(action("restore-uid").disabled).toBe(false);
  });

  it.each(["busy", "recovery", "legacy", "unhealthy"])("blocks UID mutation during %s", (blocker) => {
    const d = dashboard();
    d.uid.custom = "Previous UID";
    if (blocker === "recovery") d.patch.recoveryPending = true;
    if (blocker === "legacy") d.patch.legacyPending = true;
    if (blocker === "unhealthy") d.patch.installed = true;
    customize(state(d, "New UID", blocker === "busy"));
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
    expect(action("restore-uid").disabled).toBe(true);
    expect(document.querySelector<HTMLInputElement>("#uid-input")!.disabled).toBe(blocker === "busy");
  });

  it("hides the UID form when the platform cannot apply it", () => {
    const d = dashboard();
    d.uidAvailable = false;
    customize(state(d));
    expect(document.querySelector("#uid-form")).toBeNull();
  });

  it("preserves hostile draft/status text without creating injected elements", () => {
    const d = dashboard();
    const payload = '\"><img src=x onerror="alert(1)">';
    d.uid.custom = payload;
    d.blocker = payload;
    customize(state(d, payload));
    expect(document.querySelector<HTMLInputElement>("#uid-input")!.value).toBe(payload);
    expect(document.querySelector("#uid-active")!.textContent).toBe(payload);
    expect(document.querySelector(".uid-gate")!.textContent).toBe(payload);
    expect(document.querySelector("img")).toBeNull();
  });
});

describe("maintenance transitions", () => {
  it.each(["legacy", "recovery"])("allows offline %s without enabling uninstall or UID mutation", (pending) => {
    const d = dashboard();
    d.release = null;
    d.blocker = "Offline";
    d.patch.installed = pending === "recovery";
    d.patch.recoveryPending = pending === "recovery";
    d.patch.legacyPending = pending === "legacy";
    tools(state(d));
    expect(action("repair").disabled).toBe(false);
    expect(action("uninstall").disabled).toBe(true);
    customize(state(d));
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
  });

  it("allows offline uninstall of an owned installation without offering download-based repair", () => {
    const d = dashboard();
    d.patch.installed = true;
    d.patch.healthy = true;
    d.release = null;
    d.blocker = "Offline";
    tools(state(d));
    expect(action("uninstall").disabled).toBe(false);
    expect(action("repair").disabled).toBe(true);
  });

  it.each(["write", "access", "resource", "busy"])("blocks recovery when %s is unavailable", (missing) => {
    const d = dashboard();
    d.patch.recoveryPending = true;
    if (missing === "write") d.storageWritable = false;
    if (missing === "access") d.access.granted = false;
    if (missing === "resource") d.resource.ready = false;
    tools(state(d, "draft", missing === "busy"));
    expect(action("repair").disabled).toBe(true);
  });

  it("locks permission/provider controls and maintenance mutations during an operation", () => {
    const d = dashboard();
    d.access = { method: "root", granted: false };
    d.patch.installed = true;
    tools(state(d, "draft", true));
    for (const name of ["request_root", "use_root", "use_shizuku", "use_axmanager", "refresh", "repair", "uninstall", "save-diagnostics", "share-diagnostics"])
      expect(action(name).disabled).toBe(true);
  });
  it.each([false, true])("offers setup and explicit alternatives without granting unavailable AxManager (installed=%s)", (installed) => {
    const d = dashboard();
    d.access = { method: "axmanager", granted: false };
    d.axmanager = { installed, supported: false, running: false, version: "" };
    tools(state(d));
    expect(action("setup-axmanager").disabled).toBe(false);
    expect(action("request_axmanager").disabled).toBe(true);
    expect(action("use_shizuku").disabled).toBe(false);
    expect(action("use_root").disabled).toBe(false);
    expect(action("repair").disabled).toBe(true);
    expect(action("uninstall").disabled).toBe(true);
    customize(state(d));
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
  });
  it("allows requesting a private AxManager capability before one is visible, but never enables mutations", () => {
    const d = dashboard();
    d.access = { method: "axmanager", granted: false };
    d.axmanager = { installed: true, supported: true, running: true, version: "3.0.0" };
    tools(state(d, "draft", true));
    for (const name of ["request_axmanager", "setup-axmanager", "use_root", "use_shizuku"])
      expect(action(name).disabled).toBe(true);
    tools(state(d));
    expect(action("request_axmanager").disabled).toBe(false);
    expect(action("use_axmanager").disabled).toBe(true);
    d.axmanager.running = false;
    tools(state(d));
    expect(action("request_axmanager").disabled).toBe(false);
    expect(action("setup-axmanager").disabled).toBe(false);
    customize(state(d));
    expect(document.querySelector<HTMLButtonElement>("#apply-uid")!.disabled).toBe(true);
    expect(d.access.method).toBe("axmanager");
    d.axmanager.supported = false;
    tools(state(d));
    expect(action("request_axmanager").disabled).toBe(true);
  });
});
