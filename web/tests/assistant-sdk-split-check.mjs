import { chromium } from "@playwright/test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";

// INT-05 host-API check plus INT-06 config-release check: the shipped IIFE must
// keep the original host contract (init/destroy, chat, restore, approval,
// artifact download) and the additive host lifecycle (open/close/updateContext/
// on/off), while configReleaseId stays a proposal: the server-returned session
// reference wins, turns never carry it, and legacy sessions keep their request.
const web = path.resolve(import.meta.dirname, "..");
const repo = path.resolve(web, "..");
const bundle = path.join(web, "assistant.js/dist/assistant.js");
const evidenceDir = path.join(repo, "docs/evidence/int-07-20261104");
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

const experience = {
  schemaVersion: "v1",
  appCode: "demo",
  experienceRev: 4,
  configReleaseRef: { configReleaseId: "cr_1", digest: "sha256:release" },
  title: "客服助手",
  welcomeText: "你好，<script>alert(1)</script> 我可以帮你查询订单。",
  capabilities: [
    {
      code: "query",
      label: "订单查询",
      description: "只读查询本周订单",
      readOnly: true,
    },
    {
      code: "refund",
      label: "发起退款",
      description: "提交后需人工确认",
      requiresApproval: true,
    },
  ],
  prompts: [
    {
      ref: "p_order",
      version: 1,
      title: "查询本周订单",
      variables: [{ name: "week", required: true, description: "周次" }],
    },
    { ref: "p_help", version: 1, title: "使用帮助" },
  ],
};

const fileDeclarative = {
  type: "CHAT",
  inputSchema: {
    type: "object",
    properties: {
      message: { type: "string" },
      context: { type: "object" },
      attachment: {
        type: "string",
        format: "binary",
        title: "附件",
        description: "附件",
        required: true,
      },
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
      lastEventId: request.headers()["last-event-id"] || "",
    });
    const ok = (data) => json(route, { code: "OK", data });
    // Experience projection: rich, not-active, unreachable, or absent.
    if (url.pathname === "/v1/apps/demo/experience") return ok(experience);
    if (url.pathname === "/v1/apps/second/experience")
      return json(
        route,
        { code: "CONFIG_RELEASE_NOT_ACTIVE", message: "暂无激活配置" },
        409,
      );
    if (url.pathname === "/v1/apps/broken/experience")
      return json(route, { code: "SYS-500", message: "投影失败" }, 500);
    if (
      ["/v1/apps/file_demo/experience", "/v1/apps/flaky/experience"].includes(
        url.pathname,
      )
    )
      return json(route, { code: "NOT_FOUND", message: "无此资源" }, 404);
    if (url.pathname === "/v1/apps/demo/input-schema")
      return ok(declarative);
    if (url.pathname === "/v1/apps/second/input-schema")
      return ok(declarative);
    if (url.pathname === "/v1/apps/flow_demo/input-schema")
      return ok({ type: "FLOW", inputs: [] });
    if (url.pathname === "/v1/apps/file_demo/input-schema")
      return ok(fileDeclarative);
    if (url.pathname === "/v1/apps/flaky/input-schema")
      return ok(declarative);
    if (url.pathname === "/v1/apps/demo/sessions" && method === "POST") {
      state.session = "s_1";
      const body = request.postDataJSON() || {};
      // Server resolves the proposal; only an accepted reference is echoed back.
      return ok({
        sessionId: "s_1",
        ...(body.configReleaseId === "cr_9"
          ? { configReleaseId: "cr_9" }
          : {}),
      });
    }
    if (
      url.pathname === "/v1/apps/demo/sessions/s_1/turns" &&
      method === "POST"
    ) {
      const body = request.postDataJSON();
      return ok({
        runId: body?.inputs?.message === "执行" ? "r_2" : "r_1",
        status: "QUEUED",
      });
    }
    if (url.pathname === "/v1/apps/flow_demo/runs" && method === "POST")
      return ok({ runId: "r_flow", status: "QUEUED" });
    if (url.pathname === "/v1/apps/second/sessions" && method === "POST")
      return ok({ sessionId: "s_2" });
    if (
      url.pathname === "/v1/apps/second/sessions/s_2/turns" &&
      method === "POST"
    )
      return ok({ runId: "r_3", status: "QUEUED" });
    if (url.pathname === "/v1/apps/file_demo/sessions" && method === "POST")
      return ok({ sessionId: "s_file" });
    if (
      url.pathname === "/v1/apps/file_demo/sessions/s_file/turns" &&
      method === "POST"
    )
      return ok({ runId: "r_file", status: "QUEUED" });
    if (url.pathname === "/v1/apps/flaky/sessions" && method === "POST")
      return ok({ sessionId: "s_f" });
    if (
      url.pathname === "/v1/apps/flaky/sessions/s_f/turns" &&
      method === "POST"
    ) {
      state.flaky = (state.flaky || 0) + 1;
      if (state.flaky === 1)
        return json(route, { code: "SYS-500", message: "引擎繁忙" }, 500);
      return ok({ runId: "r_f", status: "QUEUED" });
    }
    if (url.pathname === "/v1/apps/demo/sessions/s_prev")
      return ok({
        sessionId: "s_prev",
        versionNo: 7,
        turns: [{ run_public_id: "r_prev" }],
        configReleaseId: "cr_old",
      });
    if (
      url.pathname === "/v1/apps/demo/sessions/s_prev/turns" &&
      method === "POST"
    )
      return ok({ runId: "r_prev2", status: "QUEUED" });
    if (url.pathname === "/v1/runs/r_prev2/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "RUN_FINISHED",
            data: { output: { text: "继续回答" } },
          },
        ]),
      });
    if (url.pathname === "/v1/apps/slow/experience")
      return json(route, { code: "NOT_FOUND", message: "无此资源" }, 404);
    if (url.pathname === "/v1/apps/slow/input-schema")
      return ok(declarative);
    if (url.pathname === "/v1/apps/slow/sessions" && method === "POST")
      return ok({ sessionId: "s_slow", configReleaseId: "cr_slow" });
    if (
      url.pathname === "/v1/apps/slow/sessions/s_slow/turns" &&
      method === "POST"
    )
      return ok({ runId: "r_slow", status: "QUEUED" });
    if (url.pathname === "/v1/runs/r_slow/events") {
      const stream = (state.slowEvents = (state.slowEvents || 0) + 1);
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse(
          stream === 1
            ? [{ id: "1", event: "NODE_STARTED", data: { nodeId: "n1" } }]
            : [
                {
                  id: "2",
                  event: "RUN_FINISHED",
                  data: { output: { text: "重连完成" } },
                },
              ],
        ),
      });
    }
    if (url.pathname === "/v1/runs/r_slow" && method === "GET") {
      const status = (state.slowStatus = (state.slowStatus || 0) + 1);
      return ok(
        status === 1
          ? { status: "RUNNING", eventsWatermark: 1 }
          : {
              status: "SUCCEEDED",
              eventsWatermark: 2,
              output: { text: "重连完成" },
            },
      );
    }
    if (url.pathname === "/v1/runs/r_prev")
      return ok({ inputs: { message: "旧问题" }, output: { text: "旧回答" } });
    if (url.pathname === "/v1/runs/r_1/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "RUN_FINISHED",
            data: { output: { text: "**完成**" } },
          },
        ]),
      });
    if (url.pathname === "/v1/runs/r_flow/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "RUN_FINISHED",
            data: { output: { text: "流程回答" } },
          },
        ]),
      });
    if (url.pathname === "/v1/runs/r_3/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "RUN_FINISHED",
            data: { output: { text: "第二应用回答" } },
          },
        ]),
      });
    if (url.pathname === "/v1/runs/r_file/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "RUN_FINISHED",
            data: { output: { text: "附件已收到" } },
          },
        ]),
      });
    if (url.pathname === "/v1/runs/r_f/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "RUN_FINISHED",
            data: { output: { text: "恢复成功" } },
          },
        ]),
      });
    if (url.pathname === "/v1/runs/r_2/events")
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sse([
          {
            id: "1",
            event: "WAITING_APPROVAL",
            data: { nodeDefId: "approve_node" },
          },
          {
            id: "2",
            event: "RUN_FINISHED",
            data: { output: { text: "已批准" } },
          },
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
    if (url.pathname === "/v1/runs/r_3")
      return ok({
        status: "SUCCEEDED",
        output: { text: "第二应用回答" },
        eventsWatermark: 1,
      });
    if (url.pathname === "/v1/runs/r_file")
      return ok({
        status: "SUCCEEDED",
        output: { text: "附件已收到" },
        eventsWatermark: 1,
      });
    if (url.pathname === "/v1/runs/r_f")
      return ok({
        status: "SUCCEEDED",
        output: { text: "恢复成功" },
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
  // 1. Original init contract: validation, shadow isolation, destroy removal.
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
          tokenised:
            shadow.querySelector("style").textContent.includes(":host"),
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
        result.removed =
          document.querySelector("agent-platform-assistant") === null;
        return result;
      },
      { endpoint: "http://localhost", appCode: "demo" },
    );
    assert.equal(surface.invalidEndpoint, "endpoint 仅允许 http/https");
    assert.equal(surface.missingTicket, "必须提供宿主后端出票函数");
    assert.deepEqual(surface.keys, [
      "open",
      "close",
      "updateContext",
      "on",
      "off",
      "onHostEvent",
      "destroy",
    ]);
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

  // 2. Host API: autoLauncher:false + explicit mount, events, repeat-init rule,
  //    and the original floating launcher untouched for legacy hosts.
  {
    const { page } = await sdkPage();
    const api = await page.evaluate(
      async ({ endpoint }) => {
        const failure = (run) => {
          try {
            run();
            return "no-throw";
          } catch (error) {
            return error.message;
          }
        };
        document.body.insertAdjacentHTML(
          "beforeend",
          '<div id="m1"></div><div id="m2"></div>',
        );
        const m1 = document.getElementById("m1");
        const events = [];
        const offEvents = [];
        const handle = window.AIAssistant.init({
          endpoint,
          appCode: "demo",
          ticket: async () => "tok",
          autoLauncher: false,
          mount: m1,
        });
        const shadow = m1.querySelector("agent-platform-assistant").shadowRoot;
        const noBall = shadow.querySelector(".ball") === null;
        const unsubscribe = handle.on("open", (payload) =>
          events.push(["open", payload.appCode, payload.pageContext.page]),
        );
        handle.on("close", (payload) => events.push(["close", payload.appCode]));
        handle.on("destroy", (payload) =>
          events.push(["destroy", payload.appCode]),
        );
        handle.on("session", (payload) =>
          events.push(["session", payload.sessionId]),
        );
        const listener = () => offEvents.push(1);
        handle.on("close", listener);
        handle.off("close", listener);
        handle.open({ pageContext: { page: "p9" } });
        const opened = getComputedStyle(shadow.querySelector(".drawer")).display;
        handle.close();
        const closed = getComputedStyle(shadow.querySelector(".drawer")).display;
        handle.open();
        const reopened = getComputedStyle(shadow.querySelector(".drawer")).display;
        handle.close();
        const unknownEvent = failure(() => handle.on("nope", () => {}));
        const badContext = failure(() =>
          handle.updateContext({ tenantId: "t1" }),
        );
        const second = window.AIAssistant.init({
          endpoint,
          appCode: "demo",
          ticket: async () => "tok",
          autoLauncher: false,
          mount: m1,
        });
        const reuseCount = m1.querySelectorAll("agent-platform-assistant").length;
        handle.destroy();
        const afterDestroy = m1.querySelectorAll("agent-platform-assistant").length;
        const openAfterDestroy = failure(() => handle.open());
        second.destroy();
        return {
          noBall,
          opened,
          closed,
          reopened,
          events,
          offEvents,
          unknownEvent,
          badContext,
          reuseCount,
          afterDestroy,
          openAfterDestroy,
          finalCount: m1.querySelectorAll("agent-platform-assistant").length,
        };
      },
      { endpoint: "http://localhost" },
    );
    assert.equal(api.noBall, true);
    assert.equal(api.opened, "flex");
    assert.equal(api.closed, "none");
    assert.equal(api.reopened, "flex");
    assert.deepEqual(api.events, [
      ["open", "demo", "p9"],
      ["close", "demo"],
      ["open", "demo", "p9"],
      ["close", "demo"],
      ["destroy", "demo"],
    ]);
    assert.deepEqual(api.offEvents, []);
    assert.equal(api.unknownEvent, "不支持的事件类型：nope");
    assert.equal(api.badContext, "pageContext 字段不允许：tenantId");
    assert.equal(api.reuseCount, 1);
    assert.equal(api.afterDestroy, 1);
    assert.equal(api.openAfterDestroy, "助手已销毁，请重新初始化");
    assert.equal(api.finalCount, 0);
    evidence.checks.hostApiAndEvents = true;
    await page.close();
  }

  // 3. Experience projection: welcome/capabilities/prompts are text-only, a
  //    prompt fills the composer without executing anything.
  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.handle = window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        ticket: async () => "tok",
      });
      window.handle.open();
    }, { endpoint: "http://localhost" });
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".welcome")
        .textContent.includes("你好"),
    );
    const rendered = await page.evaluate(() => {
      const shadow = document.querySelector("agent-platform-assistant").shadowRoot;
      return {
        welcome: shadow.querySelector(".welcome").textContent,
        script: shadow.querySelector(".welcome script") === null,
        capabilities: [...shadow.querySelectorAll(".capability")].map(
          (n) => n.textContent,
        ),
        prompts: [...shadow.querySelectorAll(".prompt")].map(
          (n) => n.textContent,
        ),
        hidden: shadow.querySelector(".experience").hidden,
      };
    });
    assert.match(rendered.welcome, /<script>alert\(1\)<\/script>/);
    assert.equal(rendered.script, true);
    assert.equal(rendered.capabilities.length, 2);
    assert.match(rendered.capabilities[0], /订单查询/);
    assert.match(rendered.capabilities[0], /只读/);
    assert.match(rendered.capabilities[1], /需确认/);
    assert.deepEqual(rendered.prompts, ["查询本周订单", "使用帮助"]);
    assert.equal(rendered.hidden, false);
    const turnsBefore = evidence.requests.filter((r) =>
      r.path.endsWith("/turns"),
    ).length;
    await page
      .getByRole("button", { name: "查询本周订单", exact: true })
      .click();
    const composer = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelector("textarea").value,
    );
    assert.match(composer, /查询本周订单/);
    assert.match(composer, /\*周次：/);
    const turnsAfter = evidence.requests.filter((r) =>
      r.path.endsWith("/turns"),
    ).length;
    assert.equal(turnsAfter, turnsBefore);
    evidence.checks.experienceProjectionAndPrompts = true;
    await page.close();
  }

  // 4. Honest fallback: 409/500/404 projections degrade to direct chat without
  //    inventing abilities, and long Chinese input survives the round trip.
  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.handle = window.AIAssistant.init({
        endpoint,
        appCode: "second",
        ticket: async () => "tok",
      });
      window.handle.open();
    }, { endpoint: "http://localhost" });
    await page.getByText("未获取到该应用的体验描述", { exact: false }).waitFor();
    const fallback = await page.evaluate(() => {
      const shadow = document.querySelector("agent-platform-assistant").shadowRoot;
      return {
        capabilities: shadow.querySelectorAll(".capability").length,
        prompts: shadow.querySelectorAll(".prompt").length,
        note: shadow.querySelector(".experience-note").textContent,
      };
    });
    assert.equal(fallback.capabilities, 0);
    assert.equal(fallback.prompts, 0);
    assert.match(fallback.note, /可直接提问/);
    const longText = "这是一段用于验证中文长文本传输的说明。".repeat(24);
    await page.getByRole("textbox", { name: "输入问题" }).fill(longText);
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("第二应用回答"),
    );
    const turn = evidence.requests.find((r) =>
      r.path.endsWith("/v1/apps/second/sessions/s_2/turns"),
    );
    assert.equal(turn.body.inputs.message, longText);
    evidence.checks.experienceFallbackAndLongChinese = true;
    await page.close();
  }

  // 5. Two applications on one page keep separate sessions; opening one never
  //    restores or writes the other's run.
  {
    const { page } = await sdkPage();
    const mark = evidence.requests.length;
    await page.evaluate(({ endpoint }) => {
      document.body.insertAdjacentHTML(
        "beforeend",
        '<div id="a"></div><div id="b"></div>',
      );
      window.a = window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        ticket: async () => "tok",
        autoLauncher: false,
        mount: document.getElementById("a"),
      });
      window.b = window.AIAssistant.init({
        endpoint,
        appCode: "second",
        ticket: async () => "tok",
        autoLauncher: false,
        mount: document.getElementById("b"),
      });
    }, { endpoint: "http://localhost" });
    await page.evaluate(() => window.a.open());
    await page.locator("#a").getByRole("textbox", { name: "输入问题" }).fill("第一个应用");
    await page.locator("#a").getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelector("#a agent-platform-assistant").shadowRoot.querySelector(".log").innerText.includes("完成"),
    );
    await page.evaluate(() => window.b.open());
    await page.locator("#b").getByRole("textbox", { name: "输入问题" }).fill("第二个应用");
    await page.locator("#b").getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelector("#b agent-platform-assistant").shadowRoot.querySelector(".log").innerText.includes("第二应用回答"),
    );
    const sessions = evidence.requests
      .slice(mark)
      .filter((r) => r.path.endsWith("/sessions"));
    assert.deepEqual(
      sessions.map((r) => r.path).sort(),
      ["/v1/apps/demo/sessions", "/v1/apps/second/sessions"],
    );
    const bTurn = evidence.requests
      .slice(mark)
      .find((r) => r.path.endsWith("/v1/apps/second/sessions/s_2/turns"));
    assert.equal(bTurn.body.inputs.message, "第二个应用");
    assert.equal(
      evidence.requests.some((r) => r.path.includes("/v1/apps/second/sessions/s_1")),
      false,
    );
    evidence.checks.twoApplicationsIsolated = true;
    await page.close();
  }

  // 6. Page context: validated before any network call, then carried into the
  //    turn payload; identity-shaped keys are rejected locally.
  {
    const { page } = await sdkPage();
    const context = await page.evaluate(
      async ({ endpoint }) => {
        const failure = (run) => {
          try {
            run();
            return "no-throw";
          } catch (error) {
            return error.message;
          }
        };
        const handle = window.AIAssistant.init({
          endpoint,
          appCode: "demo",
          ticket: async () => "tok",
          context: { page: "p1" },
        });
        const invalidOpen = failure(() =>
          handle.open({ pageContext: { userId: "u1" } }),
        );
        const tooLong = failure(() =>
          handle.updateContext({ query: "x".repeat(257) }),
        );
        const notScalar = failure(() =>
          handle.updateContext({ nested: { a: 1 } }),
        );
        const tooMany = failure(() =>
          handle.updateContext(
            Object.fromEntries(
              Array.from({ length: 17 }, (_, i) => ["k" + i, i]),
            ),
          ),
        );
        handle.updateContext({ page: "p2", count: 3, flag: true });
        handle.open();
        return { invalidOpen, tooLong, notScalar, tooMany, handle: true };
      },
      { endpoint: "http://localhost" },
    );
    assert.equal(context.invalidOpen, "pageContext 字段不允许：userId");
    assert.equal(context.tooLong, "pageContext 字段 query 的值不能超过 256 字符");
    assert.equal(context.notScalar, "pageContext 字段 nested 的值必须是标量");
    assert.equal(context.tooMany, "pageContext 最多允许 16 个字段");
    await page.getByRole("textbox", { name: "输入问题" }).fill("上下文检查");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("完成"),
    );
    const turn = evidence.requests
      .filter((r) => r.path.endsWith("/v1/apps/demo/sessions/s_1/turns"))
      .at(-1);
    assert.deepEqual(turn.body.inputs.context, {
      page: "p2",
      count: 3,
      flag: true,
    });
    evidence.checks.pageContextValidation = true;
    await page.close();
  }

  // 7. File inputs have no embed upload channel: the SDK says so instead of
  //    silently dropping the file or POSTing a partial turn.
  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.handle = window.AIAssistant.init({
        endpoint,
        appCode: "file_demo",
        ticket: async () => "tok",
      });
      window.handle.open();
    }, { endpoint: "http://localhost" });
    await page.getByRole("textbox", { name: "输入问题" }).fill("带附件的问题");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".error")
        .textContent.includes("请填写发布版本声明的其他输入字段后发送"),
    );
    const firstError = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelector(".error").textContent,
    );
    assert.match(firstError, /请填写发布版本声明的其他输入字段后发送/);
    await page
      .locator("agent-platform-assistant")
      .locator('input[type="file"]')
      .setInputFiles({
        name: "a.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("x"),
      });
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".error")
        .textContent.includes("嵌入端不提供上传通道"),
    );
    const secondError = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelector(".error").textContent,
    );
    assert.match(secondError, /嵌入端不提供上传通道/);
    assert.equal(
      evidence.requests.some((r) => r.path.includes("/file_demo/sessions/s_file/turns")),
      false,
    );
    evidence.checks.fileInputBlocked = true;
    await page.close();
  }

  // 8. Error recovery: a failed turn restores the text, the retry succeeds.
  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.handle = window.AIAssistant.init({
        endpoint,
        appCode: "flaky",
        ticket: async () => "tok",
      });
      window.handle.open();
    }, { endpoint: "http://localhost" });
    await page.getByRole("textbox", { name: "输入问题" }).fill("重试");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".error")
        .textContent.includes("引擎繁忙"),
    );
    const restored = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelector("textarea").value,
    );
    assert.equal(restored, "重试");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("恢复成功"),
    );
    const attempts = evidence.requests.filter((r) =>
      r.path.endsWith("/v1/apps/flaky/sessions/s_f/turns"),
    );
    assert.equal(attempts.length, 2);
    evidence.checks.errorRecovery = true;
    await page.close();
  }

  // 9. Legacy flows stay green: chat + session handoff, restore, FLOW, approvals
  //    and artifact download.
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
          document
            .querySelector("agent-platform-assistant")
            .shadowRoot.querySelector(".drawer"),
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
    // The terminal chunk renders the answer before the run promise settles and
    // the composer is re-enabled; wait for the completed state, not one frame.
    await page.waitForFunction(
      () =>
        !document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelector(".send").disabled,
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
    const turn = evidence.requests
      .filter((r) => r.path.endsWith("/sessions/s_1/turns"))
      .at(-1);
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
    const flowRun = evidence.requests.find(
      (r) => r.path === "/v1/apps/flow_demo/runs",
    );
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

  // INT-06-a: the client may propose configReleaseId, but only the server's
  // resolved reference is authoritative; turns never carry the proposal.
  {
    const { page } = await sdkPage({ search: "?configReleaseId=cr_9" });
    await page.evaluate(({ endpoint }) => {
      window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    const from = evidence.requests.length;
    await page.getByRole("textbox", { name: "输入问题" }).fill("你好");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("完成"),
    );
    const session = evidence.requests
      .slice(from)
      .find((r) => r.path === "/v1/apps/demo/sessions" && r.method === "POST");
    assert.deepEqual(session.body, { configReleaseId: "cr_9" });
    const turn = evidence.requests
      .slice(from)
      .find((r) => r.path === "/v1/apps/demo/sessions/s_1/turns");
    assert.equal("configReleaseId" in turn.body, false);
    evidence.checks.configProposalPassthrough = true;
    await page.close();
  }

  // INT-06-b: restoring an old session reads the server-returned reference;
  // a newer local proposal never overrides it and the mismatch is surfaced.
  {
    const { page } = await sdkPage({
      search: "?sessionId=s_prev&configReleaseId=cr_new",
    });
    await page.evaluate(({ endpoint }) => {
      window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    await page.getByText("旧问题", { exact: false }).waitFor();
    const notice = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelector(".log .experience-note")?.textContent || "",
    );
    assert.match(notice, /继续使用创建时的配置版本/);
    const from = evidence.requests.length;
    await page.getByRole("textbox", { name: "输入问题" }).fill("继续");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.getByText("继续回答", { exact: false }).waitFor();
    const turn = evidence.requests
      .slice(from)
      .find((r) => r.path === "/v1/apps/demo/sessions/s_prev/turns");
    assert.equal("configReleaseId" in turn.body, false);
    evidence.checks.restoreUsesServerReference = true;
    await page.close();
  }

  // INT-06-c: hosts without a proposal keep the exact legacy request/response.
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
    const from = evidence.requests.length;
    await page.getByRole("textbox", { name: "输入问题" }).fill("你好");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("完成"),
    );
    const session = evidence.requests
      .slice(from)
      .find((r) => r.path === "/v1/apps/demo/sessions" && r.method === "POST");
    assert.deepEqual(session.body, {});
    const turn = evidence.requests
      .slice(from)
      .find((r) => r.path === "/v1/apps/demo/sessions/s_1/turns");
    assert.equal("configReleaseId" in turn.body, false);
    const notes = await page.evaluate(
      () =>
        document
          .querySelector("agent-platform-assistant")
          .shadowRoot.querySelectorAll(".log .experience-note").length,
    );
    assert.equal(notes, 0);
    evidence.checks.legacySessionRequestUnchanged = true;
    await page.close();
  }

  // INT-06-d: an interrupted SSE stream resumes from the observer cursor; the
  // resumed turn still belongs to the same session/run and is sent only once.
  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.AIAssistant.init({
        endpoint,
        appCode: "slow",
        ticket: async () => "tok",
      });
    }, { endpoint: "http://localhost" });
    await page.getByRole("button", { name: "打开智能助手" }).click();
    await page.getByRole("textbox", { name: "输入问题" }).fill("重连主题");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.getByText("重连完成", { exact: false }).waitFor();
    const events = evidence.requests.filter(
      (r) => r.path === "/v1/runs/r_slow/events",
    );
    assert.equal(events.length, 2);
    assert.equal(events[0].lastEventId, "0");
    assert.equal(events[1].lastEventId, "1");
    const turns = evidence.requests.filter(
      (r) => r.path === "/v1/apps/slow/sessions/s_slow/turns",
    );
    assert.equal(turns.length, 1);
    evidence.checks.interruptedStreamResumesWithCursor = true;
    await page.close();
  }

  // INT-07: the seven standard events reach the same-page callback; the
  // authoritative terminal state produces exactly one result.
  {
    const { page } = await sdkPage();
    await page.evaluate(({ endpoint }) => {
      window.hostEvents = [];
      window.handle = window.AIAssistant.init({
        endpoint,
        appCode: "demo",
        integrationId: "int_ops",
        ticket: async () => "tok",
        onHostEvent: (event) => window.hostEvents.push(event),
      });
      window.handle.open();
    }, { endpoint: "http://localhost" });
    await page.getByRole("textbox", { name: "输入问题" }).fill("你好");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction(() =>
      document
        .querySelector("agent-platform-assistant")
        .shadowRoot.querySelector(".log")
        .innerText.includes("完成"),
    );
    const events = await page.evaluate(() => window.hostEvents);
    const names = events.map((e) => e.event);
    assert.equal(names.filter((n) => n === "ready").length, 1);
    assert.equal(names.filter((n) => n === "opened").length, 1);
    assert.equal(names.filter((n) => n === "run-started").length, 1);
    assert.equal(names.filter((n) => n === "result").length, 1);
    const result = events.find((e) => e.event === "result");
    assert.equal(result.schemaVersion, "1.0");
    assert.equal(result.runId, "r_1");
    assert.equal(result.status, "SUCCEEDED");
    assert.equal(result.integrationId, "int_ops");
    assert.equal(result.structured.businessEffect, "UNKNOWN");
    assert.equal("configReleaseId" in result, false);
    evidence.checks.hostResultEventDelivery = true;
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
