import { it, expect, vi } from "vitest";
import { streamEvents } from "../debug-panel/events";
it("normalizes live chunk events without inventing durable sequence IDs", async () => {
  vi.stubGlobal(
    "fetch",
    async () => new Response('event:chunk\ndata:{"text":"真实增量"}\n\n'),
  );
  const events: any[] = [];
  await streamEvents(
    "/events",
    () => "t",
    '10',
    (e) => events.push(e),
    new AbortController().signal,
  );
  expect(events[0].type).toBe("chunk");
  expect(events[0].seq).toBeUndefined();
  vi.unstubAllGlobals();
});
