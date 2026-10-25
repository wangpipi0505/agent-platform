export type Frame = { id: string; event: string; data: string };
export function parseFrames(input: string) {
  const text = input.replace(/\r\n/g, "\n");
  const parts = text.split("\n\n");
  const rest = parts.pop()!;
  return {
    frames: parts
      .filter((p) => p.trim())
      .map((p) => {
        const f: Frame = { id: "", event: "message", data: "" };
        const data: string[] = [];
        for (const line of p.split("\n")) {
          const i = line.indexOf(":");
          const key = line.slice(0, i);
          const value = line.slice(i + 1).replace(/^ /, "");
          if (key === "data") data.push(value);
          if (key === "id") f.id = value;
          if (key === "event") f.event = value;
        }
        f.data = data.join("\n");
        return f;
      }),
    rest,
  };
}
export type EventState = {
  watermark: number;
  events: any[];
  nodes: Record<string, string>;
};
export function applyEvent(s: EventState, e: any): EventState {
  if (!Number.isFinite(e.seq) || e.seq <= s.watermark) return s;
  const nodes = { ...s.nodes };
  const id = e.nodeDefId || e.nodeId;
  if (id)
    nodes[id] =
      (
        {
          NODE_STARTED: "RUNNING",
          NODE_FINISHED: "SUCCEEDED",
          NODE_FAILED: "FAILED",
          WAITING_STEP: "WAITING_STEP",
          WAITING_APPROVAL: "WAITING_APPROVAL",
        } as any
      )[e.type] || nodes[id];
  return { watermark: e.seq, events: [...s.events, e], nodes };
}
export const terminal = (status: string) =>
  [
    "SUCCEEDED",
    "SUCCESS",
    "COMPLETED",
    "FAILED",
    "CANCELLED",
    "FINISHED",
  ].includes(status);
export async function streamEvents(
  url: string,
  token: () => string,
  watermark: number,
  onEvent: (e: any) => void,
  signal: AbortSignal,
  onUnauthorized?: () => void,
) {
  // A disconnected viewer never cancels the run. The caller restores authoritative status before every reconnect.
  signal.throwIfAborted();
  const requestToken = token();
  if (!requestToken) throw new Error("请先登录");
  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${requestToken}`,
      "Last-Event-ID": String(watermark),
      Accept: "text/event-stream",
    },
    signal,
  });
  signal.throwIfAborted();
  if (response.status === 401 && token() === requestToken) onUnauthorized?.();
  if (!response.ok)
    throw Object.assign(new Error(`事件连接失败 (${response.status})`), { status: response.status });
  if (!response.body) throw new Error("服务未返回事件流");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let pending = "";
  while (!signal.aborted) {
    const r = await reader.read();
    pending += decoder.decode(r.value, { stream: !r.done });
    const { frames, rest } = parseFrames(pending);
    pending = rest;
    for (const f of frames) {
      if (!f.data) continue;
      const payload = JSON.parse(f.data);
      onEvent({
        ...payload,
        type: payload.type || (f.event === "chunk" ? "CHUNK" : f.event),
        seq: Number(f.id || payload.seq),
      });
    }
    if (r.done) return;
  }
}
