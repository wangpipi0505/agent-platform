import { it, expect, vi } from "vitest";
import { streamEvents } from "../debug-panel/events";
import { createApi } from "../shared/api";
it("SSE refuses an absent token before network access", async () => {
  const transport = vi.fn();
  vi.stubGlobal("fetch", transport);
  await expect(
    streamEvents(
      "/events",
      () => "",
      0,
      () => {},
      new AbortController().signal,
    ),
  ).rejects.toThrow("请先登录");
  expect(transport).not.toHaveBeenCalled();
  vi.unstubAllGlobals();
});
it("authenticated streaming carries replay cursor and decodes fragmented UTF8", async () => {
  const encoder = new TextEncoder();
  const bytes = encoder.encode(
    'id: 8\nevent: CHUNK\ndata: {"text":"中文"}\n\n',
  );
  const transport = vi.fn(
    async () =>
      new Response(
        new ReadableStream({
          start(c) {
            c.enqueue(bytes.slice(0, 42));
            c.enqueue(bytes.slice(42));
            c.close();
          },
        }),
      ),
  );
  vi.stubGlobal("fetch", transport);
  const events: any[] = [];
  await streamEvents(
    "/events",
    () => "secret",
    7,
    (e) => events.push(e),
    new AbortController().signal,
  );
  expect((transport.mock.calls as any)[0]?.[1]).toMatchObject({
    headers: { Authorization: "Bearer secret", "Last-Event-ID": "7" },
  });
  expect(events).toEqual([{ text: "中文", type: "CHUNK", seq: 8 }]);
  vi.unstubAllGlobals();
});
it("API refuses application error responses and preserves trace", async () => {
  const api = createApi(
    () => "t",
    async () =>
      new Response(
        JSON.stringify({
          code: "BIZ-REV",
          message: "草稿已变更",
          traceId: "trace-1",
        }),
        { status: 412 },
      ),
  );
  await expect(api("/draft", "PUT", {})).rejects.toMatchObject({
    message: "草稿已变更",
    status: 412,
    traceId: "trace-1",
  });
});
