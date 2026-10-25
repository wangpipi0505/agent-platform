import { writeFile, mkdir } from "node:fs/promises";
import openapiTS, { astToString } from "openapi-typescript";
const origin = process.env.PLATFORM_GATEWAY || "http://127.0.0.1:18080";
// The short-lived development ticket exists only in this process; never write it into generated artifacts.
const auth = await fetch(origin + "/auth/dev-token", { method: "POST" });
const envelope = await auth.json();
if (!auth.ok || envelope.code !== "OK")
  throw new Error("开发登录失败，无法读取真实OpenAPI");
const token = envelope.data.accessToken;
const response = await fetch(origin + "/v3/api-docs", {
  headers: { Authorization: "Bearer " + token },
});
if (!response.ok) throw new Error("读取真实OpenAPI失败：" + response.status);
const spec = await response.json();
if (!spec.openapi || !spec.paths) throw new Error("网关未返回有效OpenAPI文档");
const ast = await openapiTS(spec);
await mkdir(new URL("./generated/", import.meta.url), { recursive: true });
await writeFile(
  new URL("./generated/api.d.ts", import.meta.url),
  astToString(ast),
);
console.log("Generated shared/generated/api.d.ts from live gateway");
