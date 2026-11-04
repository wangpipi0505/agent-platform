import {renderAuthority} from './authority';
import {readRunAuthority,emptyAuthority} from '../../shared/server-authority';
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
import { restoreSession, sessionConfigReleaseId } from "../../shared/session";
import {
  requireApplicationType,
  declaredSchema,
  renderDeclaredInputs,
  collectInputs,
  renderExperience,
  renderExperienceFallback,
  validatePageContext,
  experiencePath,
  experienceProjection,
} from "./experience";
import { bubble, prependWarning } from "./widget";
import { publishSession } from "./host-events";
import type {
  HostEventBridge,
  HostEventStatus,
} from "../../shared/assistant-host-events";
import type {
  AssistantEvent,
  AssistantEventPayload,
  AssistantOpenOptions,
  AssistantOptions,
  ExtraInput,
  PageContext,
  Widget,
} from "./types";

type Emit = <E extends AssistantEvent>(
  event: E,
  payload: AssistantEventPayload[E],
) => void;

function hostStatusOf(status: unknown): HostEventStatus {
  switch (status) {
    case "SUCCEEDED":
    case "SUCCESS":
    case "COMPLETED":
    case "FINISHED":
      return "SUCCEEDED";
    case "FAILED":
      return "FAILED";
    case "CANCELLED":
      return "CANCELLED";
    default:
      return "UNKNOWN";
  }
}

function hostFileRefs(result: any) {
  const refs = result?.fileRefs ?? result?.output?.fileRefs ?? [];
  if (!Array.isArray(refs)) return [];
  return refs
    .filter(
      (f: any) =>
        Number.isInteger(f?.artifactId) &&
        f.artifactId > 0 &&
        typeof f?.name === "string" &&
        f.name,
    )
    .slice(0, 16)
    .map((f: any) => ({
      artifactId: f.artifactId as number,
      name: String(f.name).slice(0, 256),
      ...(Number.isInteger(f.sizeBytes) && f.sizeBytes >= 0
        ? { sizeBytes: f.sizeBytes as number }
        : {}),
    }));
}

function hostRecordRefs(result: any) {
  const refs = result?.businessRecordRefs;
  if (!Array.isArray(refs)) return [];
  return refs
    .filter(
      (r: any) =>
        typeof r?.source === "string" &&
        r.source &&
        typeof r?.recordId === "string" &&
        r.recordId,
    )
    .slice(0, 16)
    .map((r: any) => ({
      source: String(r.source).slice(0, 64),
      recordId: String(r.recordId).slice(0, 128),
    }));
}

/**
 * Session and run orchestration. Shared modules keep the protocol, approval,
 * artifact and observer state machines; this layer only wires them to the
 * widget, the host ticket callback and the validated page context.
 */
export function createRuntime(
  options: AssistantOptions,
  widget: Widget,
  initialSessionId: string,
  emit: Emit,
  hostEvents?: HostEventBridge,
) {
  let controller: AbortController | undefined;
  let running = false;
  let destroyed = false;
  let sessionId = initialSessionId;
  let declaration: any;
  let hydrated = false;
  let experienceLoaded = false;
  let experienceLoading: Promise<void> | undefined;
  let experienceConfig = "";
  let pageContext: PageContext = options.context
    ? validatePageContext(options.context)
    : {};
  const extraInputs = new Map<string, ExtraInput>();

  // INT-06：宿主只能“提议”配置版本；会话实际使用的版本一律以服务端返回值为准。
  // 提议入口：init 选项（JS 宿主）或页面 URL 的 configReleaseId；两者都不决定可用性。
  const configProposal = (): string => {
    const explicit = (options as AssistantOptions & { configReleaseId?: unknown })
      .configReleaseId;
    if (typeof explicit === "string" && explicit.trim()) return explicit.trim();
    return (
      new URLSearchParams(location.search).get("configReleaseId") || experienceConfig
    ).trim();
  };
  let sessionConfig = "";
  let configNoticeShown = false;
  const note = (text: string) => {
    const line = document.createElement("p");
    line.className = "experience-note";
    line.textContent = text;
    widget.log.append(line);
    widget.log.scrollTop = widget.log.scrollHeight;
  };
  // 只读服务端返回的会话引用；恢复时若与本地提议不一致，以服务端为准并如实提示。
  const adoptServerConfig = (session: any, notify: boolean) => {
    sessionConfig = sessionConfigReleaseId(session);
    const proposed = configProposal();
    if (!notify || configNoticeShown || !proposed) return;
    if (sessionConfig && proposed !== sessionConfig) {
      configNoticeShown = true;
      note("应用配置已更新，本会话继续使用创建时的配置版本（以服务端返回为准）。");
    } else if (!sessionConfig) {
      configNoticeShown = true;
      note("该会话未固定配置版本，继续按会话创建时的应用版本执行（以服务端返回为准）。");
    }
  };

  /** Shared authorized request helper; HTTP status stays authoritative. */
  async function call(
    path: string,
    body?: any,
    signal?: AbortSignal,
    decoder?: (text: string) => any,
  ) {
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
  }

  function useComposer(text: string) {
    widget.input.value = text;
    widget.input.focus();
  }

  function loadExperience(): Promise<void> {
    if (experienceLoaded) return Promise.resolve();
    return experienceLoading ??= readExperience().finally(() => { experienceLoading = undefined; });
  }
  /** 本应用/固定配置才可呈现；授权失败不能记作已加载成功，允许重新出票后再打开。 */
  async function readExperience() {
    try {
      const token = await options.ticket();
      if (destroyed) return;
      const response = await fetch(
        options.endpoint + experiencePath(options.appCode, options.environment),
        { headers: { Authorization: "Bearer " + token } },
      );
      const envelope: any = await response.json().catch(() => null);
      const projection = experienceProjection(response.status, envelope, options.appCode, sessionConfig || configProposal());
      if (!projection) {
        renderExperienceFallback(widget);
        experienceLoaded = true;
        return;
      }
      if (destroyed) return;
      experienceConfig = projection.configReleaseId;
      renderExperience(widget, projection.description, useComposer);
      experienceLoaded = true;
    } catch (failure) {
      renderExperienceFallback(widget);
      widget.error.textContent = failure instanceof Error ? failure.message : "体验读取失败，请重试";
    }
  }

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
    try{const semantic=await request('/v1/runs/'+encodeURIComponent(runPublicId)+'/semantic-results');
      if(!destroyed)renderAuthority(element,readRunAuthority(runPublicId,old,semantic));
    }catch(failure){const view=emptyAuthority();view.error=failure instanceof Error?failure.message:'语义回执读取失败';if(!destroyed)renderAuthority(element,view);}
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
      adoptServerConfig(restored.session, true);
      hydrated = true;
      emit("session", { sessionId, appCode: options.appCode });
    } catch (e: any) {
      widget.error.textContent = e.message;
      hostEvents?.emit("error", {
        error: {
          code: "ASSISTANT_RESTORE_FAILED",
          message: String(e?.message ?? "恢复会话失败").slice(0, 512),
          retryable: true,
        },
      });
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
    widget.experience.hidden = true;
    const user = bubble(widget, input.value);
    const message = input.value;
    input.value = "";
    const reply = bubble(widget, "");
    let text = "";
    let receivedFinal = false;
    let frame = 0;
    let runId = "";
    const applyFinal = (value: unknown) => {
      receivedFinal = true;
      cancelAnimationFrame(frame);
      frame = 0;
      const answer = finalAnswer(value);
      text = answer.text;
      const authority=reply.querySelector('[data-run-authority]');
      reply.innerHTML = safeMarkdown(text);
      if(authority)reply.append(authority);
      if (answer.warning) prependWarning(reply, answer.warning);
    };
    try {
      if (!sessionId) await loadExperience();
      if (!declaration) {
        if (sessionId) {
          const restored = await restoreSession(
            call,
            options.appCode,
            sessionId,
          );
          declaration = restored.declaration;
          for (const turn of restored.session.turns || [])
            await appendHistory(call, turn.run_public_id, user);
          adoptServerConfig(restored.session, true);
        } else
          declaration = await call(
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
        { ...pageContext },
        extraInputs,
      );
      if (declaration.type === "CHAT" && !sessionId) {
        const proposed = configProposal();
        const session = await call(
          "/v1/apps/" + encodeURIComponent(options.appCode) + "/sessions",
          proposed ? { configReleaseId: proposed } : {},
        );
        sessionId = session.sessionId;
        adoptServerConfig(session, false);
        hydrated = true;
        publishSession(sessionId);
        emit("session", { sessionId, appCode: options.appCode });
      }
      async function showApprovals(id: string) {
        try {
          const rows = await call(
            "/v1/runs/" + encodeURIComponent(id) + "/approvals",
          );
          widget.root.querySelectorAll(".approval").forEach((n) => n.remove());
          for (const row of rows) {
            hostEvents?.emit("approval-required", {
              runId: id,
              status: "WAITING_APPROVAL",
              structured: {
                nodeId: String(row.nodeInstanceId || row.nodeDefId || ""),
              },
            });
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
                  await call(
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
      // 轮次只沿用会话已固定的引用，不再携带提议；FLOW 运行则允许一次提议。
      const runBody: any = { inputs, requestId: crypto.randomUUID() };
      const flowProposal = configProposal();
      if (declaration.type !== "CHAT" && flowProposal)
        runBody.configReleaseId = flowProposal;
      const run = await call(
        "/v1/apps/" +
          encodeURIComponent(options.appCode) +
          (declaration.type === "CHAT"
            ? "/sessions/" + encodeURIComponent(sessionId) + "/turns"
            : "/runs"),
        runBody,
      );
      if (destroyed) throw new DOMException("观察已停止", "AbortError");
      runId = run.runId;
      hostEvents?.emit("run-started", {
        runId,
        ...(sessionId ? { sessionId } : {}),
        ...(sessionConfig ? { configReleaseId: sessionConfig } : {}),
        // 运行已受理，但业务效果仍以服务端最终事实为准。
        structured: { businessEffect: "UNKNOWN" },
      });
      controller = new AbortController();
      const signal = controller.signal;
      const result = await observeRun({
        signal,
        open: async (cursor, emitEvent) => {
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
            emitEvent,
            signal,
          );
        },
        readStatus: async () => {
          const snapshot=await call('/v1/runs/'+encodeURIComponent(run.runId),undefined,signal,decodeRunStatus);
          let view=readRunAuthority(run.runId,snapshot,undefined);
          try { const semantic=await call('/v1/runs/'+encodeURIComponent(run.runId)+'/semantic-results',undefined,signal);
            view=readRunAuthority(run.runId,snapshot,semantic);
          } catch(failure) { view=emptyAuthority();view.error=failure instanceof Error?failure.message:'语义回执读取失败'; }
          if(!destroyed&&!signal.aborted)renderAuthority(reply,view);
          return snapshot;
        },
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
                const authority=reply.querySelector('[data-run-authority]');
                reply.innerHTML = safeMarkdown(text);
                if(authority)reply.append(authority);
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
      const status = hostStatusOf(result.status);
      const fileRefs = hostFileRefs(result);
      const recordRefs = hostRecordRefs(result);
      hostEvents?.result({
        runId,
        ...(sessionId ? { sessionId } : {}),
        ...(sessionConfig ? { configReleaseId: sessionConfig } : {}),
        status,
        structured: {
          // 消息/模型输出不是“业务已提交”的依据；业务效果只能由宿主按引用反查。
          businessEffect: "UNKNOWN",
          answer: text.slice(0, 2048),
        },
        ...(fileRefs.length ? { fileRefs } : {}),
        ...(recordRefs.length ? { businessRecordRefs: recordRefs } : {}),
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
        hostEvents?.emit("error", {
          ...(runId ? { runId } : {}),
          error: {
            code:
              typeof e.code === "string" && e.code
                ? e.code.slice(0, 64)
                : "ASSISTANT_RUN_FAILED",
            message: String(e.message ?? "助手请求失败").slice(0, 512),
            retryable: false,
          },
        });
      }
    } finally {
      cancelAnimationFrame(frame);
      running = false;
      widget.connection.textContent = "";
      widget.send.disabled = destroyed;
    }
  }

  function open(openOptions?: AssistantOpenOptions) {
    if (destroyed) throw Error("助手已销毁，请重新初始化");
    if (openOptions?.pageContext !== undefined)
      pageContext = validatePageContext(openOptions.pageContext);
    widget.drawer.style.display = "flex";
    emit("open", { pageContext: { ...pageContext }, appCode: options.appCode });
    hostEvents?.emit("opened", {
      ...(sessionId ? { sessionId } : {}),
      ...(sessionConfig ? { configReleaseId: sessionConfig } : {}),
      structured: { pageContext: { ...pageContext } },
    });
    void hydrate().then(() => loadExperience());
  }

  function close() {
    if (destroyed) return;
    widget.drawer.style.display = "none";
    emit("close", { appCode: options.appCode });
    hostEvents?.emit("closed", {
      ...(sessionId ? { sessionId } : {}),
      ...(sessionConfig ? { configReleaseId: sessionConfig } : {}),
    });
  }

  function updateContext(context: PageContext) {
    if (destroyed) throw Error("助手已销毁，请重新初始化");
    pageContext = validatePageContext(context);
  }

  function destroy() {
    if (destroyed) return;
    destroyed = true;
    controller?.abort();
    extraInputs.clear();
    emit("destroy", { appCode: options.appCode });
  }

  return { hydrate, send, open, close, updateContext, destroy };
}
