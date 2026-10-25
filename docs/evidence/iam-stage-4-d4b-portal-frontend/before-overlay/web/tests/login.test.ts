import { it, expect, vi } from "vitest";
import { login } from "../shared/api";
it("login explains unavailable gateway instead of leaking JSON parser error", async () => {
  vi.stubGlobal("fetch", async () => new Response("", { status: 502 }));
  await expect(login()).rejects.toThrow("网关登录服务不可用");
  vi.unstubAllGlobals();
});
