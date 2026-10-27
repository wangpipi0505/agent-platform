package cn.agentplatform.app.domain;

import cn.agentplatform.common.PlatformException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发布门禁的显式豁免：ADMIN 对"缺少合格评测报告"的风险接受记录。
 *
 * <p>不变量：豁免只覆盖授予时声明的 (应用, 草稿修订, 候选摘要) 三元组，草稿任何再次保存都会改变修订
 * 或摘要，从而自动失效；理由与授予人强制落库，用于事后追溯。恢复窗口最多24小时：豁免是一次发布决策的
 * 风险接受，不是长期免检开关，撤销后立即不再生效。
 */
public record PublishWaiver(
        String id,
        String tenantId,
        String appCode,
        long draftRev,
        String candidateSha256,
        String reason,
        String approvedBy,
        Instant approvedAt,
        Instant expiresAt,
        Instant revokedAt) {
    public static final Duration MAX_TTL = Duration.ofHours(24);
    public static final int MIN_REASON = 10;
    public static final int MAX_REASON = 512;

    /** 校验授予请求。字段形状与时长上限在此收口，服务层只负责持久化与租户隔离。 */
    public static PublishWaiver grant(
            String id,
            String tenantId,
            String appCode,
            Map<String, Object> body,
            String approvedBy,
            Instant now) {
        if (!(body.get("reason") instanceof String raw)) throw invalid("豁免理由必须为字符串");
        String reason = raw.trim();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON)
            throw invalid("豁免理由需" + MIN_REASON + "至" + MAX_REASON + "字");
        if (!(body.get("draftRev") instanceof Number rev)
                || !(rev instanceof Integer || rev instanceof Long)
                || rev.longValue() < 1)
            throw invalid("豁免必须绑定有效的草稿修订号");
        if (!(body.get("candidateSha256") instanceof String sha)
                || !sha.matches("[0-9a-f]{64}"))
            throw invalid("豁免必须绑定64位小写十六进制候选摘要");
        if (!(body.get("expiresAt") instanceof String rawExpiry))
            throw invalid("豁免到期时间必须为ISO-8601时间字符串");
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(rawExpiry.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw invalid("豁免到期时间必须为ISO-8601时间字符串");
        }
        // 到期时间必须落在(now, now+24h]：既能覆盖一次发布会话，也不会变成长期免检。
        if (!expiresAt.isAfter(now)) throw invalid("豁免到期时间必须晚于当前时间");
        if (expiresAt.isAfter(now.plus(MAX_TTL))) throw invalid("豁免有效期不能超过24小时");
        return new PublishWaiver(
                id, tenantId, appCode, rev.longValue(), sha, reason, approvedBy, now, expiresAt, null);
    }

    /** 撤销或过期后立即失效；发布与提交都以此判定，避免15分钟报告窗口绕过豁免时限。 */
    public boolean active(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean covers(long rev, String sha) {
        return draftRev == rev && candidateSha256.equals(sha);
    }

    public String status(Instant now) {
        if (revokedAt != null) return "REVOKED";
        return active(now) ? "ACTIVE" : "EXPIRED";
    }

    public Map<String, Object> view(Instant now) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("appCode", appCode);
        result.put("draftRev", draftRev);
        result.put("candidateSha256", candidateSha256);
        result.put("reason", reason);
        result.put("approvedBy", approvedBy);
        result.put("approvedAt", approvedAt.toString());
        result.put("expiresAt", expiresAt.toString());
        result.put("revokedAt", revokedAt == null ? null : revokedAt.toString());
        result.put("status", status(now));
        return result;
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "PUBLISH_WAIVER_INVALID", message);
    }
}
