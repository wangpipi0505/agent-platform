package cn.agentplatform.app.domain;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ONB-01：业务接入模板的不可变修订。
 *
 * <p>模板是能力包与既有契约引用的“包装”，不携带连接参数、凭据或可执行脚本；它只登记
 * 能力包精确版本、体验修订与接入档案三元组（业务系统/环境/等级），因此不会形成第二套
 * 连接或模板引擎，也不会扩大底层 Schema、守卫与执行能力。
 */
public record OnboardingTemplate(
        String code,
        int revision,
        String kind,
        String name,
        String description,
        long sourcePackageVersionId,
        String sourcePackageSha256,
        String appCode,
        String experienceAppCode,
        int experienceRev,
        String bizSystemCode,
        String environment,
        String mode,
        String declarationSha256) {

    public static final Set<String> KINDS =
            Set.of("QUERY", "FILE_ANALYSIS", "CONTROLLED_WRITE");
    /** 本子步只开放只读查询模板；其余两类等待 INT-02 文件链路与受控写入确认机制。 */
    public static final Set<String> SELECTABLE_KINDS = Set.of("QUERY");
    public static final String OPEN_KIND_REASON =
            "文件分析与受控写入模板等待 INT-02 文件链路与确认机制，本子步只开放只读查询模板";

    public static String declarationSha(
            String kind,
            String name,
            String description,
            long sourcePackageVersionId,
            String sourcePackageSha256,
            String appCode,
            String experienceAppCode,
            int experienceRev,
            String bizSystemCode,
            String environment,
            String mode) {
        return Digest.sha256(Json.canonical(List.of(
                "onboarding-template.v1",
                kind,
                name,
                description,
                sourcePackageVersionId,
                sourcePackageSha256,
                appCode,
                experienceAppCode,
                experienceRev,
                bizSystemCode,
                environment,
                mode)));
    }

    public Map<String, Object> experienceRef() {
        return Map.of("appCode", experienceAppCode, "experienceRev", experienceRev);
    }

    public Map<String, Object> integrationRef() {
        return Map.of(
                "bizSystemCode", bizSystemCode,
                "environment", environment,
                "mode", mode);
    }
}
