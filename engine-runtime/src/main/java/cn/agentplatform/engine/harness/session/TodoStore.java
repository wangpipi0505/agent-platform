package cn.agentplatform.engine.harness.session;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * harness.session Todo 整表替换事件与投影（A3-7；机制规格=主方案 §5.9，事件负载=
 * contracts/session-events.md §1.5）。
 *
 * <p><b>包归属（session 而非 loop）</b>：本类与 {@link JobTracker} 同为 session_event 的
 * log-only 派生状态——只经 {@link SessionStore} 追加事件、只从事件流 fold 派生投影，
 * <b>无物化投影表</b>（对照 Inbox 属 harness.loop 的理由：拥有 agent_inbox 物化表与
 * 两阶段消费协议）。写入方虽在 loop/工具侧，机制本体属于"事件日志及其投影"，
 * 与 SessionStore.deriveMessages 同一职责域。
 *
 * <p><b>整值替换语义</b>：{@link #write} 每次追加一条 {@code todo/write} 事件，负载为
 * {@code {items:[{id,content,status}]}} 全量新表——重复写=新事件（不做 diff、不合并）；
 * {@link #project} fold 时<b>最后一个 todo/write 事件即当前态</b>，重放任意次结果一致
 * （事件流是唯一事实源，无独立投影表可漂移）。任务工作台展示计划进度即读此投影。
 *
 * <p><b>三态枚举（log-only 无表约束，校验在写入侧）</b>：status ∈
 * {@code PENDING|IN_PROGRESS|COMPLETED}（{@link Status}），非法值抛 400
 * {@code TODO_STATUS_INVALID} 且不落事件；CHECK 约束不随本卡扩展（数据库稿目录已含
 * todo/write，负载形状由写入侧守护）。id/content 仅要求非 null（log-only 透传），
 * 整表可为空列表（清空是合法新态）。
 */
public class TodoStore {

  /** todo 三态（主方案 §5.9；契约 §1.5：整表替换事件，无独立状态机约束）。 */
  public enum Status {
    PENDING,
    IN_PROGRESS,
    COMPLETED
  }

  /** 单条 todo（status 为三态枚举字符串，写入侧校验；id/content log-only 透传）。 */
  public record TodoItem(String id, String content, String status) {

    public TodoItem {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(content, "content");
    }
  }

  private static final Set<String> STATUSES =
          Arrays.stream(Status.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  private final SessionStore events;

  /** 独立事务模式：write 的 append 自开 REQUIRED 事务。 */
  public TodoStore(JdbcTemplate db, PlatformTransactionManager manager) {
    this.events = new SessionStore(db, manager);
  }

  /** 复用调用方事务（照 SessionStore(TraceStore) 先例）：可与同事务其余写入一并提交/回滚。 */
  public TodoStore(TraceStore trace) {
    this.events = new SessionStore(trace);
  }

  /**
   * 整表替换：校验后追加一条 {@code todo/write} 事件（负载 {@code {items:[...]}} 全量新表）。
   * 重复写=新事件；非法 status 抛 400 {@code TODO_STATUS_INVALID}（事件不落库）；
   * 空列表=清空整表（合法新态）。
   */
  public void write(String sessionId, List<TodoItem> items) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(items, "items");
    for (TodoItem item : items) {
      Objects.requireNonNull(item, "items 元素不得为 null");
      if (item.status() == null || !STATUSES.contains(item.status()))
        throw new PlatformException(
                400,
                "TODO_STATUS_INVALID",
                "todo 状态必须是三态枚举之一 " + STATUSES + "，实际：" + item.status());
    }
    var data = Json.object();
    data.set("items", Json.tree(items));
    events.append(sessionId, null, SessionEvents.TODO_WRITE, data, null);
  }

  /** 从事件流 fold 出当前 todo 表（最后一个 todo/write 即当前态），重放任意次结果一致。 */
  public List<TodoItem> project(String sessionId) {
    return fold(events.read(sessionId, 0, 0));
  }

  /** 纯折叠（照 SessionStore.fold 先例）：同一 fold 服务全量投影与任意区间重放。 */
  public static List<TodoItem> fold(List<SessionStore.SessionEvent> events) {
    JsonNode items = null;
    for (SessionStore.SessionEvent event : events)
      if (SessionEvents.TODO_WRITE.equals(event.type())) items = event.data().path("items");
    if (items == null || !items.isArray()) return List.of();
    List<TodoItem> out = new ArrayList<>();
    for (JsonNode item : items)
      out.add(new TodoItem(text(item, "id"), text(item, "content"), text(item, "status")));
    return List.copyOf(out);
  }

  /** null/缺失读作 null，其余原样（防 NullNode.asText() 返回 "null" 字面量的坑）。 */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }
}
