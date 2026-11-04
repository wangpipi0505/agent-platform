import {
  safeMarkdown,
  outputText,
  outputWarning,
  finalAnswer,
} from "../../shared/content";
import { streamEvents, terminal } from "../../debug-panel/events";
import { decodeRunStatus, record, eventText } from "../../shared/run-protocol";
import { observeRun } from "../../shared/run-observer";
import { decisionPayload } from "../../shared/approval";
import { downloadArtifact } from "../../shared/artifact";
import { restoreSession } from "../../shared/session";
import {
  requireApplicationType,
  declaredSchema,
  renderDeclaredInputs,
  collectInputs,
} from "./experience";
import { bubble, prependWarning } from "./widget";
import { publishSession } from "./host-events";
import type { AssistantOptions, ExtraInput, Widget } from "./types";

/**
 * Session and run orchestration. Shared modules keep the protocol, approval,
 * artifact and observer state machines; this layer only wires them to the
 * widget and the host-supplied ticket callback.
 */
export function createRuntime(
  options: AssistantOptions,
  widget: Widget,
  initialSessionId: string,
) {
  let controller: AbortController | undefined;
  let running = false;
  let destroyed = false;
  let sessionId = initialSessionId;
  let declaration: any;
  let hydrated = false;
  const extraInputs = new Map<string, ExtraInput>();

  async function appendHistory(
    request: (path: string) => Promise<any>,
    runPublicId: string,
    before?: HTMLElement,
  ) {
    const old = await request(
      "/v1/runs/" + encodeURIComponent(runPublicId),
    );
    const element = document.createElement("div");
    element.className = "bubble";
    element.textContent =
      String(old.inputs?.message ?? JSON.stringify(old.inputs || {})) +
      "\n" +
      outputText(old.output);
    const warning = outputWarning(old.output);
    if (warning) prependWarning(element, warning);
    if (before) widget.log.insertBefore(element, before);
    else widget.log.append(element);
  }

  async function hydrate() {
    if (!sessionId || hydrated) return;
    widget.send.disabled = true;
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
      for (const turn of restored.session.turns || [])
        await appendHistory(request, turn.run_public_id);
      hydrated = true;
    } catch (e: any) {
      widget.error.textContent = e.message;
    } finally {
      widget.send.disabled = running || destroyed;
    }
  }

  async function send() {
    const input = widget.input;
    if (!input.value.trim() || running || destroyed) return;
    running = true;
    widget.send.disabled = true;
    widget.error.textContent = "";
    widget.connection.textContent = "正在处理 ▍";
    const user = bubble(widget, input.value);
    const message = input.value;
    input.value = "";
    const reply = bubble(widget, "");
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
      if (answer.warning) prependWarning(reply, answer.warning);
    };
    try {
      const request = async (
        path: string,
        body?: any,
        signal?: AbortSignal,
        decoder?: (text: string) => any,
      ) => {
        signal?.throwIfAborted();
        const token = await options.ticket();
        signal?.throwIfAborted();
        if (destroyed) throw new DOMException("观察已停止", "AbortError");
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
        try {
          e = decoder && r.ok ? decoder(await r.text()) : await r.json();
        } catch (error: any) {
          signal?.throwIfAborted();
          if (error?.code) throw error;
          e = { message: `服务返回不可读响应 (${r.status})` };
        }
        signal?.throwIfAborted();
        if (!r.ok || e?.code !== "OK")
          throw Object.assign(Error(e?.message || `请求失败 (${r.status})`), {
            status: r.status,
            code: e?.code,
          });
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
          for (const turn of restored.session.turns || [])
            await appendHistory(request, turn.run_public_id, user);
        } else
          declaration = await request(
            "/v1/apps/" +
              encodeURIComponent(options.appCode) +
              "/input-schema",
          );
      }
      requireApplicationType(declaration);
      const declared = declaredSchema(declaration);
      if (renderDeclaredInputs(input, declared, extraInputs))
        throw Error("请填写发布版本声明的其他输入字段后发送");
      const inputs = collectInputs(
        declared,
        message,
        options.context || {},
        extraInputs,
      );
      if (declaration.type === "CHAT" && !sessionId) {
        const session = await request(
          "/v1/apps/" + encodeURIComponent(options.appCode) + "/sessions",
          {},
        );
        sessionId = session.sessionId;
        hydrated = true;
        publishSession(sessionId);
      }
      async function showApprovals(id: string) {
        try {
          const rows = await request(
            "/v1/runs/" + encodeURIComponent(id) + "/approvals",
          );
          widget.root.querySelectorAll(".approval").forEach((n) => n.remove());
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
                  widget.error.textContent = e.message;
                  button.disabled = false;
                }
              };
              card.append(button);
            }
            widget.log.append(card);
          }
        } catch (e: any) {
          widget.error.textContent = e.message;
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
      if (destroyed) throw new DOMException("观察已停止", "AbortError");
      controller = new AbortController();
      const signal = controller.signal;
      const result = await observeRun({
        signal,
        open: async (cursor, emit) => {
          const token = await options.ticket();
          if (signal.aborted || destroyed)
            throw new DOMException("观察已停止", "AbortError");
          return streamEvents(
            options.endpoint +
              "/v1/runs/" +
              encodeURIComponent(run.runId) +
              "/events",
            () => token,
            cursor,
            emit,
            signal,
          );
        },
        readStatus: () =>
          request(
            "/v1/runs/" + encodeURIComponent(run.runId),
            undefined,
            signal,
            decodeRunStatus,
          ),
        onConnection: (state) => {
          widget.connection.textContent =
            state === "reconnecting"
              ? "连接恢复中，正在核对运行状态；临时片段可能缺失。"
              : "连接已恢复；临时片段可能缺失，最终以完整结果为准。正在处理 ▍";
        },
        onEvent: (e) => {
          const data = record(e.data);
          if (e.type === "WAITING_APPROVAL") void showApprovals(run.runId);
          if ((e.type === "CHUNK" || e.type === "chunk") && !receivedFinal) {
            text += eventText(e.data) || "";
            if (!frame)
              frame = requestAnimationFrame(() => {
                reply.innerHTML = safeMarkdown(text);
                frame = 0;
              });
          }
          if (e.type === "RUN_FINISHED" && data?.output !== undefined)
            applyFinal(data?.output);
        },
        onStatus: (snapshot) => {
          if (snapshot.status === "WAITING_APPROVAL")
            void showApprovals(run.runId);
          // Only a terminal snapshot is authoritative enough to stop accepting chunks.
          if (terminal(snapshot.status))
            applyFinal(
              snapshot.status === "FAILED" || snapshot.status === "CANCELLED"
                ? snapshot.error ??
                    snapshot.output ??
                    (snapshot.status === "FAILED" ? "运行失败" : "运行已取消")
                : snapshot.output,
            );
        },
      });
      if (result.status === "FAILED")
        widget.error.textContent =
          result.error?.message || "本次运行失败，请查看权威运行记录";
      for (const file of result.fileRefs || result.output?.fileRefs || []) {
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
            widget.error.textContent = e.message;
          } finally {
            button.disabled = false;
          }
        };
        widget.log.append(button);
      }
    } catch (e: any) {
      if (e.name !== "AbortError") {
        input.value = message;
        widget.error.textContent = e.message + "；可查看权威运行记录。";
      }
    } finally {
      cancelAnimationFrame(frame);
      running = false;
      widget.connection.textContent = "";
      widget.send.disabled = destroyed;
    }
  }

  function destroy() {
    destroyed = true;
    controller?.abort();
  }

  return { hydrate, send, destroy };
}
