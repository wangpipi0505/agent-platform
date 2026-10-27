import http from "node:http";
import { createHash } from "node:crypto";

const port = Number(process.env.PORT || 28400);
const upstreamBase = (process.env.UPSTREAM_BASE_URL || "https://api.deepseek.com").replace(/\/+$/, "");
const apiKey = process.env.UPSTREAM_API_KEY || "";
const model = process.env.MODEL || "deepseek-flash";

if (!apiKey) {
  console.error("UPSTREAM_API_KEY is required");
  process.exit(2);
}

function sendJson(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "content-length": body.length });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > 4 * 1024 * 1024) {
        reject(new Error("request too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

// Conservative token estimate: enough for the platform's admission/context guard
// while keeping this proxy dependency-free. It is intentionally an estimate, not
// the provider's exact tokenizer; the platform marks tokenizer-based metering as
// exact when tokenizerUrl is configured, so keep the estimate conservative.
function estimateTokens(payload) {
  const text = JSON.stringify(payload ?? {});
  return Math.max(1, Math.ceil(Buffer.byteLength(text, "utf8") / 3)) + 64;
}

function canonicalRequestCount(payload) {
  return estimateTokens({
    model: payload.model ?? model,
    messages: payload.messages ?? [],
    tools: payload.tools ?? [],
    request: payload.request ?? null,
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://127.0.0.1:${port}`);

    if (req.method === "GET" && url.pathname === "/health") {
      return sendJson(res, 200, { ok: true, model, upstream: upstreamBase });
    }

    if (req.method === "GET" && url.pathname === "/v1/models") {
      return sendJson(res, 200, {
        object: "list",
        data: [{ id: model, object: "model", created: 0, owned_by: "local-proxy" }],
      });
    }

    if (req.method === "POST" && url.pathname === "/tokenize") {
      const raw = await readBody(req);
      const payload = JSON.parse(raw.toString("utf8") || "{}");
      const count = canonicalRequestCount(payload);
      const sha = payload.request_sha256 || createHash("sha256").update(raw).digest("hex");
      return sendJson(res, 200, { count, request_sha256: sha });
    }

    if (req.method === "POST" && url.pathname === "/v1/chat/completions") {
      const raw = await readBody(req);
      const payload = JSON.parse(raw.toString("utf8") || "{}");
      const target = new URL(`${upstreamBase}/chat/completions`);
      const headers = {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
        accept: payload.stream ? "text/event-stream" : "application/json",
      };
      const upstream = await fetch(target, {
        method: "POST",
        headers,
        body: Buffer.from(JSON.stringify(payload)),
        signal: AbortSignal.timeout(180_000),
      });
      const contentType = upstream.headers.get("content-type") || "application/json";
      res.writeHead(upstream.status, { "content-type": contentType, "cache-control": "no-cache" });
      if (!upstream.body) {
        res.end();
        return;
      }
      for await (const chunk of upstream.body) res.write(chunk);
      res.end();
      return;
    }

    return sendJson(res, 404, { error: { message: `not found: ${req.method} ${url.pathname}` } });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (!res.headersSent) sendJson(res, 502, { error: { message } });
    else res.end();
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`model proxy listening on http://127.0.0.1:${port} (model=${model})`);
});
