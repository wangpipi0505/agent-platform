import axios from "axios";
import { defineStore, getActivePinia } from "pinia";
import { ref, shallowRef, onScopeDispose, getCurrentScope } from "vue";
export type SessionScope = { signal: AbortSignal; current: () => boolean };
export type PortalIdentity = {tenantId:string;principalId:string;roles:string[];expiresAt:string};
export function checkScope(scope?: SessionScope) {
  if (scope && (scope.signal.aborted || !scope.current()))
    throw new DOMException('会话已停止，请重新连接', 'AbortError');
}
export function combinedSignal(a?: AbortSignal, b?: AbortSignal) {
  return a && b && a !== b ? AbortSignal.any([a,b]) : a || b;
}
export const useAuth = defineStore("auth", () => {
  const token = ref("");
  const roles = ref<string[]>([]);
  const user = ref<any>();
  const generation=ref(0),controller=shallowRef(new AbortController());
  const connection=ref('idle'),notice=ref('');
  let timer: ReturnType<typeof setTimeout> | undefined;
  function snapshot() {
    const expected=generation.value,signal=controller.value.signal;
    return {generation:expected,signal,current:()=>generation.value===expected&&!signal.aborted};
  }
  function invalidate(expected=generation.value,state='unauthenticated',message='会话已失效，请重新连接') {
    if(expected!==generation.value)return false;
    generation.value++;
    controller.value.abort();controller.value=new AbortController();
    clearTimeout(timer);timer=undefined;
    token.value='';roles.value=[];user.value=undefined;
    connection.value=state;notice.value=message;
    return true;
  }
  function install(expected:number,candidate:string,identity:PortalIdentity) {
    if(expected!==generation.value||controller.value.signal.aborted)return false;
    token.value=candidate;user.value=identity;roles.value=[...identity.roles];
    connection.value='authenticated';notice.value='';
    const deadline=Date.parse(identity.expiresAt);
    const expire=()=>{
      if(expected!==generation.value)return;
      const remaining=deadline-Date.now();
      if(remaining<=0)invalidate(expected,'expired','会话已到期，请重新连接');
      else timer=setTimeout(expire,Math.min(60_000,remaining));
    };
    clearTimeout(timer);expire();return true;
  }
  // UI visibility is not authorization. Every action is still verified by the backend Actor.
  const can = (permission: string) =>
    !!token.value &&
    (roles.value.includes("ADMIN") ||
      roles.value.includes(
        (
          { edit: "EDITOR", approve: "APPROVER", admin: "ADMIN" } as Record<
            string,
            string
          >
        )[permission],
      ));
  return { token, roles, user, can, generation, connection, notice, snapshot, invalidate, install };
});
export function createApi(
  token: () => string,
  transport: typeof fetch = axiosTransport,
  onUnauthorized?: () => void,
  scope?: SessionScope,
) {
  return async <T = any>(
    path: string,
    method = "GET",
    body?: unknown,
    signal?: AbortSignal,
  ): Promise<T> => {
    checkScope(scope);
    signal=combinedSignal(signal,scope?.signal);
    signal?.throwIfAborted();
    const requestToken = token();
    if (!requestToken) throw new Error("请先登录");
    let response:Response;
    try { response = await transport(path, {
      method,
      signal,
      headers: {
        Authorization: `Bearer ${requestToken}`,
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials:'omit',
    }); } catch(error) { checkScope(scope);signal?.throwIfAborted();throw error; }
    checkScope(scope);
    signal?.throwIfAborted();
    const envelope = await response
      .json()
      .catch(() => ({ message: `服务返回不可读响应 (${response.status})` }));
    signal?.throwIfAborted();
    checkScope(scope);
    if (!response.ok || envelope.code !== "OK") {
      // A stopped request or an earlier login must never revoke the current login.
      if (response.status === 401 && token() === requestToken) onUnauthorized?.();
      const error: any = new Error(
        String(envelope.code).startsWith("SYS-")
          ? "系统繁忙，请稍后重试"
          : envelope.message || `请求失败 (${response.status})`,
      );
      error.traceId = envelope.traceId;
      error.status = response.status;
      error.code = envelope.code;
      throw error;
    }
    return envelope.data;
  };
}
export function useApi() {
  const auth = useAuth();
  const session=auth.snapshot();
  // An old component cannot use a new identity in a continuation after an await.
  const disposed=new AbortController();
  if(getCurrentScope())onScopeDispose(()=>disposed.abort());
  const scope={signal:combinedSignal(session.signal,disposed.signal)!,current:session.current};
  return createApi(
    () => auth.token,
    axiosTransport,
    () => {
      auth.invalidate(session.generation);
    },scope,
  );
}
export async function login() {
  const r = await fetch("/auth/dev-token", { method: "POST",credentials:'omit' });
  const e = await r.json().catch(() => {
    throw new Error("网关登录服务不可用，请确认开发环境网关已启动后重试");
  });
  if (!r.ok || e.code !== "OK") throw new Error(e.message || "开发登录失败");
  const token = e.data?.accessToken || e.data?.token || e.data;
  if (typeof token !== "string") throw new Error("登录接口没有返回 token");
  if (getActivePinia()) {
    const auth = useAuth();
    auth.roles = Array.isArray(e.data?.user?.roles) ? e.data.user.roles : [];
    auth.user = e.data?.user;
  }
  return token;
}

export async function axiosTransport(
  url: RequestInfo | URL,
  options?: RequestInit,
): Promise<Response> {
  const response = await axios.request({
    url: String(url),
    method: options?.method || "GET",
    headers: options?.headers as Record<string, string>,
    data: options?.body,
    signal: options?.signal ?? undefined,
    responseType: "text",
    validateStatus: () => true,
    adapter:'fetch',
    withCredentials:false,
  });
  return new Response(response.data, {
    status: response.status,
    headers: response.headers as Record<string, string>,
  });
}
