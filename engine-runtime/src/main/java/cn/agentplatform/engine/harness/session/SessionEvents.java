package cn.agentplatform.engine.harness.session;

/**
 * 会话事件目录常量（H1-1，台账 §4.1 命名规范：事件类型字符串常量集中本类，禁止散落字面量）。
 *
 * <p>目录 27 类，唯一权威=数据库稿 §4.1 {@code session_event.type} CHECK 枚举 +
 * contracts/session-events.md §1；扩展一律走新迁移追加 CHECK 值并同步本类。
 */
public final class SessionEvents {

  private SessionEvents() {}

  /** 生命周期。 */
  public static final String TURN_START = "turn/start";
  public static final String TURN_END = "turn/end";
  public static final String STEP_START = "step/start";
  public static final String STEP_END = "step/end";

  /** 消息（assistant/attempt 记录尝试，不入模型历史）。 */
  public static final String SYSTEM_MESSAGE = "system/message";
  public static final String USER_MESSAGE = "user/message";
  public static final String ASSISTANT_MESSAGE = "assistant/message";
  public static final String ASSISTANT_ATTEMPT = "assistant/attempt";

  /** 工具（调用-结果配对是压缩与裁剪的硬边界）。 */
  public static final String TOOL_CALL = "tool/call";
  public static final String TOOL_RESULT = "tool/result";

  /** 请求（D2：模型可见即已记录）。 */
  public static final String REQUEST_HEADER = "request/header";
  public static final String REQUEST_CONTEXT = "request/context";

  /** 平台扩展（log-only，整值替换或事件对）。 */
  public static final String PLAN_MODE = "plan/mode";
  public static final String APPROVAL_ASKED = "approval/asked";
  public static final String APPROVAL_DECIDED = "approval/decided";
  public static final String APPROVAL_POLICY = "approval/policy";
  public static final String TODO_WRITE = "todo/write";
  public static final String COMPACTION_START = "compaction/start";
  public static final String COMPACTION_SUMMARY = "compaction/summary";
  public static final String COMPACTION_END = "compaction/end";
  public static final String SUBAGENT_REGISTERED = "subagent/registered";
  public static final String SUBAGENT_SETTLED = "subagent/settled";
  public static final String JOB_REGISTERED = "job/registered";
  public static final String JOB_SETTLED = "job/settled";
  public static final String INBOX_ENQUEUE = "inbox/enqueue";
  public static final String INBOX_CONSUME = "inbox/consume";
  public static final String WORKING_DIRECTORY_CHANGE = "working-directory/change";

  /** turn/end 收尾原因（主方案 §5.1）。 */
  public static final String TURN_REASON_INTERRUPTED = "interrupted";

  /** 恢复补写 tool/result 的固定标注（契约④：只 UNKNOWN，禁止 error 表述失败）。 */
  public static final boolean SYNTHETIC = true;
  public static final String SEMANTIC_UNKNOWN = "UNKNOWN";
  public static final String SYNTHETIC_VISIBLE_TEXT = "结果未确认（待对账）";
}
