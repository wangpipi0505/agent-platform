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
const authorityReasons=['AUTHORITY_EXPIRED','AUTHORITY_DENIED','AUTHORITY_UNAVAILABLE'];
export function authorityReset(event:any) {
  if(event?.type!=='stream_reset'||!authorityReasons.includes(event.reason))return;
  const reason=event.reason;
  return Object.assign(Error(reason==='AUTHORITY_UNAVAILABLE'?'授权服务暂不可用，请手动恢复连接':reason==='AUTHORITY_DENIED'?'当前身份无权继续观察，请手动恢复连接':'观察授权已到期，请重新连接'),{reason,code:reason,status:reason==='AUTHORITY_UNAVAILABLE'?503:403});
}
export const authorityFailure=(error:any)=>error?.status===401||error?.status===403||authorityReasons.includes(error?.reason)
  ||(error?.status===503&&['IAM_AUTHORITY_UNAVAILABLE','PORTAL_SESSION_UNAVAILABLE','AUTHORITY_UNAVAILABLE'].includes(error.code));
export async function streamEvents(
  url: string,
  token: () => string,
  watermark: number,
  onEvent: (e: any) => void,
  signal: AbortSignal,
  onUnauthorized?: () => void,
  scope?: {signal:AbortSignal;current:()=>boolean},
) {
  // A disconnected viewer never cancels the run. The caller restores authoritative status before every reconnect.
  if(scope)signal=signal===scope.signal?signal:AbortSignal.any([signal,scope.signal]);
  const check=()=>{signal.throwIfAborted();if(scope&&!scope.current())throw new DOMException('观察已停止','AbortError');};
  check();
  const requestToken = token();
  if (!requestToken) throw new Error("请先登录");
  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${requestToken}`,
      "Last-Event-ID": String(watermark),
      Accept: "text/event-stream",
    },
    signal,
    credentials:'omit',
  });
  check();
  if (response.status === 401 && token() === requestToken) onUnauthorized?.();
  if (!response.ok) {
    const envelope=await response.json().catch(()=>({}));check();
    throw Object.assign(new Error(envelope.message||`事件连接失败 (${response.status})`), { status: response.status,code:envelope.code });
  }
  if (!response.body) throw new Error("服务未返回事件流");
  const reader = response.body.getReader();
  const stopReader=()=>{void reader.cancel().catch(()=>{});};
  signal.addEventListener('abort',stopReader,{once:true});
  const decoder = new TextDecoder();
  let pending = "";
  try { while (true) {
    check();
    const r = await reader.read();
    check();
    pending += decoder.decode(r.value, { stream: !r.done });
    const { frames, rest } = parseFrames(pending);
    pending = rest;
    for (const f of frames) {
      check();
      if (!f.data) continue;
      const payload = JSON.parse(f.data);
      const event={
        ...payload,
        type: payload.type || (f.event === "chunk" ? "CHUNK" : f.event),
        seq: Number(f.id || payload.seq),
      };
      const stopped=authorityReset(event);if(stopped)throw stopped;
      check();onEvent(event);check();
    }
    if (r.done) return;
  } } finally {signal.removeEventListener('abort',stopReader);try{await reader.cancel();}finally{reader.releaseLock();}}
}
