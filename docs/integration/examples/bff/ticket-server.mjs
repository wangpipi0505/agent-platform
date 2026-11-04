// 可信后端短票示例（Node.js 18+，无第三方依赖）。
//
// 职责：把“已登录的宿主用户”换成“平台短期运行票据”，浏览器只拿到 ticket。
// 绝不下发 API Key / 模型密钥 / Authority 凭据；票据不写日志、不进 URL。
//
// 用法：
//   HOST_ORIGIN=https://host.example.com \
//   PLATFORM_URL=https://assistant.example.com \
//   INTEGRATION_KEY_ID=... INTEGRATION_KEY_SECRET=... \
//   node ticket-server.mjs
//
// 注意：本示例适用于非 IAM 的可信 BFF 模式；IAM 模式下网关入站边界不放行 /embed/ticket，
// 短凭据应使用 Authority 签发的 portal session。详见 deploy/integration/README.md「已知限制」。

import http from "node:http";

const PORT = Number(process.env.PORT || 8787);
const HOST_ORIGIN = required("HOST_ORIGIN");
const PLATFORM_URL = required("PLATFORM_URL").replace(/\/+$/, "");
const KEY_ID = required("INTEGRATION_KEY_ID");
const KEY_SECRET = required("INTEGRATION_KEY_SECRET");

if (!/^https?:\/\/[^/?#]+$/.test(HOST_ORIGIN)) {
  throw new Error("HOST_ORIGIN 必须是精确 origin（协议+主机+端口，无路径）");
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`缺少环境变量 ${name}`);
  return value;
}

/**
 * 替换为宿主真实的会话校验：从 Cookie/会话存储取**已验**用户。
 * 绝不要从请求体/query 读取 userId、tenantId、roles。
 */
async function verifySession(request) {
  const sessionId = readCookie(request.headers.cookie, "SESSION");
  if (!sessionId) return null;
  // TODO: 接宿主会话存储/SSO；返回 null 表示未登录。
  // 示例默认拒绝，避免把演示代码误当生产实现。
  return null;
}

function readCookie(header, name) {
  if (!header) return "";
  for (const part of header.split(";")) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) return decodeURIComponent(rest.join("="));
  }
  return "";
}

function sameOrigin(request) {
  return request.headers.origin === HOST_ORIGIN;
}

function writeJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(body);
}

async function readBody(request, limit = 4096) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) throw new Error("请求体过大");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function issueTicket(request, response) {
  if (!sameOrigin(request)) {
    return writeJson(response, 403, { code: "INTEGRATION_SOURCE_NOT_ALLOWED", message: "来源不允许" });
  }
  const user = await verifySession(request);
  if (!user) {
    return writeJson(response, 401, { code: "UNAUTHENTICATED", message: "请先登录宿主系统" });
  }
  await readBody(request); // 只接受空体或 appCode 提示；身份一律来自会话

  // 声明由宿主后端构造：userId/roles/attributes 必须来自已在宿主验证的会话。
  // 网关会按接入档案的 claimLimits 再裁剪；平台不会信任浏览器自报。
  const claims = {
    userId: user.userId,
    roles: user.roles ?? [],
    attributes: user.attributes ?? {},
  };

  const upstream = await fetch(`${PLATFORM_URL}/embed/ticket`, {
    method: "POST",
    headers: {
      Authorization: `ApiKey ${KEY_ID}:${KEY_SECRET}`,
      Origin: HOST_ORIGIN,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(claims),
    redirect: "error",
  });

  const envelope = await upstream.json().catch(() => ({}));
  if (!upstream.ok || envelope.code !== "OK" || !envelope.data?.ticket) {
    // 透传可解释码（AUTH/PARAM/TRANSPORT），不带上游响应体细节。
    const code = typeof envelope.code === "string" ? envelope.code : "TICKET_UNAVAILABLE";
    return writeJson(response, upstream.status, { code, message: envelope.message || "取票失败" });
  }

  // 只回短期票据；不透出 apiKey、credentialId、policyDigest 等内部字段。
  return writeJson(response, 200, {
    code: "OK",
    data: {
      ticket: envelope.data.ticket,
      expiresIn: envelope.data.expiresIn,
    },
  });
}

async function readRecord(request, response, url) {
  if (!sameOrigin(request)) {
    return writeJson(response, 403, { code: "INTEGRATION_SOURCE_NOT_ALLOWED", message: "来源不允许" });
  }
  const user = await verifySession(request);
  if (!user) {
    return writeJson(response, 401, { code: "UNAUTHENTICATED", message: "请先登录宿主系统" });
  }
  const source = url.searchParams.get("source") || "";
  const recordId = url.searchParams.get("recordId") || "";
  if (!["example-source"].includes(source) || !/^[A-Za-z0-9._-]{1,64}$/.test(recordId)) {
    return writeJson(response, 400, { code: "INVALID_INPUT", message: "引用不允许" });
  }
  // TODO: 用真实仓储查询，并按 user 重新鉴权（不信任客户端引用本身）。
  return writeJson(response, 200, {
    code: "OK",
    data: { title: "示例记录 " + recordId, summary: "只读摘要；由宿主后端重新鉴权后返回。" },
  });
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", HOST_ORIGIN);
    if (request.method === "POST" && url.pathname === "/assistant/api/ticket") {
      return await issueTicket(request, response);
    }
    if (request.method === "GET" && url.pathname === "/assistant/api/records") {
      return await readRecord(request, response, url);
    }
    return writeJson(response, 404, { code: "NOT_FOUND", message: "未知接口" });
  } catch (error) {
    // 不记录 cookie/Authorization/上游响应体。
    console.error("ticket-server error:", error?.message ?? "unknown");
    return writeJson(response, 500, { code: "INTERNAL_ERROR", message: "内部错误" });
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`ticket-server listening on http://127.0.0.1:${PORT} (origin ${HOST_ORIGIN})`);
});
