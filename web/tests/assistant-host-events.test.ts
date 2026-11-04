import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  HOST_EVENT_NAMES,
  HOST_EVENT_SCHEMA_VERSION,
  acceptHostChannelMessage,
  createHostEventBridge,
  postHostChannelEvent,
  readHostChannelHello,
  validateHostEvent,
  type HostEventEnvelope,
} from "../shared/assistant-host-events";

function envelope(over: Record<string, unknown> = {}) {
  return {
    schemaVersion: "1.0",
    event: "ready",
    integrationId: "int_ops",
    appCode: "demo",
    traceId: "trace-1",
    occurredAt: "2026-11-04T00:00:00.000Z",
    ...over,
  };
}

function valid(over: Record<string, unknown> = {}) {
  const result = validateHostEvent(envelope(over));
  expect(result.ok).toBe(true);
  return (result as { ok: true; event: HostEventEnvelope }).event;
}

function reason(over: Record<string, unknown>) {
  const result = validateHostEvent(envelope(over));
  expect(result.ok).toBe(false);
  return (result as { ok: false; reason: string }).reason;
}

describe("host event envelope v1", () => {
  it("accepts all seven declared events within the fixed fields", () => {
    for (const event of HOST_EVENT_NAMES) {
      valid({
        event,
        ...(event === "result" ? { runId: "r_1", status: "SUCCEEDED" } : {}),
        ...(event === "error" ? { error: { code: "ASSISTANT_ERROR" } } : {}),
      });
    }
    expect(HOST_EVENT_NAMES).toHaveLength(7);
  });

  it("rejects unknown schemaVersion, unknown events and undeclared fields", () => {
    expect(reason({ schemaVersion: "1.1" })).toContain("未知 schemaVersion");
    expect(reason({ event: "assistant:ready" })).toContain("未声明的事件名");
    expect(reason({ extra: true })).toContain("未声明字段");
    expect(reason({ integrationId: "bad id" })).toContain("字符不合法");
    expect(reason({ occurredAt: "not-a-date" })).toContain("有效时间");
  });

  it("enforces the conditional shape of result and error events", () => {
    expect(reason({ event: "result" })).toContain("runId 与 status");
    expect(reason({ event: "result", runId: "r_1" })).toContain("runId 与 status");
    expect(reason({ event: "error" })).toContain("error 对象");
    valid({
      event: "result",
      runId: "r_1",
      status: "UNKNOWN",
      structured: { businessEffect: "UNKNOWN" },
    });
  });

  it("rejects oversized payloads and non-data values", () => {
    expect(
      reason({ structured: { answer: "x".repeat(9000) } }),
    ).toContain("超过大小上限");
    expect(reason({ structured: { fn: () => 1 } })).toContain("不可序列化");

    const tooMany = Object.fromEntries(
      Array.from({ length: 33 }, (_, i) => ["k" + i, i]),
    );
    expect(reason({ structured: tooMany })).toContain("字段数量超过上限");
  });

  it("rejects malformed references and errors", () => {
    expect(reason({ fileRefs: [{ artifactId: 0, name: "a" }] })).toContain(
      "artifactId",
    );
    expect(reason({ fileRefs: [{ artifactId: 1 }] })).toContain("name");
    expect(
      reason({ businessRecordRefs: [{ source: "s", recordId: "r", extra: 1 }] }),
    ).toContain("未声明字段");
    expect(reason({ error: { code: "X", retryable: "yes" } })).toContain(
      "retryable",
    );
  });

  it("keeps the checked-in JSON Schema in sync with the event enum", () => {
    const schema = JSON.parse(
      readFileSync(
        new URL("../shared/assistant-host-events.schema.json", import.meta.url),
        "utf8",
      ),
    );
    expect(schema.$id).toBe("urn:agent-platform:assistant-host-event:1.0");
    expect(schema.properties.schemaVersion.const).toBe(
      HOST_EVENT_SCHEMA_VERSION,
    );
    expect(schema.properties.event.enum).toEqual([...HOST_EVENT_NAMES]);
  });
});

describe("host event bridge", () => {
  const delivered: HostEventEnvelope[] = [];
  const bridge = () =>
    createHostEventBridge({
      appCode: "demo",
      integrationId: "int_ops",
      traceId: "trace-fixed",
      now: () => new Date("2026-11-04T00:00:00.000Z"),
      onEvent: (event) => delivered.push(event),
    });

  it("delivers each terminal result for a run exactly once", () => {
    delivered.length = 0;
    const b = bridge();
    expect(b.result({ runId: "r_1", status: "SUCCEEDED" })).not.toBeNull();
    expect(b.result({ runId: "r_1", status: "SUCCEEDED" })).toBeNull();
    expect(b.result({ runId: "r_1", status: "FAILED" })).toBeNull();
    expect(delivered.filter((e) => e.event === "result")).toHaveLength(1);
    expect(delivered[0].traceId).toBe("trace-fixed");
  });

  it("dedupes run-started and approval-required per run/node", () => {
    delivered.length = 0;
    const b = bridge();
    b.emit("run-started", { runId: "r_2" });
    b.emit("run-started", { runId: "r_2" });
    b.emit("approval-required", {
      runId: "r_2",
      status: "WAITING_APPROVAL",
      structured: { nodeId: "n1" },
    });
    b.emit("approval-required", {
      runId: "r_2",
      status: "WAITING_APPROVAL",
      structured: { nodeId: "n1" },
    });
    expect(delivered.map((e) => e.event)).toEqual([
      "run-started",
      "approval-required",
    ]);
  });

  it("ignores unknown events and isolates host callback failures", () => {
    const seen: string[] = [];
    const b = createHostEventBridge({
      appCode: "demo",
      onEvent: () => {
        throw new Error("host boom");
      },
    });
    b.subscribe((event) => {
      throw new Error("subscriber boom");
    });
    b.subscribe((event) => seen.push(event.event));
    expect(b.emit("unknown" as never)).toBeNull();
    expect(b.emit("ready")).not.toBeNull();
    expect(seen).toEqual(["ready"]);
    const unsubscribe = b.subscribe(() => seen.push("later"));
    unsubscribe();
    expect(seen).toEqual(["ready"]);
  });
});

describe("iframe channel boundary", () => {
  const good = envelope({
    event: "result",
    runId: "r_1",
    status: "SUCCEEDED",
  }) as HostEventEnvelope;

  it("only posts data envelopes to an exact origin", () => {
    const calls: Array<{ message: any; origin: string }> = [];
    const target = {
      postMessage(message: unknown, origin: string) {
        calls.push({ message, origin });
      },
    };
    const channel = { nonce: "nonce_12345678", instanceId: "inst_12345678" };
    expect(postHostChannelEvent(target, "*", good, channel).ok).toBe(false);
    expect(
      postHostChannelEvent(target, "https://host.example/path", good, channel).ok,
    ).toBe(false);
    expect(
      postHostChannelEvent(target, "https://host.example", good, channel).ok,
    ).toBe(true);
    expect(calls).toHaveLength(1);
    expect(calls[0].origin).toBe("https://host.example");
    expect(calls[0].message.type).toBe("agent-platform:assistant:event");
    expect(calls[0].message.nonce).toBe(channel.nonce);
    expect(calls[0].message.event.runId).toBe("r_1");
    expect(typeof calls[0].message.event).toBe("object");
  });

  it("rejects forged origin, source, nonce, instance and version", () => {
    const source = { name: "parent" };
    const expected = {
      origin: "https://host.example",
      source,
      nonce: "nonce_12345678",
      instanceId: "inst_12345678",
    };
    const message = (over: Record<string, unknown> = {}) => ({
      origin: "https://host.example",
      source,
      data: {
        type: "agent-platform:assistant:event",
        schemaVersion: "1.0",
        nonce: expected.nonce,
        instanceId: expected.instanceId,
        event: good,
        ...over,
      },
    });
    expect(acceptHostChannelMessage(message(), expected).ok).toBe(true);
    expect(
      acceptHostChannelMessage(message({}), { ...expected, origin: "https://evil.example" }).ok,
    ).toBe(false);
    expect(
      acceptHostChannelMessage(
        { ...message(), origin: "https://evil.example" },
        expected,
      ).ok,
    ).toBe(false);
    expect(
      acceptHostChannelMessage({ ...message(), source: {} }, expected).ok,
    ).toBe(false);
    expect(acceptHostChannelMessage(message({ nonce: "x" }), expected).ok).toBe(
      false,
    );
    expect(
      acceptHostChannelMessage(message({ instanceId: "inst_other" }), expected).ok,
    ).toBe(false);
    expect(
      acceptHostChannelMessage(message({ schemaVersion: "2.0" }), expected).ok,
    ).toBe(false);
  });

  it("parses one-time hello channel parameters", () => {
    expect(
      readHostChannelHello({
        type: "agent-platform:assistant:hello",
        nonce: "nonce_12345678",
        instanceId: "inst_12345678",
      }),
    ).toEqual({
      ok: true,
      channel: { nonce: "nonce_12345678", instanceId: "inst_12345678" },
    });
    expect(
      readHostChannelHello({
        type: "agent-platform:assistant:hello",
        nonce: "x",
        instanceId: "inst_12345678",
      }).ok,
    ).toBe(false);
  });
});
