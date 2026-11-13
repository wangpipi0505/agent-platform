package cn.agentplatform.engine.harness.session;

import cn.agentplatform.common.Json;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * harness.session Job 事件对与 owner 生命周期联动（A3-7；机制规格=主方案 §5.9，事件负载=
 * contracts/session-events.md §1.5）。
 *
 * <p><b>包归属（session 而非 loop）</b>：与 {@link TodoStore} 同理——本类只经
 * {@link SessionStore} 追加事件、只从事件流 fold 派生投影，<b>无物化投影表</b>（对照
 * Inbox 属 harness.loop 的理由：拥有 agent_inbox 物化表与两阶段消费协议）。owner 生命周期
 * 联动虽由 loop 侧触发（owner 终止时调用 {@link #cancelByOwner}），机制本体是
 * {@code job/registered}/{@code job/settled} 事件对的收口，事实源在 session_event。
 *
 * <p><b>事件对语义</b>：
 * <ol>
 *   <li>{@link #register}——追加 {@code job/registered}{jobId,owner,payload}；job_* 读/停
 *       工具为 P2 不在本卡，登记事件即受理事实；</li>
 *   <li>{@link #settle}——追加 {@code job/settled}{jobId,outcome,owner} 收口；
 *       <b>幂等</b>：同 jobId 已 settled → 静默返回不重复收口（重放也只产生一条事件）；</li>
 *   <li>{@link #cancelByOwner}——owner 终止联动：该 owner 名下全部未 settled 的 job 逐个补
 *       {@code job/settled{outcome=CANCELLED_OWNER_GONE}}（事件对收口，不留孤儿活跃 job），
 *       其余 owner 的 job 不受影响。</li>
 * </ol>
 *
 * <p><b>投影无独立表</b>：{@link #project} fold 出 registered/settled/active——同 jobId
 * 重复登记/收口按"最后一态获胜"，active=registered−settled 差集（登记序）；重放任意次
 * 结果一致。outcome 为开集字符串（契约未闭集，{@link #OUTCOME_CANCELLED_OWNER_GONE} 是
 * 本卡定义的联动字面量）。
 *
 * <p><b>检查-写入原子性</b>：settle 幂等判断与 cancelByOwner 的活跃集计算都在事务内
 * <b>先取会话单写者锁</b>（chat_session FOR UPDATE，与 SessionStore.append 同一把锁，
 * 锁序一致无环）再读事件流，并发重复 settle/取消在锁下串行，不会产生双份收口事件。
 * 事务边界同 {@link Inbox} 先例：默认经 TraceStore 复用调用方事务；独立事务场景用
 * (JdbcTemplate, PlatformTransactionManager) 构造器。
 */
public class JobTracker {

  /** owner 终止联动的收口结果（主方案 §5.9：owner 终止连带取消）。 */
  public static final String OUTCOME_CANCELLED_OWNER_GONE = "CANCELLED_OWNER_GONE";

  /** 登记事实（同 jobId 重复登记最后一态获胜；payload log-only 透传）。 */
  public record JobRegistration(String jobId, String owner, JsonNode payload) {}

  /** 收口事实（同 jobId 重复收口最后一态获胜；幂等保证正常路径只写一条）。 */
  public record JobSettlement(String jobId, String outcome, String owner) {}

  /** 投影三面：registered/settled 全量（按 jobId 去重最后一态获胜），active=registered−settled。 */
  public record JobProjection(
          List<JobRegistration> registered, List<JobSettlement> settled, List<JobRegistration> active) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  // 委托构造器（this(...)）先置 null 再覆盖，故 trace/events 非 final（同 SessionStore/Inbox 先例）。
  private TraceStore trace;
  private SessionStore events;

  /** 独立事务模式：register/settle/cancelByOwner 各自开 REQUIRED 事务（事件写入并入同事务）。 */
  public JobTracker(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    var template = new TransactionTemplate(manager);
    template.setTimeout(5);
    this.tx = template;
    this.trace = null;
    this.events = new SessionStore(db, manager);
  }

  /** 复用调用方事务（照 Inbox(TraceStore) 先例）：收口可与同事务其余写入一并提交/回滚。 */
  public JobTracker(TraceStore trace) {
    this(trace.db(), null);
    this.trace = trace;
    this.events = new SessionStore(trace);
  }

  /** 追加 {@code job/registered}{jobId,owner,payload}。重复登记=新事件（事实源留痕）。 */
  public void register(String sessionId, String jobId, String owner, JsonNode payload) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(jobId, "jobId");
    Objects.requireNonNull(owner, "owner");
    var data = Json.object();
    data.put("jobId", jobId);
    data.put("owner", owner);
    if (payload != null) data.set("payload", payload);
    else data.putNull("payload");
    events.append(sessionId, null, SessionEvents.JOB_REGISTERED, data, null);
  }

  /**
   * 追加 {@code job/settled}{jobId,outcome,owner} 收口。幂等：同 jobId 已 settled →
   * 静默返回（不抛、不重复落事件）。幂等判断与追加在同一事务的会话写锁内完成
   * （见类注释"检查-写入原子性"）。
   */
  public void settle(String sessionId, String jobId, String outcome, String owner) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(jobId, "jobId");
    Objects.requireNonNull(outcome, "outcome");
    inTx(
        () -> {
          lockSession(sessionId);
          if (isSettled(sessionId, jobId)) return null; // 幂等：已收口静默返回，不重复落事件
          var data = Json.object();
          data.put("jobId", jobId);
          data.put("outcome", outcome);
          if (owner != null) data.put("owner", owner);
          else data.putNull("owner");
          events.append(sessionId, null, SessionEvents.JOB_SETTLED, data, null);
          return null;
        });
  }

  /**
   * owner 终止联动：该 owner 名下<b>全部未 settled</b> 的 active job 逐个补
   * {@code job/settled{outcome=CANCELLED_OWNER_GONE,owner=登记 owner}}——事件对收口，
   * 不留孤儿活跃 job；其余 owner 的 job 不受影响。全部收口在同一事务内完成。
   */
  public void cancelByOwner(String sessionId, String owner) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(owner, "owner");
    inTx(
        () -> {
          lockSession(sessionId);
          // 写锁已持，fold 出活跃集后逐个收口（并发写者被锁挡在事务提交之后）。
          for (JobRegistration reg : fold(events.read(sessionId, 0, 0)).active()) {
            if (!owner.equals(reg.owner())) continue;
            events.append(
                    sessionId,
                    null,
                    SessionEvents.JOB_SETTLED,
                    Json.tree(
                            Map.of(
                                    "jobId", reg.jobId(),
                                    "outcome", OUTCOME_CANCELLED_OWNER_GONE,
                                    "owner", reg.owner())),
                    null);
          }
          return null;
        });
  }

  /** 从事件流 fold 出 {registered, settled, active} 投影，重放任意次结果一致。 */
  public JobProjection project(String sessionId) {
    return fold(events.read(sessionId, 0, 0));
  }

  /**
   * 纯折叠：registered/settled 按 jobId 去重（最后一态获胜，LinkedHashMap 保登记序），
   * active=registered−settled 差集（登记序）。同 fold 服务全量投影与任意区间重放。
   */
  public static JobProjection fold(List<SessionStore.SessionEvent> events) {
    var registered = new LinkedHashMap<String, JobRegistration>();
    var settled = new LinkedHashMap<String, JobSettlement>();
    for (SessionStore.SessionEvent event : events) {
      JsonNode data = event.data();
      String jobId = text(data, "jobId");
      if (jobId == null || jobId.isBlank()) continue; // 防御：无 jobId 的目录内事件不参与本投影
      switch (event.type()) {
        case SessionEvents.JOB_REGISTERED ->
                registered.put(jobId, new JobRegistration(jobId, text(data, "owner"), data.path("payload")));
        case SessionEvents.JOB_SETTLED ->
                settled.put(jobId, new JobSettlement(jobId, text(data, "outcome"), text(data, "owner")));
        default -> {}
      }
    }
    var active = new ArrayList<JobRegistration>();
    for (Map.Entry<String, JobRegistration> entry : registered.entrySet())
      if (!settled.containsKey(entry.getKey())) active.add(entry.getValue());
    return new JobProjection(
            List.copyOf(registered.values()), List.copyOf(settled.values()), List.copyOf(active));
  }

  /** 幂等判断：同 jobId 是否已有 job/settled 事件（与投影同一 fold 语义，单一事实源）。 */
  private boolean isSettled(String sessionId, String jobId) {
    return fold(events.read(sessionId, 0, 0)).settled().stream()
            .anyMatch(s -> s.jobId().equals(jobId));
  }

  /**
   * 契约③：per-session 单写者锁——与 SessionStore.append 同锁（chat_session FOR UPDATE），
   * settle 幂等检查与 cancelByOwner 活跃集计算在锁内进行，检查-写入不撕裂。
   */
  private void lockSession(String sessionId) {
    db.queryForObject(
            "select 1 from engine.chat_session where public_id=? for update", Long.class, sessionId);
  }

  /** 仅在事务边界内执行：trace.atomic（同事务模式）或 tx.execute（独立事务模式）。 */
  private <T> T inTx(Supplier<T> work) {
    return trace != null ? trace.atomic(work) : tx.execute(status -> work.get());
  }

  /** null/缺失读作 null，其余原样（防 NullNode.asText() 返回 "null" 字面量的坑）。 */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }
}
