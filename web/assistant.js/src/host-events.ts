import { writeSessionUrl } from "../../shared/session";

/**
 * Host-page coupling owned by the SDK. Today it is limited to the session
 * handoff through the page URL; the broader host event bridge belongs to the
 * next L2 step and is deliberately not introduced by this structural split.
 */
export function readSessionId(search: string) {
  return new URLSearchParams(search).get("sessionId") || "";
}

export function publishSession(id: string) {
  writeSessionUrl(id);
}
