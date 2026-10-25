import { it, expect, vi } from "vitest";
import { streamEvents } from "../debug-panel/events";
it("expires viewer authentication on SSE 401", async () => {
  vi.stubGlobal("fetch", async () => new Response("", { status: 401 }));
  let expired = false;
  await expect(
    streamEvents(
      "/events",
      () => "old", '0',
      () => {},
      new AbortController().signal,
      () => (expired = true),
    ),
  ).rejects.toThrow("401");
  expect(expired).toBe(true);
  vi.unstubAllGlobals();
});
