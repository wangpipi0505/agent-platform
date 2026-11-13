package cn.agentplatform.engine.harness.session;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * harness.session 会话事件日志存储（H1-1；机制规格=主方案 §5.1，字段=数据库稿 §4.1）。
 *
 * <p>移植 dsh SessionHandle 四条契约（contracts/session-events.md §2）：
 * <ol>
 *   <li><b>read 只返回连续合法前缀</b>——读到 seq 断口即停，撕裂尾不返回（append 在事务内保证
 *       连续，此处作为防御性兜底与直写撕裂的检测）；</li>
 *   <li><b>append 首事件 seq 必须等于已存 next-seq</b>——调用方可携带期望 seq，不符即
 *       409 SESSION_SEQ_MISMATCH；事务插入天然满足单调连续；</li>
 *   <li><b>单写者所有权</b>——append 事务内先对 chat_session 行 FOR UPDATE，per-session
 *       串行化写者；</li>
 *   <li><b>恢复补写</b>——缺失 tool/result 一律 {@code synthetic=true、semantic=UNKNOWN}，
 *       模型可见文本"结果未确认（待对账）"，禁止写 error 表述失败；未闭合 turn 补
 *       {@code turn/end{interrupted}}（补写语义与四态对账的边界见主方案 §5.8）。</li>
 * </ol>
 *
 * <p>事件负载（data/surface_op）形状与白名单投影见 contracts/session-events.md；投影只消费
 * 消息类事件，assistant/attempt 不入模型历史。本类不含事件产生方（loop/tools），只承载
 * 追加、读取、增量折叠与恢复补写——写入纪律（同事务边界）由调用方遵守（D2）。
 */
public class SessionStore {

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private cn.agentplatform.engine.infra.TraceStore trace;

  public SessionStore(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(5);
    this.trace = null;
  }

  /**
   * 复用 TraceStore 的事务边界（H1-2）：append 走 trace.atomic，与调用方（如 ChatSessions 的
   * 轮次预留事务）共享同一事务——满足"chat_turn 创建与 turn/start+user/message 同事务"（主方案 §5.1）。
   */
  public SessionStore(cn.agentplatform.engine.infra.TraceStore trace) {
    this(trace.db(), null);
    this.trace = trace;
  }

  /** 会话事件行（occurredAt 为库端落库时刻；data/surface_op 为 JSON 树）。 */
  public record SessionEvent(long seq, String type, Instant occurredAt, JsonNode data, JsonNode surfaceOp) {}

  /** 模型可见消息（sourceSeq 用于压缩 replace 区间摘除）。 */
  public record Message(long sourceSeq, String role, JsonNode content) {}

  /** deriveMessages 的可累积投影状态：lastSeq 为已消费事件水位（增量游标）。 */
  public record MessageProjection(long lastSeq, List<Message> messages) {

    public static MessageProjection empty() {
      return new MessageProjection(0, new ArrayList<>());
    }
  }

  /**
   * 契约②③：追加事件。{@code expectedSeq} 非空时必须等于库内 next-seq（越 seq 拒绝）；
   * 空时仅要求内部连续。返回实际写入的 seq。
   */
  public long append(String sessionId, Integer expectedSeq, String type, JsonNode data, JsonNode surfaceOp) {
    Long seq =
            trace != null
                    ? trace.atomic(
                            () -> appendLocked(sessionId, expectedSeq, type, data, surfaceOp))
                    : tx.execute(
                            status -> appendLocked(sessionId, expectedSeq, type, data, surfaceOp));
    return seq == null ? -1 : seq;
  }

  /** 仅在活动事务内执行（trace.atomic 或 tx.execute 已开启边界）。 */
  private Long appendLocked(
      String sessionId, Integer expectedSeq, String type, JsonNode data, JsonNode surfaceOp) {
    // 契约③：per-session 单写者锁——写事务内锁定会话行，写者串行。
    db.queryForObject(
            "select 1 from engine.chat_session where public_id=? for update", Long.class, sessionId);
    long next = nextSeq(sessionId);
    if (expectedSeq != null && expectedSeq.longValue() != next)
      throw new PlatformException(
              409,
              "SESSION_SEQ_MISMATCH",
              "会话事件 seq 不连续：期望 " + expectedSeq + "，实际下一序号 " + next);
    db.update(
            "insert into engine.session_event(session_id,seq,type,data,surface_op)"
                + " values(?,?,?,?::jsonb,?::jsonb)",
            sessionId,
            next,
            type,
            Json.write(data),
            surfaceOp == null ? null : Json.write(surfaceOp));
    return next;
  }

  /** 当前事件水位（最大 seq；空会话为 0）。检查点与 H1-9 快照水位共用。 */
  public long watermark(String sessionId) {
    Long max =
            db.queryForObject(
                    "select coalesce(max(seq),0) from engine.session_event where session_id=?",
                    Long.class,
                    sessionId);
    return max == null ? 0 : max;
  }

  /**
   * 契约①：读取 {@code afterSeq}（排他）之后的连续合法前缀；遇 seq 断口即停——撕裂尾不返回。
   * {@code limit <= 0} 视为不限制。
   */
  public List<SessionEvent> read(String sessionId, long afterSeq, int limit) {
    var rows =
            db.queryForList(
                    "select seq,type,occurred_at,data,surface_op from engine.session_event"
                        + " where session_id=? and seq>? order by seq",
                    sessionId,
                    afterSeq);
    List<SessionEvent> out = new ArrayList<>();
    long expected = afterSeq;
    for (var row : rows) {
      long seq = ((Number) row.get("seq")).longValue();
      if (seq != expected + 1) break; // 撕裂尾：断口后的行一律不返回
      out.add(
              new SessionEvent(
                      seq,
                      String.valueOf(row.get("type")),
                      ((Timestamp) row.get("occurred_at")).toInstant(),
                      Json.read(String.valueOf(row.get("data")), JsonNode.class),
                      row.get("surface_op") == null
                              ? null
                              : Json.read(String.valueOf(row.get("surface_op")), JsonNode.class)));
      expected = seq;
      if (limit > 0 && out.size() >= limit) break;
    }
    return out;
  }

  /**
   * deriveMessages 增量 fold：以 {@code base.lastSeq} 为游标读取增量并折叠到消息投影。
   * 只投影消息类事件（system/user/assistant message）；surface_op=replace{startSeq,endSeq}
   * 先摘除区间内的既有消息，本事件再按消息类追加；assistant/attempt 与其余类型不入投影。
   */
  public MessageProjection deriveMessages(String sessionId, MessageProjection base) {
    var state = base == null ? MessageProjection.empty() : base;
    return fold(state, read(sessionId, state.lastSeq(), 0));
  }

  /** 纯折叠：同一 fold 同时服务会话重放与增量续读（H1-4 影子验证共用入口）。 */
  public static MessageProjection fold(MessageProjection base, List<SessionEvent> events) {
    var state = base == null ? MessageProjection.empty() : base;
    long lastSeq = state.lastSeq();
    var messages = new ArrayList<>(state.messages());
    for (SessionEvent event : events) {
      lastSeq = event.seq();
      var surface = event.surfaceOp();
      if (surface != null && "replace".equals(surface.path("op").asText())) {
        long start = surface.path("startSeq").asLong();
        long end = surface.path("endSeq").asLong();
        messages.removeIf(m -> m.sourceSeq() >= start && m.sourceSeq() <= end);
      }
      String role = messageRole(event.type());
      if (role != null) messages.add(new Message(event.seq(), role, event.data()));
    }
    return new MessageProjection(lastSeq, messages);
  }

  /**
   * 契约④（恢复补写）：为未决的 tool/call 补写合成结果——synthetic=true、semantic=UNKNOWN、
   * 模型可见"结果未确认（待对账）"，禁止 error（防模型把未决当失败而重提交已受理操作）。
   */
  public void synthesizeMissingToolResult(String sessionId, String callId, JsonNode meta) {
    var data = Json.object();
    data.put("callId", callId);
    data.put("synthetic", SessionEvents.SYNTHETIC);
    data.put("semantic", SessionEvents.SEMANTIC_UNKNOWN);
    data.set("result", Json.tree(java.util.Map.of("text", SessionEvents.SYNTHETIC_VISIBLE_TEXT)));
    if (meta != null) data.set("meta", meta);
    append(sessionId, null, SessionEvents.TOOL_RESULT, data, null);
  }

  /**
   * 契约④（恢复补写）：turn 已开启且未闭合时补 {@code turn/end{reason=interrupted}}；
   * 已闭合的 turn 不重复补写。
   */
  public void closeInterruptedTurn(String sessionId, long turn) {
    Integer open =
            db.queryForObject(
                    "select (exists(select 1 from engine.session_event where session_id=? and"
                        + " type='"
                        + SessionEvents.TURN_START
                        + "' and data->>'turn'=?)"
                        + " and not exists(select 1 from engine.session_event where session_id=?"
                        + " and type='"
                        + SessionEvents.TURN_END
                        + "' and data->>'turn'=?))::int",
                    Integer.class,
                    sessionId,
                    String.valueOf(turn),
                    sessionId,
                    String.valueOf(turn));
    if (open != null && open == 1)
      append(
              sessionId,
              null,
              SessionEvents.TURN_END,
              Json.tree(java.util.Map.of("turn", turn, "reason", SessionEvents.TURN_REASON_INTERRUPTED)),
              null);
  }

  private long nextSeq(String sessionId) {
    Long max =
            db.queryForObject(
                    "select coalesce(max(seq),0) from engine.session_event where session_id=?",
                    Long.class,
                    sessionId);
    return (max == null ? 0 : max) + 1;
  }

  private static String messageRole(String type) {
    return switch (type) {
      case SessionEvents.SYSTEM_MESSAGE -> "system";
      case SessionEvents.USER_MESSAGE -> "user";
      case SessionEvents.ASSISTANT_MESSAGE -> "assistant";
      default -> null;
    };
  }
}
