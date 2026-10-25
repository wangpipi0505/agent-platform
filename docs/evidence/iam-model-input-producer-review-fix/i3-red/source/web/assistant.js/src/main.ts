import tokenCss from "../../shared/tokens.css?raw";
import { safeMarkdown, outputText, outputWarning, finalAnswer } from "../../shared/content";
import { streamEvents, terminal } from "../../debug-panel/events";
import { decodeRunStatus, record, eventText } from '../../shared/run-protocol';
import { observeRun } from "../../shared/run-observer";
import { decisionPayload } from "../../shared/approval";
import { downloadArtifact } from "../../shared/artifact";
import { restoreSession, writeSessionUrl } from "../../shared/session";
import { buildDeclaredInputs, inputFields } from "../../shared/input-schema";
export function init(options: {
  endpoint: string;
  appCode: string;
  ticket: () => Promise<string>;
  context?: Record<string, unknown>;
}) {
  if (!/^https?:\/\//.test(options.endpoint))
    throw new Error("endpoint 仅允许 http/https");
  if (typeof options.ticket !== "function")
    throw new Error("必须提供宿主后端出票函数");
  // Shadow DOM isolates presentation only; authorization remains enforced by the gateway.
  if (!customElements.get("agent-platform-assistant"))
    customElements.define(
      "agent-platform-assistant",
      class extends HTMLElement {},
    );
  const host = document.createElement("agent-platform-assistant");
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `<style>:host{font:15px/1.7 system-ui;color:var(--ink)}*{box-sizing:border-box}button{cursor:pointer;font:inherit;background:var(--surface);color:var(--ink);border:1px solid var(--line);border-radius:6px;padding:6px 12px}.send{background:var(--brand-primary);color:var(--surface);border:0;padding:10px 16px;margin-top:8px}button:focus-visible{outline:2px solid var(--brand-primary)}.ball{position:fixed;right:24px;bottom:24px;width:48px;height:48px;border:0;border-radius:50%;background:var(--brand-primary);color:var(--surface)}.drawer{position:fixed;right:0;top:0;height:100dvh;max-width:100vw;width:clamp(360px,30vw,560px);background:var(--surface);border-left:1px solid var(--line);box-shadow:var(--shadow-2);padding:24px;z-index:10000;display:flex;flex-direction:column}.log{flex:1;min-height:0;overflow:auto}.bubble{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--bg);padding:12px;border-radius:12px;margin:8px 0}textarea{width:100%;min-height:80px;border:1px solid var(--line);border-radius:8px;padding:10px;font:inherit;resize:vertical;color:var(--ink);background:var(--surface)}.error{color:var(--st-fail-text)}header{display:flex;justify-content:space-between}</style><button class="ball" aria-label="打开智能助手">AI</button><section class="drawer" style="display:none"><header><strong>智能助手</strong><button class="close">关闭</button></header><div class="log"><p>向智能体提问。</p></div><p class="error" role="alert"></p><textarea aria-label="输入问题"></textarea><button class="send">发送</button></section>`;
  const tokens = document.createElement("style");
  tokens.textContent = tokenCss
    .slice(0, tokenCss.indexOf("}") + 1)
    .replace(":root", ":host");
  tokens.textContent += " .output-warning{color:var(--st-suspend-text);background:#fff7ed;border-left:3px solid var(--st-suspend);border-radius:6px;padding:10px 12px;white-space:normal;overflow-wrap:anywhere}";
  root.prepend(tokens);
  document.body.append(host);
  const connection = document.createElement('p');
  connection.setAttribute('role', 'status');
  root.querySelector('.error')!.before(connection);
  const drawer = root.querySelector<HTMLElement>(".drawer")!;
  root.querySelector(".ball")!.addEventListener("click", () => {
    drawer.style.display = "flex";
    void hydrate();
  });
  root
    .querySelector(".close")!
    .addEventListener("click", () => (drawer.style.display = "none"));
  const send = root.querySelector<HTMLButtonElement>(".send")!;
  const input = root.querySelector<HTMLTextAreaElement>("textarea")!;
  let controller: AbortController;
  let running = false;
  let destroyed = false;
  let sessionId = new URLSearchParams(location.search).get("sessionId") || "";
  let declaration: any;
  let hydrated = false;
  async function hydrate() {
    if (!sessionId || hydrated) return;
    send.disabled = true;
    try {
      const token = await options.ticket();
      const request = async (path: string) => {
        const r = await fetch(options.endpoint + path, {
          headers: { Authorization: "Bearer " + token },
        });
        const e = await r.json();
        if (!r.ok || e.code !== "OK") throw Error(e.message || "恢复会话失败");
        return e.data;
      };
      const restored = await restoreSession(
        request,
        options.appCode,
        sessionId,
      );
      declaration = restored.declaration;
      for (const turn of restored.session.turns || []) {
        const old = await request(
          "/v1/runs/" + encodeURIComponent(turn.run_public_id),
        );
        const bubble = document.createElement("div");
        bubble.className = "bubble";
        bubble.textContent = String(old.inputs?.message??JSON.stringify(old.inputs||{}))+'\n'+outputText(old.output);
        const warning = outputWarning(old.output);
        if (warning) {
          const note = document.createElement("p");
          note.setAttribute("role", "status");
          note.className = "output-warning";
          note.textContent = warning;
          bubble.prepend(note);
        }
        root.querySelector(".log")!.append(bubble);
      }
      hydrated = true;
    } catch (e: any) {
      root.querySelector(".error")!.textContent = e.message;
    } finally {
      send.disabled = running || destroyed;
    }
  }
  const extraInputs = new Map<
    string,
    {
      field: any;
      control: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
    }
  >();
  send.addEventListener("click", async () => {
    if (!input.value.trim() || running || destroyed) return;
    running = true;
    send.disabled = true;
    root.querySelector('.error')!.textContent = '';
    connection.textContent = '正在处理 ▍';
    const log = root.querySelector(".log")!;
    const user = document.createElement("div");
    user.className = "bubble";
    user.textContent = input.value;
    log.append(user);
    const message = input.value;
    input.value = "";
    const reply = document.createElement("div");
    reply.className = "bubble";
    log.append(reply);
    let text = "";
    let receivedFinal = false;
    let frame = 0;
    const applyFinal = (value: unknown) => {
      receivedFinal = true;
      cancelAnimationFrame(frame);
      frame = 0;
      const answer = finalAnswer(value);
      text = answer.text;
      reply.innerHTML = safeMarkdown(text);
      if (answer.warning) {
        const note = document.createElement("p");
        note.setAttribute("role", "status");
        note.className = "output-warning";
        note.textContent = answer.warning;
        reply.prepend(note);
      }
    };
    try {
      const request = async (path: string, body?: any, signal?: AbortSignal, decoder?: (text:string)=>any) => {
        signal?.throwIfAborted();
        const token = await options.ticket();
        signal?.throwIfAborted();
        if (destroyed) throw new DOMException('观察已停止', 'AbortError');
        const r = await fetch(options.endpoint + path, {
          method: body ? "POST" : "GET",
          signal,
          headers: {
            Authorization: "Bearer " + token,
            "Content-Type": "application/json",
          },
          ...(body ? { body: JSON.stringify(body) } : {}),
        });
        signal?.throwIfAborted();
        // HTTP status remains authoritative even when a gateway returns no JSON.
        let e;
        try { e = decoder && r.ok ? decoder(await r.text()) : await r.json(); }
        catch (error:any) { signal?.throwIfAborted(); if(error?.code) throw error; e = {message:`服务返回不可读响应 (${r.status})`}; }
        signal?.throwIfAborted();
        if (!r.ok || e?.code !== "OK")
          throw Object.assign(Error(e?.message || `请求失败 (${r.status})`), { status: r.status, code:e?.code });
        return e.data;
      };
      if (!declaration) {
        if (sessionId) {
          const restored = await restoreSession(
            request,
            options.appCode,
            sessionId,
          );
          declaration = restored.declaration;
          for (const turn of restored.session.turns || []) {
            const old = await request(
              "/v1/runs/" + encodeURIComponent(turn.run_public_id),
            );
            const history = document.createElement("div");
            history.className = "bubble";
            history.textContent = String(old.inputs?.message??JSON.stringify(old.inputs||{}))+'\n'+outputText(old.output);
            const warning = outputWarning(old.output);
            if (warning) {
              const note = document.createElement("p");
              note.setAttribute("role", "status");
              note.className = "output-warning";
              note.textContent = warning;
              history.prepend(note);
            }
            log.insertBefore(history, user);
          }
        } else
          declaration = await request(
            "/v1/apps/" + encodeURIComponent(options.appCode) + "/input-schema",
          );
      }
      if (!["CHAT", "FLOW"].includes(declaration.type))
        throw Error("发布输入契约未提供应用类型，请刷新后重试");
      const declared = declaration.inputSchema ?? declaration.inputs;
      let added = false;
      for (const field of inputFields(declared).filter(
        (f) => !["message", "context"].includes(f.name),
      )) {
        if (extraInputs.has(field.name)) continue;
        const label = document.createElement("label");
        label.textContent =
          (field.description || field.name) + (field.required ? " *" : "");
        const control =
          field.enum || field.type === "boolean"
            ? document.createElement("select")
            : ["object", "array"].includes(field.type)
              ? document.createElement("textarea")
              : document.createElement("input");
        control.setAttribute("aria-label", field.name);
        if (control instanceof HTMLSelectElement) {
          const blank = document.createElement("option");
          blank.value = "";
          blank.textContent = "请选择";
          control.append(blank);
          for (const v of field.enum || [true, false]) {
            const option = document.createElement("option");
            option.value = JSON.stringify(v);
            option.textContent = String(v);
            control.append(option);
          }
        } else if (
          control instanceof HTMLInputElement &&
          ["number", "integer"].includes(field.type)
        )
          control.type = "number";
        label.append(control);
        input.before(label);
        extraInputs.set(field.name, { field, control });
        added = true;
      }
      if (added) throw Error("请填写发布版本声明的其他输入字段后发送");
      const raw: any = { message, context: options.context || {} };
      for (const [name, { field, control }] of extraInputs) {
        if (control.value === "") continue;
        raw[name] =
          control instanceof HTMLSelectElement
            ? JSON.parse(control.value)
            : ["number", "integer"].includes(field.type)
              ? Number(control.value)
              : control.value;
      }
      const inputs = inputFields(declared).length
        ? buildDeclaredInputs(declared, raw)
        : raw;
      if (declaration.type === "CHAT" && !sessionId) {
        const session = await request(
          "/v1/apps/" + encodeURIComponent(options.appCode) + "/sessions",
          {},
        );
        sessionId = session.sessionId;
        hydrated = true;
        writeSessionUrl(sessionId);
      }
      async function showApprovals(id: string) {
        try {
          const rows = await request(
            "/v1/runs/" + encodeURIComponent(id) + "/approvals",
          );
          root.querySelectorAll(".approval").forEach((n) => n.remove());
          for (const row of rows) {
            const card = document.createElement("section");
            card.className = "bubble approval";
            const title = document.createElement("strong");
            title.textContent =
              "执行确认 · " + (row.nodeDefId || row.nodeInstanceId);
            const snapshot = document.createElement("pre");
            snapshot.textContent = JSON.stringify(
              row.parameterSnapshot,
              null,
              2,
            );
            const expiry = document.createElement("p");
            expiry.textContent =
              "截止时间：" + (row.expiresAt || "由服务端控制");
            card.append(title, expiry, snapshot);
            for (const [approved, label] of [
              [true, "确认执行"],
              [false, "拒绝"],
            ] as const) {
              const button = document.createElement("button");
              button.textContent = label;
              button.onclick = async () => {
                if (!confirm(label + "当前参数快照？")) return;
                button.disabled = true;
                try {
                  await request(
                    "/v1/runs/" +
                      encodeURIComponent(id) +
                      "/approvals/" +
                      encodeURIComponent(row.nodeInstanceId || row.nodeId) +
                      "/decision",
                    decisionPayload(row, approved, crypto.randomUUID()),
                  );
                  card.remove();
                } catch (e: any) {
                  root.querySelector(".error")!.textContent = e.message;
                  button.disabled = false;
                }
              };
              card.append(button);
            }
            log.append(card);
          }
        } catch (e: any) {
          root.querySelector(".error")!.textContent = e.message;
        }
      }
      const run = await request(
          "/v1/apps/" +
          encodeURIComponent(options.appCode) +
          (declaration.type === "CHAT"
            ? "/sessions/" + encodeURIComponent(sessionId) + "/turns"
            : "/runs"),
        {
            inputs,
            requestId: crypto.randomUUID(),
        },
      );
      if (destroyed) throw new DOMException('观察已停止', 'AbortError');
      controller = new AbortController();
      const signal = controller.signal;
      const result = await observeRun({
        signal,
        open: async (cursor, emit) => {
          const token = await options.ticket();
          if (signal.aborted || destroyed) throw new DOMException('观察已停止', 'AbortError');
          return streamEvents(options.endpoint + '/v1/runs/' + encodeURIComponent(run.runId) + '/events',
            () => token, cursor, emit, signal);
        },
        readStatus: () => request('/v1/runs/' + encodeURIComponent(run.runId), undefined, signal, decodeRunStatus),
        onConnection: state => { connection.textContent = state === 'reconnecting'
          ? '连接恢复中，正在核对运行状态；临时片段可能缺失。'
          : '连接已恢复；临时片段可能缺失，最终以完整结果为准。正在处理 ▍'; },
        onEvent: (e) => {
          const data = record(e.data);
          if (e.type === "WAITING_APPROVAL")
            void showApprovals(run.runId);
          if ((e.type === "CHUNK" || e.type === "chunk") && !receivedFinal) {
            text += eventText(e.data) || "";
            if (!frame) frame = requestAnimationFrame(() => {
              reply.innerHTML = safeMarkdown(text);
              frame = 0;
            });
          }
          if (e.type === "RUN_FINISHED" && data?.output !== undefined)
            applyFinal(data?.output);
        },
        onStatus: snapshot => {
          if (snapshot.status === 'WAITING_APPROVAL') void showApprovals(run.runId);
          // Only a terminal snapshot is authoritative enough to stop accepting chunks.
          if (terminal(snapshot.status))
            applyFinal(snapshot.status === 'FAILED' || snapshot.status === 'CANCELLED'
              ? snapshot.error ?? snapshot.output ?? (snapshot.status === 'FAILED' ? '运行失败' : '运行已取消')
              : snapshot.output);
        },
      });
      if (result.status === 'FAILED')
        root.querySelector('.error')!.textContent = result.error?.message || '本次运行失败，请查看权威运行记录';
      {
        for (const file of result.fileRefs ||
          result.output?.fileRefs ||
          []) {
          const button = document.createElement("button");
          button.textContent =
            "下载附件 · " + (file.summary || file.name || file.artifactId);
          button.onclick = async () => {
            button.disabled = true;
            try {
              await downloadArtifact(
                file,
                run.runId,
                await options.ticket(),
                options.endpoint,
              );
            } catch (e: any) {
              root.querySelector(".error")!.textContent = e.message;
            } finally {
              button.disabled = false;
            }
          };
          log.append(button);
        }
      }
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        input.value = message;
        root.querySelector(".error")!.textContent = e.message + '；可查看权威运行记录。';
      }
    } finally {
      cancelAnimationFrame(frame);
      running = false;
      connection.textContent = '';
      send.disabled = destroyed;
    }
  });
  return {
    destroy() {
      destroyed = true;
      controller?.abort();
      host.remove();
    },
  };
}


