package cn.agentplatform.engine.harness.loop;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * harness.loop Inbox 两阶段消费（H1-3；机制规格=主方案 §5.2" Inbox 双队列与持久确认消费"，
 * 字段权威=数据库稿 §4.1 {@code engine.agent_inbox}，事件负载=contracts/session-events.md §1.5）。
 *
 * <p>双队列：{@code NEXT_TURN}（turn 边界只取一条）与 {@code NEXT_STEP}（step 边界取全部，
 * 注入类消息不打断当前步）。持久化链路（主方案 §5.2）：
 * <ol>
 *   <li><b>入队</b>——先写 {@code inbox/enqueue} 事件再插投影行，两者同事务；
 *       {@code UNIQUE(session,queue,dedupe_key)} 幂等：重复入队返回既有 position，投影单行，
 *       事件目录允许重复追加（重投递在事实源留痕）；</li>
 *   <li><b>认领</b>——原子认领（FOR UPDATE SKIP LOCKED 风格），行写 {@code claimed_by} +
 *       {@code claim_generation}(接管递增) + 租约，<b>不删除</b>（两阶段之一）；</li>
 *   <li><b>确认消费</b>——{@code inbox/consume} 事件与该步模型请求材料
 *       （request/header+request/context）同一事务提交（调用方把本方法放进同一 trace.atomic）；
 *       事件、{@code consumed_at} 与物理删除同事务。必须匹配
 *       {@code claimed_by+claim_generation}——接管后代次递增，旧认领者确认被拒绝
 *       （409 INBOX_CLAIM_STALE，事务连事件一并回滚）。</li>
 * </ol>
 *
 * <p><b>恢复以事务提交为界（评审三轮 02），不设中间态</b>：提交前中断→行与事件一并回滚
 * （日志无该步请求材料），租约过期后按 {@code inbox/enqueue} 重新投递（同 dedupeKey 幂等）；
 * 提交后中断→消费事实已持久，恢复直接读取继续推进，重复确认幂等静默返回、不重复消费、不补写。
 * {@code requeueExpired} 不设显式方法：认领谓词内含"租约过期可再认领"，过期行由下一次
 * {@link #claim} 直接接管（代次递增），无需单独清理步骤。{@code agent_inbox} 是
 * {@code inbox/enqueue|consume} 事件的物化投影，可随时重建。
 *
 * <p>事务边界同 {@link SessionStore}（H1-2 先例）：默认经 {@link TraceStore} 复用调用方事务
 * （消费须与请求材料同事务）；独立事务场景用 (JdbcTemplate, PlatformTransactionManager) 构造器。
 * 事件写入复用 SessionStore——其 append 自带会话单写者锁（chat_session FOR UPDATE），顺带把
 * enqueue 的 position 分配与消费的删除串行化（consume/enqueue 均先取会话锁再触 inbox 行，
 * claim 只触 inbox 行，锁序无环）。
 */
public class Inbox {

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  // 委托构造器（this(...)）先置 null 再覆盖，故 trace/events 非 final（同 SessionStore 先例）。
  private TraceStore trace;
  private SessionStore events;

  /** 独立事务模式：enqueue/claim/consume 各自开 REQUIRED 事务（内部事件写入并入同事务）。 */
  public Inbox(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    var template = new TransactionTemplate(manager);
    template.setTimeout(5);
    this.tx = template;
    this.trace = null;
    this.events = new SessionStore(db, manager);
  }

  /**
   * 复用 TraceStore 的事务边界（照 SessionStore(TraceStore) 先例）：调用方在 trace.atomic 内
   * 调 enqueue/consume 时，事件、投影行与该事务内其余写入（如请求材料）一并提交或回滚。
   */
  public Inbox(TraceStore trace) {
    this(trace.db(), null);
    this.trace = trace;
    this.events = new SessionStore(trace);
  }

  /** 认领回执：{@code generation} 是确认消费凭据的一半（旧代次确认拒绝），leaseUntil 为租约到期。 */
  public record Claim(
      long position,
      String dedupeKey,
      JsonNode message,
      String owner,
      int generation,
      Instant leaseUntil) {}

  /**
   * 入队（幂等）。先写 {@code inbox/enqueue} 事件（负载 {queue,dedupeKey,message}）再插投影行，
   * 两者同事务；UNIQUE(session,queue,dedupe_key) 冲突即重复入队，返回既有 position、不产生新行。
   *
   * @return 队内序 position（重复入队=既有行的 position；同 (session,queue) 内单调递增）
   */
  public long enqueue(String sessionId, String queue, String dedupeKey, JsonNode message) {
    Objects.requireNonNull(message, "message");
    Long position =
        inTx(
            () -> {
              // ① 事实源先行：事件目录允许重复追加（重投递留痕），投影只物化唯一行。
              //    SessionStore.append 取会话单写者锁，串行化下方 position=max+1 分配。
              events.append(
                  sessionId,
                  null,
                  SessionEvents.INBOX_ENQUEUE,
                  Json.tree(Map.of("queue", queue, "dedupeKey", dedupeKey, "message", message)),
                  null);
              // ② 投影行：同 dedupeKey 幂等（ON CONFLICT DO NOTHING→读回既有 position）
              var inserted =
                  db.queryForList(
                      "insert into engine.agent_inbox(session_id,queue,dedupe_key,position,message)"
                          + " values(?,?,?,?,?::jsonb)"
                          + " on conflict (session_id,queue,dedupe_key) do nothing"
                          + " returning position",
                      Long.class,
                      sessionId,
                      queue,
                      dedupeKey,
                      nextPosition(sessionId, queue),
                      Json.write(message));
              if (!inserted.isEmpty()) return inserted.getFirst();
              return db.queryForObject(
                  "select position from engine.agent_inbox"
                      + " where session_id=? and queue=? and dedupe_key=?",
                  Long.class,
                  sessionId,
                  queue,
                  dedupeKey);
            });
    return position == null ? -1 : position;
  }

  /**
   * 原子认领：FOR UPDATE SKIP LOCKED 风格——可认领=未消费且(未认领或租约已过期)，按 position
   * FIFO 取最早一条；认领写 {@code claimed_by}+{@code claim_generation}(接管递增)+租约，
   * 不删除（两阶段之一）。无可认领行返回 null。
   */
  public Claim claim(String sessionId, String queue, String owner, int leaseSeconds) {
    Claim claim =
        inTx(
            () -> {
              var rows =
                  db.queryForList(
                      "with target as ("
                          + " select position from engine.agent_inbox"
                          + " where session_id=? and queue=? and consumed_at is null"
                          + " and (claimed_by is null or claim_lease_until is null"
                          + "      or claim_lease_until<=now())"
                          + " order by position limit 1 for update skip locked)"
                          + "update engine.agent_inbox i"
                          + " set claimed_by=?, claim_generation=i.claim_generation+1,"
                          + "     claim_lease_until=now()+make_interval(secs=>(?::double precision))"
                          + " from target"
                          + " where i.session_id=? and i.queue=? and i.position=target.position"
                          + " returning i.position,i.dedupe_key,i.message,i.claim_generation,"
                          + " i.claim_lease_until",
                      sessionId,
                      queue,
                      owner,
                      leaseSeconds,
                      sessionId,
                      queue);
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              return new Claim(
                  ((Number) row.get("position")).longValue(),
                  String.valueOf(row.get("dedupe_key")),
                  Json.read(String.valueOf(row.get("message")), JsonNode.class),
                  owner,
                  ((Number) row.get("claim_generation")).intValue(),
                  ((java.sql.Timestamp) row.get("claim_lease_until")).toInstant());
            });
    return claim;
  }

  /**
   * 确认消费（两阶段第二段）：写 {@code inbox/consume} 事件（负载
   * {queue,dedupeKey,consumedByStep}）+ 置 consumed_at + 物理删除行，事件与删除同事务；
   * 恢复语义=提交前中断随调用方事务一并回滚，提交后中断事实已持久。
   *
   * <p>须匹配 {@code claimed_by+claim_generation}（claim 回执带回的代次；同 owner 再认领后代次
   * 也会递增，故认领凭据必须两列齐配）：不匹配抛 409 INBOX_CLAIM_STALE 且整个事务回滚
   * （不留事件）。行不存在（已消费删除/从未入队）幂等静默返回——重复消费不抛、不留痕。
   */
  public void consume(
      String sessionId, String queue, String dedupeKey, String owner, int claimGeneration,
      long consumedByStep) {
    inTx(
        () -> {
          var rows =
              db.queryForList(
                  "select claimed_by,claim_generation,consumed_at from engine.agent_inbox"
                      + " where session_id=? and queue=? and dedupe_key=?",
                  sessionId,
                  queue,
                  dedupeKey);
          if (rows.isEmpty()) return null; // 幂等：已消费删除（或从未入队）——不抛不留痕
          var row = rows.getFirst();
          String claimedBy =
                  row.get("claimed_by") == null ? null : String.valueOf(row.get("claimed_by"));
          int generation = ((Number) row.get("claim_generation")).intValue();
          if (row.get("consumed_at") != null
                  || !Objects.equals(claimedBy, owner)
                  || generation != claimGeneration)
            throw new PlatformException(
                    409,
                    "INBOX_CLAIM_STALE",
                    "旧认领者确认拒绝：期望 " + owner + "#" + claimGeneration
                            + "，实际 " + claimedBy + "#" + generation);
          // 事实源先行（与 enqueue 对称）：事件在事务内先落；随后标记+删除。
          events.append(
                  sessionId,
                  null,
                  SessionEvents.INBOX_CONSUME,
                  Json.tree(
                          Map.of(
                                  "queue", queue,
                                  "dedupeKey", dedupeKey,
                                  "consumedByStep", consumedByStep)),
                  null);
          // 防御性围栏再核对：诊断与写入之间被接管（租约过期 claim 不需会话锁）时拒绝——
          // 事件已在本事务内，抛出即连事件一并回滚，不留半截消费。
          int marked =
                  db.update(
                          "update engine.agent_inbox set consumed_at=now()"
                              + " where session_id=? and queue=? and dedupe_key=?"
                              + " and claimed_by=? and claim_generation=? and consumed_at is null",
                          sessionId,
                          queue,
                          dedupeKey,
                          owner,
                          claimGeneration);
          if (marked != 1)
            throw new PlatformException(
                    409, "INBOX_CLAIM_STALE", "确认消费与认领状态不一致：认领已被接管");
          db.update(
                  "delete from engine.agent_inbox where session_id=? and queue=? and dedupe_key=?",
                  sessionId,
                  queue,
                  dedupeKey);
          return null;
        });
  }

  /** 同 (session,queue) 内队内序：max+1（消费即物理删除，position 允许回收复用）。 */
  private long nextPosition(String sessionId, String queue) {
    Long max =
            db.queryForObject(
                    "select coalesce(max(position),0) from engine.agent_inbox"
                        + " where session_id=? and queue=?",
                    Long.class,
                    sessionId,
                    queue);
    return (max == null ? 0 : max) + 1;
  }

  /** 仅在事务边界内执行：trace.atomic（同事务模式）或 tx.execute（独立事务模式）。 */
  private <T> T inTx(Supplier<T> work) {
    return trace != null ? trace.atomic(work) : tx.execute(status -> work.get());
  }
}
