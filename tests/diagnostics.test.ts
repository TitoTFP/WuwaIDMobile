// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { DiagnosticHistory, mockDiagnostics, serializeDiagnostics } from "../src/diagnostics";
import type { Dashboard } from "../src/model";
import { loadCheck } from "../src/model";

function dashboard(): Dashboard {
  return {
    mode: "mock", platformSupported: true, storageWritable: true, blocker: null, release: null,
    checks: { bootstrap: loadCheck("ready"), access: loadCheck("ready"), release: loadCheck("ready"), storage: loadCheck("ready") },
    uidAvailable: true, game: { installed: true, version: "3.7.0", packageName: "game.fixture" },
    resource: { ready: true, version: "3.7.0" }, shizuku: { installed: true, running: true, granted: true },
    axmanager: { installed: false, supported: false, running: false, version: "" },
    access: { method: "shizuku", granted: true },
    patch: { installed: true, healthy: true, recoveryPending: false, legacyPending: false, version: "fixture" },
    uid: { original: "123456789", custom: "Rover[private].✨" }, lastChecked: 0,
  };
}

describe("diagnostic privacy and history", () => {
  it("retains exactly the most recent twenty failures with already-redacted historical UIDs", () => {
    const history = new DiagnosticHistory();
    for (let i = 0; i < 25; i++) history.record(`operation-${i}`, new Error(`failure ${i}: old-UID-${i}`), [`old-UID-${i}`]);
    expect(history.snapshot().map(entry => [entry.operation, entry.message])).toEqual(
      Array.from({ length: 20 }, (_, i) => [`operation-${i + 5}`, `failure ${i + 5}: [REDACTED]`]),
    );
    const snapshot = history.snapshot();
    snapshot[0].message = "consumer changed snapshot";
    expect(history.snapshot()[0].message).toBe("failure 5: [REDACTED]");
  });

  it("redacts literal, overlapping UID values before limiting error size", () => {
    const history = new DiagnosticHistory();
    history.record("apply_uid", "abc123 abc .[*]✨", ["abc", "abc123", ".[*]✨", ""]);
    expect(history.snapshot()[0].message).toBe("[REDACTED] [REDACTED] [REDACTED]");
    history.record("apply_uid", "secret".repeat(1000), ["secret"]);
    expect(history.snapshot()[1].message.length).toBeLessThanOrEqual(1024);
    expect(history.snapshot()[1].message).not.toContain("secret");
  });

  it("exports customized presence but not UID text, including report and history errors", () => {
    const d = dashboard();
    const secret = d.uid.custom!;
    d.patch.message = `invalid mount for ${secret}`;
    d.blocker = `storage denied for ${secret}`;
    const history = new DiagnosticHistory();
    history.record("repair", `repair failed for ${secret}`);
    const source = mockDiagnostics(d);
    const output = serializeDiagnostics(source, history, [secret]);
    const report = JSON.parse(output.content);
    expect(output.content).not.toContain(secret);
    expect(report.sections.patch.data.uidCustomized).toBe(true);
    expect(report.sections.patch.data.message).toBe("invalid mount for [REDACTED]");
    expect(report.sections.probe.data.storageError).toBe("storage denied for [REDACTED]");
    expect(report.errors[0].message).toBe("repair failed for [REDACTED]");
    expect(source.sections.patch.data!.message).toBe(`invalid mount for ${secret}`);
  });

  it("does not damage protocol statuses when the custom UID itself is an enum value", () => {
    const d = dashboard();
    d.uid.custom = "ok";
    d.patch.message = "UID ok failed";
    const report = JSON.parse(serializeDiagnostics(mockDiagnostics(d), new DiagnosticHistory(), ["ok"]).content);
    expect(report.sections.patch.status).toBe("ok");
    expect(report.sections.patch.data.message).toBe("UID [REDACTED] failed");
    expect(report.privacy.customUid).toBe("redacted");
  });

  it("reports unavailable access as not checked rather than an absent installation", () => {
    const d = dashboard();
    d.access.granted = false;
    const report = JSON.parse(serializeDiagnostics(mockDiagnostics(d), new DiagnosticHistory()).content);
    expect(report.sections.probe.data.accessGranted).toBe(false);
    for (const name of ["resource", "patch"]) {
      expect(report.sections[name].status).toBe("not_checked");
      expect(report.sections[name].data).toBeNull();
    }
  });

  it("preserves a readable probe when resource collection fails", () => {
    const d = dashboard();
    d.resource.ready = false;
    const report = mockDiagnostics(d);
    expect(report.sections.probe.status).toBe("ok");
    expect(report.sections.resource.status).toBe("error");
    expect(report.sections.patch.status).toBe("not_checked");
    expect(report.sections.patch.data).toBeNull();
  });
});

describe("repeated diagnostic redaction", () => {
  it("keeps redaction markers stable and bounds messages when a UID becomes known later", () => {
    const history = new DiagnosticHistory();
    history.record("repair", "UID RE failed", ["RE"]);
    const d = dashboard();
    d.uid.custom = "RE";
    const report = JSON.parse(serializeDiagnostics(mockDiagnostics(d), history, ["RE"]).content);
    expect(report.errors[0].message).toBe("UID [REDACTED] failed");
    history.record("probe", "a".repeat(1024));
    const later = JSON.parse(serializeDiagnostics(mockDiagnostics(d), history, ["a"]).content);
    expect(later.errors[1].message.length).toBeLessThanOrEqual(1024);
    expect(later.errors[1].message).not.toContain("a");
  });

  it("redacts a complete UID beginning with the redaction marker without damaging existing markers", () => {
    const secret = "[REDACTED]PRIVATE";
    const history = new DiagnosticHistory();
    history.record("apply_uid", `UID ${secret} failed; already [REDACTED]`, [secret, "RE"]);
    expect(history.snapshot()[0].message).toBe("UID [REDACTED] failed; already [REDACTED]");
    const d = dashboard();
    d.uid.custom = secret;
    d.patch.message = `UID ${secret} failed`;
    const report = JSON.parse(serializeDiagnostics(mockDiagnostics(d), history, [secret, "RE"]).content);
    expect(report.sections.patch.data.message).toBe("UID [REDACTED] failed");
    expect(report.errors[0].message).toBe("UID [REDACTED] failed; already [REDACTED]");
  });

  it("redacts late-known UIDs independently across history entries and partial report fields", () => {
    const secret = "Rover.?🦋";
    const history = new DiagnosticHistory();
    history.record("probe", `${"prefix ".repeat(20)}${secret}`);
    history.record("resource", `${secret} failed`);
    const d = dashboard();
    d.resource.ready = false;
    d.blocker = `probe ${secret}`;
    const source = mockDiagnostics(d);
    source.sections.resource.error = `resource ${secret}`;
    source.sections.patch.reason = `inspect ${secret}`;
    const report = JSON.parse(serializeDiagnostics(source, history, [secret]).content);
    expect(report.errors.map((entry: { message: string }) => entry.message))
      .toEqual([`${"prefix ".repeat(20)}[REDACTED]`, "[REDACTED] failed"]);
    expect(report.sections.probe.data.storageError).toBe("probe [REDACTED]");
    expect(report.sections.resource.error).toBe("resource [REDACTED]");
    expect(report.sections.patch.reason).toBe("inspect [REDACTED]");
  });
});
