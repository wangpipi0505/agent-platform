package cn.agentplatform.engine.harness.context;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * T2-9（台账 §7.3）：压缩三事件锁协议（主方案 §5.4）——
 * {@code compaction/start{turn}} → {@code compaction/summary{summary,shadowedRange,shadowedSeqs,tokens,model}}
 * → {@code user/message(surfaceOp=replace[startSeq..endSeq])} → {@code compaction/end}。
 *
 * <p>硬性约束：**工具调用与结果配对不可拆**（压缩区间以配对为边界——区间端点必须落在
 * 配对边界上，禁止半对）。锁先落库，崩溃留孤儿锁可检测（{@code detectOrphanLock}）。
 * 触发点：pre-step（压力阈值 compactionThreshold）与 request-error（窗口溢出恢复）。
 * 压缩本身的模型调用也落日志（可重建）。
 *
 * <p>模型切换窗口检查（dsh 0.2.2 语义吸收，Java 首期简化）：目标模型与当前路由不同且请求
 * 提交前，按目标模型窗口检查现有历史；需浓缩时用原模型压缩，目标请求在历史降到阈值下才
 * 继续；检查失败报 {@code MODEL_SWITCH_COMPACTION_FAILED} 并保留原请求头（错误码待登记
 * 接口稿 §2——contracts/error-codes.md §3 待登记区已列）。
 */
public class CompactionStore {

  private final SessionStore sessions;

  public CompactionStore(SessionStore sessions) {
    this.sessions = sessions;
  }

  /** 三态：IDLE（无活动压缩）| LOCKED（start 已落 end 未落）| DONE。 */
  public enum LockState { IDLE, LOCKED, DONE }

  /**
   * 压缩区间解析：给定消息投影的水位与配对关系，计算可安全压缩的区间
   * [startSeq..endSeq]——端点必须落在配对边界（最后一个完整 tool/call+tool/result 对之后），
   * 且保留最近 keepRecent 条消息不压缩（上下文连续性）。
   */
  public record ShadowRange(long startSeq, long endSeq, List<Long> shadowedSeqs) {}

  public ShadowRange resolveRange(String sessionId, long watermark, int keepRecent) {
    var events = sessions.read(sessionId, 0, 0);
    // 找最后一个完整配对边界（tool/result 及其后非配对中间事件均可用作边界）
    long lastPairBoundary = 0;
    Set<String> openCalls = new HashSet<>();
    for (SessionStore.SessionEvent e : events) {
      if (SessionEvents.TOOL_CALL.equals(e.type())) openCalls.add(e.data().path("callId").asText());
      else if (SessionEvents.TOOL_RESULT.equals(e.type())) openCalls.remove(e.data().path("callId").asText());
      if (openCalls.isEmpty()) lastPairBoundary = e.seq();
    }
    // 保留最近 keepRecent 条消息消息类事件不压缩
    int messageSeen = 0;
    long keepFrom = Long.MAX_VALUE;
    for (int i = events.size() - 1; i >= 0; i--) {
      var e = events.get(i);
      if (isMessage(e.type())) {
        messageSeen++;
        if (messageSeen > keepRecent) {
          keepFrom = e.seq();
          break;
        }
      }
    }
    long end = Math.min(lastPairBoundary, watermark);
    long start = 1;
    if (keepFrom != Long.MAX_VALUE) end = Math.min(end, Math.max(0, keepFrom - 1));
    if (end < start) return new ShadowRange(0, 0, List.of());
    // 收集遮蔽区间内的消息类 seq（投影 replace 摘除对象）
    List<Long> shadowed = new ArrayList<>();
    for (SessionStore.SessionEvent e : events)
      if (e.seq() >= start && e.seq() <= end && isMessage(e.type())) shadowed.add(e.seq());
    return new ShadowRange(start, end, shadowed);
  }

  /** 三事件锁协议执行：start→summary→replace→end（同原子事务落库——锁先落库，崩溃留孤儿可检测）。 */
  public Map<String, Object> compact(
      String sessionId, long turn, String summary, ShadowRange range, long tokens, String model) {
    // 配对边界校验（硬性约束）：区间内不得有未配对 tool/call
    validatePairBoundary(sessionId, range);
    long startSeq =
            sessions.append(
                    sessionId, null, SessionEvents.COMPACTION_START, Json.tree(Map.of("turn", turn)), null);
    sessions.append(
            sessionId,
            null,
            SessionEvents.COMPACTION_SUMMARY,
            Json.tree(
                    Map.of(
                            "summary",
                            summary,
                            "shadowedRange",
                            Map.of("startSeq", range.startSeq(), "endSeq", range.endSeq()),
                            "shadowedSeqs",
                            range.shadowedSeqs(),
                            "tokens",
                            tokens,
                            "model",
                            model)),
            null);
    // 替换投影：压缩摘要消息以 surfaceOp=replace 覆盖 [startSeq..endSeq]
    sessions.append(
            sessionId,
            null,
            SessionEvents.USER_MESSAGE,
            Json.tree(Map.of("content", summary)),
            Json.tree(
                    Map.of(
                            "op",
                            "replace",
                            "startSeq",
                            range.startSeq(),
                            "endSeq",
                            range.endSeq())));
    sessions.append(sessionId, null, SessionEvents.COMPACTION_END, Json.tree(Map.of("turn", turn)), null);
    return Map.of(
            "startSeq", startSeq,
            "shadowedRange", Map.of("startSeq", range.startSeq(), "endSeq", range.endSeq()),
            "shadowedCount", range.shadowedSeqs().size(),
            "tokens", tokens);
  }

  /** 孤儿锁检测（恢复路径调用）：LOCKED（start 有 end 无）→返回 true，恢复补写 compaction/end。 */
  public boolean detectOrphanLock(String sessionId) {
    boolean start = false, end = false;
    for (SessionStore.SessionEvent e : sessions.read(sessionId, 0, 0)) {
      if (SessionEvents.COMPACTION_START.equals(e.type())) start = true;
      if (SessionEvents.COMPACTION_END.equals(e.type())) end = true;
    }
    return start && !end;
  }

  /** 恢复补写：孤儿锁收口（compaction/end{turn=interrupted}）。 */
  public void closeOrphanLock(String sessionId, long turn) {
    if (detectOrphanLock(sessionId))
      sessions.append(
              sessionId,
              null,
              SessionEvents.COMPACTION_END,
              Json.tree(Map.of("turn", turn, "reason", "interrupted")),
              null);
  }

  /**
   * 模型切换窗口检查（dsh 0.2.2 吸收，Java 首期简化）：目标模型窗口与当前占用比对——
   * 现有历史 token 估算（usage 校准口径由 T2-10 token 启发式承接）超目标窗口→需浓缩；
   * 浓缩失败报 MODEL_SWITCH_COMPACTION_FAILED（错误码待登记接口稿 §2——error-codes.md §3）。
   *
   * @return true=窗口满足可直接切换；false=已执行浓缩可继续；抛出=浓缩失败（保留原请求头）
   */
  public boolean switchWindowCheck(
      String sessionId,
      long turn,
      long estimatedTokens,
      long targetWindow,
      java.util.function.Function<ShadowRange, Map<String, Object>> compactionFn,
      ShadowRange range) {
    if (estimatedTokens <= targetWindow) return true;
    try {
      compactionFn.apply(range);
      return false;
    } catch (RuntimeException failure) {
      throw new PlatformException(
              502,
              "MODEL_SWITCH_COMPACTION_FAILED",
              "切换模型窗口检查失败：需浓缩但压缩执行失败（保留原请求头）——" + failure.getMessage());
    }
  }

  /** 配对边界校验：[startSeq..endSeq] 内未配对 tool/call→PLATFORM 错误（压缩协议硬性约束）。 */
  private void validatePairBoundary(String sessionId, ShadowRange range) {
    Set<String> open = new HashSet<>();
    var events = sessions.read(sessionId, 0, 0);
    for (SessionStore.SessionEvent e : events) {
      if (e.seq() > range.endSeq()) break;
      if (e.seq() >= range.startSeq()) {
        if (SessionEvents.TOOL_CALL.equals(e.type()))
          open.add(e.data().path("callId").asText());
        else if (SessionEvents.TOOL_RESULT.equals(e.type())) open.remove(e.data().path("callId").asText());
      }
    }
    if (!open.isEmpty())
      throw new PlatformException(
              409,
              "COMPACTION_PAIRING_BROKEN",
              "压缩区间拆开工具配对（未闭合 callId=" + String.join(",", open) + "）——硬性约束禁止");
  }

  private static boolean isMessage(String type) {
    return SessionEvents.SYSTEM_MESSAGE.equals(type)
            || SessionEvents.USER_MESSAGE.equals(type)
            || SessionEvents.ASSISTANT_MESSAGE.equals(type);
  }
}
