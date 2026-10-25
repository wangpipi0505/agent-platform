import axios from "axios";
import { defineStore, getActivePinia } from "pinia";
import { ref } from "vue";
export const useAuth = defineStore("auth", () => {
  const token = ref("");
  const roles = ref<string[]>([]);
  const user = ref<any>();
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
  return { token, roles, user, can };
});
export function createApi(
  token: () => string,
  transport: typeof fetch = axiosTransport,
  onUnauthorized?: () => void,
) {
  return async <T = any>(
    path: string,
    method = "GET",
    body?: unknown,
    signal?: AbortSignal,
  ): Promise<T> => {
    signal?.throwIfAborted();
    const requestToken = token();
    if (!requestToken) throw new Error("请先登录");
    const response = await transport(path, {
      method,
      signal,
      headers: {
        Authorization: `Bearer ${requestToken}`,
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    signal?.throwIfAborted();
    const envelope = await response
      .json()
      .catch(() => ({ message: `服务返回不可读响应 (${response.status})` }));
    signal?.throwIfAborted();
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
      throw error;
    }
    return envelope.data;
  };
}
export function useApi() {
  const auth = useAuth();
  return createApi(
    () => auth.token,
    axiosTransport,
    () => {
      auth.token = "";
    },
  );
}
export async function login() {
  const r = await fetch("/auth/dev-token", { method: "POST" });
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

async function axiosTransport(
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
  });
  return new Response(response.data, {
    status: response.status,
    headers: response.headers as Record<string, string>,
  });
}
