// UI-07：独立对话端与SDK视觉统一——Shadow DOM令牌隔离、执行详情收纳、
// 契约保持、无虚构成功。源码断言（readFileSync；css?raw 在 vitest 下返回空）。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (name: string) =>
  readFileSync(new URL("../assistant.js/src/" + name, import.meta.url), "utf8");
const widgetTsSource = read("widget.ts");
const widgetCssSource = read("widget.css");
const mainTsSource = read("main.ts");
const runtimeTsSource = read("runtime.ts");
const appVueSource = readFileSync(
  new URL("../webapp/src/App.vue", import.meta.url),
  "utf8",
);

describe("SDK 语义令牌映射与宿主隔离", () => {
  it("tokenCss 以 :host 作用域注入 Shadow DOM，不向业务页面注入全局暗色", () => {
    expect(widgetTsSource).toContain('import tokenCss from "../../shared/tokens.css?raw"');
    expect(widgetTsSource).toContain('.replace(":root", ":host")');
    // 全部样式都在 attachShadow 内注入；document 层无 style 注入调用
    expect(widgetTsSource).toContain('attachShadow({ mode: "open" })');
    expect(widgetTsSource).not.toContain("document.head.append");
    expect(widgetTsSource).not.toContain("document.body.append(style");
    // 文字比例由 :host 自持并读取令牌字阶（宿主页面排版不受影响）
    expect(widgetCssSource).toContain(":host{font:var(--text-body,15px)/1.7 system-ui;");
  });

  it("校验警示条令牌化：不再依赖硬编码浅橙底", () => {
    expect(widgetTsSource).toContain(
      "background:color-mix(in srgb, var(--st-suspend) 12%, transparent)",
    );
    expect(widgetTsSource).not.toContain("#fff7ed");
  });
});

describe("工程元数据收纳与结果优先", () => {
  it("执行详情为可折叠容器且默认隐藏；setRunMeta 只填呈现不触协议", () => {
    expect(widgetTsSource).toContain(
      '<details class="run-details" hidden><summary>执行详情</summary><pre class="run-meta"></pre></details>',
    );
    expect(widgetTsSource).toContain("export function setRunMeta(root: ShadowRoot, text: string)");
    expect(widgetTsSource).toContain("details.hidden = !text;");
    // 默认状态：结果气泡无平铺 runId/节点等工程元数据（用户先看结果和下一步）
    expect(runtimeTsSource).not.toContain("runId +");
    expect(runtimeTsSource).not.toContain('"运行标识"');
    expect(widgetCssSource).toContain(".run-details{margin:8px 0}");
  });
});

describe("契约与协议保持（呈现层零协议改动）", () => {
  it("init({endpoint,appCode,ticket,context}) 契约与宿主 open/close/updateContext/on/off 保持", () => {
    expect(mainTsSource).toContain("export function init(options: AssistantOptions): AssistantHandle");
    expect(mainTsSource).toContain('if (!/^https?:\\/\\//.test(options.endpoint))');
    expect(mainTsSource).toContain("open(openOptions)");
    expect(mainTsSource).toContain("updateContext(context)");
    expect(mainTsSource).toContain("on(event, listener)");
  });

  it("runtime 请求/SSE/确认/下载路径未被本卡触碰（协议行原样）", () => {
    for (const path of [
      '"/v1/apps/"',
      '"/v1/runs/"',
      "/input-schema",
      "/sessions",
      "/turns",
      "/approvals",
      "/events",
      "/decision",
    ])
      expect(runtimeTsSource).toContain(path);
    expect(runtimeTsSource).toContain("downloadArtifact(");
    expect(runtimeTsSource).toContain("decisionPayload(row, approved, crypto.randomUUID())");
    expect(runtimeTsSource).toContain("observeRun({");
  });

  it("未连接真实服务时不显示虚构成功记录：成功内容只来自终态快照/最终事件", () => {
    // 无预置成功文案；最终回答只在 RUN_FINISHED/terminal 快照到达后渲染
    expect(runtimeTsSource).not.toContain("运行成功");
    expect(runtimeTsSource).toContain('e.type === "RUN_FINISHED"');
    expect(runtimeTsSource).toContain("terminal(snapshot.status)");
    // 连接状态行只陈述处理中/恢复事实，不伪造完成
    expect(runtimeTsSource).toContain('"正在处理 ▍"');
    expect(runtimeTsSource).toContain("连接恢复中，正在核对运行状态；临时片段可能缺失。");
  });
});

describe("Shadow DOM 与 webapp 视觉统一", () => {
  it("结果卡/确认卡/下载按钮/连接状态/执行详情均有着落样式", () => {
    for (const rule of [
      ".log > button{",
      ".bubble p{",
      ".bubble pre{",
      ".approval{",
      ".approval > button{",
      ".connection{",
      ".run-details{",
      ".run-meta{",
    ])
      expect(widgetCssSource).toContain(rule);
  });

  it("webapp 复用全局令牌与自有助手端结构（F4 mock 期登记更新：SessionList/AssistantChat/三卡替换 Chat 直连；tokens 复用与顶栏/面板结构不变）", () => {
    expect(appVueSource).toContain('import SessionList from "./SessionList.vue"');
    expect(appVueSource).toContain('import AssistantChat from "./AssistantChat.vue"');
    expect(appVueSource).toContain('class="topbar"');
    expect(appVueSource).toContain('class="panel"');
  });
});
