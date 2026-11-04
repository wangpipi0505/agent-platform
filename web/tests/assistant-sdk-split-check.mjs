import { chromium } from "@playwright/test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";

// Structural INT-05 check: the split SDK must keep the original host contract
// (init/destroy, chat, restore, approval, artifact download) and shadow-DOM
// isolation, using only the built IIFE bundle a host page would load.
const web = path.resolve(import.meta.dirname, "..");
const repo = path.resolve(web, "..");
const bundle = path.join(web, "assistant.js/dist/assistant.js");
const evidenceDir = path.join(repo, "docs/evidence/int-05-20261104");
const evidence = {
  fixtureOnly: true,
  bundle: "assistant.js/dist/assistant.js",
  checks: {},
  requests: [],
  pageErrors: [],
  dialogs: [],
};

const json = (route, data, status = 200) =>
  route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(data),
  });

const sse = (frames) =>
  frames
    .map(
      (frame) =>
        `id: ${frame.id}\nevent: ${frame.event}\ndata: ${JSON.stringify(frame.data)}\n\n`,
    )
    .join("");

const declarative = {
  type: "CHAT",
  inputSchema: {
    type: "object",
    properties: {
      message: { type: "string" },
      context: { type: "object" },
    },
  },
};

function service(state) {
  return async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    if (url.pathname === "/") {
      return route.fulfill({
        status: 200,
        contentType: "text/html",
        body: "<!doctype html><html><body></body></html>",
      });
    }
    if (!url.pathname.startsWith("/v1/")) return route.fulfill({ status: 404 });
    evidence.requests.push({
      method,
      path: url.pathname,
      query: url.search,
      body: request.postData() ? request.postDataJSON() : null,
      authorization: request.headers()["authorization"] || "",
    });
    const ok = (data) => json(route, { code: "OK", data });
    if (url.pathname === "/v1/apps/demo/input-schema")
      return ok(declarative);
    if (url.pathname === "/v1/apps/flow_demo/input-schema")
      return ok({ type: "FLOW", inputs: [] });
    if (url.pathname === "/v1/apps/demo/sessions" && method === "POST") {
      state.session = "s_1";
      return ok({ sessionId: "s_1" });
    }
    if (url.pathname === "/v1/apps/demo/sessions/s_1/turns" && method === "POST") {
      const body = request.postDataJSON();
      return ok({
        runId: body?.inputs?.message === "执行" ? "r_2" : "r_1",
        status: "QUEUED",
      });
    }
    if (url.pathname === "/v1/apps/flow_demo/runs" && method === "POST")
      return ok({ runId: "r_flow", status: "QUEUED" });
    if (url.pathname === "/v1/apps/demo/sessions/s_prev")
      return ok({
        sessionId: "s_prev",
        versionNo: 7,
        turns: [{ run_public_id: "r_prev" }],
      });
    if (url.pathname === "/v1/runs/r_prev")
      return ok({ inputs: { message: "旧问题" }, output: { text: "旧回答" } });
    if (url.pathname === "/v1/runs/r_1/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          { id: "1", event: "RUN_FINISHED", data: { output: { text: "**完成**" } } },
        ]),
      });
    if (url.pathname === "/v1/runs/r_flow/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          { id: "1", event: "RUN_FINISHED", data: { output: { text: "流程回答" } } },
        ]),
      });
    if (url.pathname === "/v1/runs/r_2/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          { id: "1", event: "WAITING_APPROVAL", data: { nodeDefId: "approve_node" } },
          { id: "2", event: "RUN_FINISHED", data: { output: { text: "已批准" } } },
        ]),
      });
    if (url.pathname === "/v1/runs/r_1")
      return ok({
        status: "SUCCEEDED",
        output: { text: "**完成**" },
        eventsWatermark: 1,
      });
    if (url.pathname === "/v1/runs/r_flow")
      return ok({
        status: "SUCCEEDED",
        output: { text: "流程回答" },
        eventsWatermark: 1,
      });
    if (url.pathname === "/v1/runs/r_2")
      return ok({
        status: "SUCCEEDED",
        output: {
          text: "已批准",
          fileRefs: [
            {
              artifactId: "a1",
              name: "结果.txt",
              summary: "结果",
              externalDownloadUrl: "/v1/runs/r_2/artifacts/a1/download",
            },
          ],
        },
        eventsWatermark: 2,
      });
    if (url.pathname === "/v1/runs/r_2/approvals")
      return ok([
        {
          nodeDefId: "approve_node",
          nodeInstanceId: "ni_1",
          snapshotDigest: "sha256:abc",
          parameterSnapshot: { target: "订单" },
          expiresAt: new Date(Date.now() + 600000).toISOString(),
        },
      ]);
    if (
      url.pathname === "/v1/runs/r_2/approvals/ni_1/decision" &&
      method === "POST"
    )
      return ok({ decided: true });
    if (url.pathname === "/v1/runs/r_2/artifacts/a1/download")
      return route.fulfill({
        status: 200,
        contentType: "application/octet-stream",
        body: Buffer.from("artifact-bytes"),
      });
    return route.fulfill({ status: 404, body: "unexpected " + url.pathname });
  };
}

async function sdkPage({ search = "", endpoint = "http://localhost" } = {}) {
  const page = await browser.newPage();
  page.on("pageerror", (error) => evidence.pageErrors.push(error.message));
  page.on("dialog", (dialog) => {
    evidence.dialogs.push(dialog.message());
    void dialog.accept();
  });
  const state = {};
  await page.route("**/*", service(state));
  await page.goto(endpoint + "/" + search);
  await page.addScriptTag({ path: bundle });
  return { page, state, endpoint };
}

const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
});
await fs.mkdir(evidenceDir, { recursive: true });
try {
  {
    const { page } = await sdkPage();
    const surface = await page.evaluate(
      ({ endpoint, appCode }) => {
        const failure = (run) => {
          try {
            run();
            return "no-throw";
          } catch (error) {
            return error.message;
          }
        };
        const invalidEndpoint = failure(() =>
          window.AIAssistant.init({
            endpoint: "ftp://x",
            appCode,
            ticket: async () => "t",
          }),
        );
        const missingTicket = failure(() =>
          window.AIAssistant.init({ endpoint, appCode }),
        );
        const handle = window.AIAssistant.init({
          endpoint,
          appCode,
          ticket: async () => "t",
        });
        const host = document.querySelector("agent-platform-assistant");
        const shadow = host.shadowRoot;
        const result = {
          invalidEndpoint,
          missingTicket,
          keys: Object.keys(handle),
          hosts: document.querySelectorAll("agent-platform-assistant").length,
          headStyles: document.head.querySelectorAll("style").length,
          bodyStyles: document.body.querySelectorAll("style").length,
          shadowStyles: shadow.querySelectorAll("style").length,
          tokenised: shadow.querySelector("style").textContent.includes(":host"),
          warningRule: shadow
            .querySelector("style")
            .textContent.includes(".output-warning"),
          drawerDisplay: getComputedStyle(
            shadow.querySelector(".drawer"),
          ).display,
          ballPosition: getComputedStyle(shadow.querySelector(".ball"))
            .position,
        };
        handle.destroy();
        result.removed = document.querySelector("agent-platform-assistant") === null;
        return result;
      },
      { endpoint: "http://localhost", appCode: "demo" },
    );
    assert.equal(surface.invalidEndpoint, "endpoint 仅允许 http/https");
    assert.equal(surface.missingTicket, "必须提供宿主后端出票函数");
    assert.deepEqual(surface.keys, ["destroy"]);
    assert.equal(surface.hosts, 1);
    assert.equal(surface.headStyles, 0);
    assert.equal(surface.bodyStyles, 0);
    assert.equal(surface.shadowStyles, 2);
    assert.equal(surface.tokenised, true);
    assert.equal(surface.warningRule, true);
    assert.equal(surface.drawerDisplay, "none");
    assert.equal(surface.ballPosition, "fixed");
    assert.equal(surface.removed, true);
    evidence.checks.mountContractAndShadowIsolation = true;
    await page.close();
  }

  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.handle = window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        context: { page: "p1" },
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    const drawerVisible = await page.evaluate(
      () =>
        getComputedStyle(
          document.querySelector("agent-platform-assistant").shadowRoot.querySelector(
            ".drawer",
          ),
        ).display,
    );
    assert.equal(drawerVisible, "flex");
    await page.getByRole("textbox", { name: "输入问题" }).fill("你好");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("完成"),
    );
    const chat = await page.evaluate(() => {
      const shadow = document.querySelector("agent-platform-assistant").shadowRoot;
      const user = shadow.querySelectorAll(".bubble")[0];
      const reply = shadow.querySelectorAll(".bubble")[1];
      return {
        bubbles: shadow.querySelectorAll(".bubble").length,
        user: user.textContent,
        replyHtml: reply.innerHTML,
        disabled: shadow.querySelector(".send").disabled,
      };
    });
    assert.equal(chat.bubbles, 2);
    assert.equal(chat.user, "你好");
    assert.match(chat.replyHtml, /<strong>完成<\/strong>/);
    assert.equal(chat.disabled, false);
    const turn = evidence.requests.find((r) =>
      r.path.endsWith("/sessions/s_1/turns"),
    );
    assert.equal(turn.body.inputs.message, "你好");
    assert.deepEqual(turn.body.inputs.context, { page: "p1" });
    assert.match(turn.body.requestId, /^[0-9a-f-]{36}$/);
    assert.equal(turn.authorization, "Bearer tok");
    assert.match(page.url(), /sessionId=s_1/);
    evidence.checks.chatFlowAndSessionHandoff = true;
    await page.close();
  }

  {
    const { page } = await sdkPage({ search: "?sessionId=s_prev" });
    await page.evaluate(({ endpoint }) => {
      window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    await page.getByText("旧问题", { exact: false }).waitFor();
    const restored = await page.evaluate(() => {
      const shadow = document.querySelector("agent-platform-assistant").shadowRoot;
      return {
        text: shadow.querySelector(".log").innerText,
        bubbles: shadow.querySelectorAll(".bubble").length,
        sendDisabled: shadow.querySelector(".send").disabled,
      };
    });
    assert.match(restored.text, /旧问题[\s\S]*旧回答/);
    assert.equal(restored.bubbles, 1);
    assert.equal(restored.sendDisabled, false);
    const versioned = evidence.requests.find(
      (r) => r.path.endsWith("/input-schema") && r.query === "?versionNo=7",
    );
    assert.equal(versioned.query, "?versionNo=7");
    evidence.checks.restoreSessionCompatibility = true;
    await page.close();
  }

  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.AIAssistant.init({
        endpoint,
        appCode: "flow_demo",
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    await page.getByRole("textbox", { name: "输入问题" }).fill("流程主题");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("流程回答"),
    );
    const flowRun = evidence.requests.find((r) => r.path === "/v1/apps/flow_demo/runs");
    assert.equal(flowRun.body.inputs.message, "流程主题");
    assert.equal(
      evidence.requests.some((r) => r.path === "/v1/apps/flow_demo/sessions"),
      false,
    );
    evidence.checks.flowRunPath = true;
    await page.close();
  }

  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    await page.getByRole("textbox", { name: "输入问题" }).fill("执行");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.getByRole("button", { name: "确认执行" }).waitFor();
    await page.getByRole("button", { name: "确认执行" }).click();
    await page.waitForFunction(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelectorAll(".approval").length === 0,
    );
    await page.getByRole("button", { name: "下载附件 · 结果" }).waitFor();
    const decision = evidence.requests.find((r) =>
      r.path.endsWith("/approvals/ni_1/decision"),
    );
    assert.equal(decision.body.approved, true);
    assert.equal(decision.body.snapshotDigest, "sha256:abc");
    assert.match(decision.body.requestId, /^[0-9a-f-]{36}$/);
    const cardGone = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelectorAll(".approval").length,
    );
    assert.equal(cardGone, 0);
    const dialogText = evidence.dialogs.at(-1);
    assert.match(dialogText, /确认执行当前参数快照？/);
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", { name: "下载附件 · 结果" }).click();
    const download = await downloadPromise;
    assert.equal(download.suggestedFilename(), "结果.txt");
    await page.screenshot({
      path: path.join(evidenceDir, "assistant-sdk-chat.png"),
      fullPage: true,
    });
    evidence.checks.approvalAndArtifactDownload = true;
    await page.close();
  }

  assert.deepEqual(evidence.pageErrors, []);
  evidence.ok = true;
} finally {
  await fs.writeFile(
    path.join(evidenceDir, "assistant-sdk-structure.json"),
    JSON.stringify(evidence, null, 2),
  );
  process.stdout.write(JSON.stringify(evidence) + "\n");
  await browser.close();
}
