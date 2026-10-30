package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.SkillAdapters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 非权威的单 JVM 只读叶结果缓存；PG claim/执行事实、当前 IAM 和已声明身份映射仍逐次完成。 */
@Component
public class ReadOnlyToolCache {
    private final boolean enabled;
    private final long ttlNanos, maxTotalBytes, maxEntryBytes;
    private final int maxEntries;
    private final ObjectProvider<CurrentAuthorityClient> authority;
    private final LongSupplier nanoTime;
    // ponytail: 最多 256 条的本地 LRU；只在短临界区复制/计数，不在此锁内做 HTTP。
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long totalBytes;
    private record Entry(SkillAdapters.Raw raw, long created, long bytes) {}

    public ReadOnlyToolCache(
            @Value("${agent.skill.read-only-cache.enabled:false}") boolean enabled,
            @Value("${agent.skill.read-only-cache.ttl-ms:30000}") long ttlMs,
            @Value("${agent.skill.read-only-cache.max-entries:256}") int maxEntries,
            @Value("${agent.skill.read-only-cache.max-total-bytes:8388608}") long maxTotalBytes,
            @Value("${agent.skill.read-only-cache.max-entry-bytes:65536}") long maxEntryBytes,
            ObjectProvider<CurrentAuthorityClient> authority, ObjectProvider<LongSupplier> clocks) {
        if (ttlMs < 1 || ttlMs > 30000 || maxEntries < 1 || maxEntries > 256
                || maxTotalBytes < 1 || maxTotalBytes > 8388608 || maxEntryBytes < 1
                || maxEntryBytes > 65536 || maxEntryBytes > maxTotalBytes)
            throw new IllegalArgumentException("READ_ONLY_CACHE_LIMIT_INVALID");
        this.enabled = enabled; this.ttlNanos = ttlMs * 1_000_000L; this.maxEntries = maxEntries;
        this.maxTotalBytes = maxTotalBytes; this.maxEntryBytes = maxEntryBytes;
        this.authority = authority; this.nanoTime = clocks.getIfAvailable(() -> System::nanoTime);
    }

    /** 只有本地前置阶段可构造；远端同名业务码不能冒充“尚未发送”。 */
    public static final class BeforeDispatchFailure extends SkillAdapters.Failure {
        private BeforeDispatchFailure(String code) {
            super("NO_PERMISSION", false, "当前授权复核未通过，本次未发送业务请求", code, null);
        }
    }

    public SkillAdapters.Observation execute(Map<String, Object> contract, Map<String, Object> arguments,
            Runnable mappingCheck, Supplier<SkillAdapters.Raw> load) {
        if (!enabled || !eligible(contract) || !delegated() || authority.getIfAvailable() == null)
            return new SkillAdapters.Observation(load.get(), Map.of());

        final HttpCurrentAuthorityClient.RetainedAuthorization proof;
        final String key;
        try {
            var before = AuthorityContext.current();
            // 必须走接收端原 retained refresh；不能拿公开 VerifiedAuthority 或旧 JWT 合成当前许可。
            proof = AuthorityContext.requireInitialFor(Actor.current(), before.call().resource());
            var fresh = proof.current();
            var secured = SkillRules.guard(fresh.actor(), SkillCatalog.object(contract.get("guard")), arguments);
            if (!Json.canonical(secured).equals(Json.canonical(arguments)))
                throw new IllegalArgumentException("CURRENT_GUARD_NARROWED_ARGUMENTS");
            key = Digest.sha256(Json.canonical(List.of(contract.get("skillVersionId"), contract.get("sha256"),
                    Digest.sha256(Json.canonical(contract)), fresh.actor().subject().mappingKey(),
                    fresh.authorizationVersion(), fresh.call(), fresh.operation().ceilingSha256(), arguments)));
        } catch (PlatformException denied) {
            // retained refresh 失败已让当前 frame 失效，绝不 catch 后回落到旧身份继续调用。
            throw new BeforeDispatchFailure(denied.code());
        } catch (IllegalArgumentException denied) {
            throw new BeforeDispatchFailure("READ_ONLY_CACHE_CURRENT_GUARD_DENIED");
        }
        // EX23 的 PG 当前行核验即使命中也照常执行；失败沿原可信本地类型传播。
        mappingCheck.run();
        var cached = get(key);
        if (cached != null) {
            requireBeforeDispatch(proof);
            return new SkillAdapters.Observation(cached, Map.of("readOnlyCacheHit", true));
        }
        requireBeforeDispatch(proof);
        var result = load.get();
        // 业务已发送后不再标记 BeforeDispatchFailure；过期/失效结果不进入缓存。
        AuthorityContext.requireBound(proof);
        if (inlineSuccess(result)) put(key, result);
        return new SkillAdapters.Observation(result, Map.of());
    }

    private static void requireBeforeDispatch(HttpCurrentAuthorityClient.RetainedAuthorization proof) {
        try { AuthorityContext.requireBound(proof); }
        catch (PlatformException denied) { throw new BeforeDispatchFailure(denied.code()); }
    }

    private static boolean eligible(Map<String, Object> contract) {
        var version = Json.tree(contract.get("skillVersionId"));
        return "HTTP".equals(contract.get("type")) && "SYNC".equals(contract.get("invocationKind"))
                && Set.of("PUBLISHED", "ARCHIVED").contains(Objects.toString(contract.get("status"), ""))
                && version.isIntegralNumber() && version.canConvertToLong() && version.asLong() > 0
                && contract.get("sha256") instanceof String digest && digest.matches("[a-fA-F0-9]{64}")
                && contract.get("execution") instanceof Map<?, ?> execution
                && Boolean.TRUE.equals(execution.get("readonly")) && Boolean.FALSE.equals(execution.get("sideEffect"))
                // 无外部凭据撤销证明，先只收明确公共 API；旧缺省 BEARER 也绕过。
                && "NONE".equals(contract.get("authScheme"))
                && Objects.toString(contract.get("authRef"), "").isBlank();
    }

    private static boolean delegated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && "delegated".equals(jwt.getClaimAsString("authority_mode"));
    }

    private static boolean inlineSuccess(SkillAdapters.Raw raw) {
        return raw != null && raw.structured() != null && raw.fileRefs() != null && raw.fileRefs().isEmpty()
                && raw.processFact() == null && raw.validation() != null
                && SkillAdapters.Validation.OK.equals(raw.validation().mode());
    }

    private static SkillAdapters.Raw copy(SkillAdapters.Raw raw) {
        return new SkillAdapters.Raw(raw.structured().deepCopy(), List.of(), raw.summary(),
                raw.ontologyMeta() == null ? null : raw.ontologyMeta().deepCopy(),
                raw.cardPayloadJson(), raw.validation(), null);
    }

    private synchronized SkillAdapters.Raw get(String key) {
        expire(nanoTime.getAsLong());
        var entry = entries.get(key);
        return entry == null ? null : copy(entry.raw());
    }

    private synchronized void put(String key, SkillAdapters.Raw raw) {
        // 字节预算为实际 UTF-8 结果材料 + 固定长度键；条目数另限，不宣称测量 JVM 对象头。
        var saved = copy(raw);
        long bytes = Json.write(saved).getBytes(StandardCharsets.UTF_8).length + key.length();
        if (bytes > maxEntryBytes) return;
        long now = nanoTime.getAsLong(); expire(now);
        var previous = entries.remove(key); if (previous != null) totalBytes -= previous.bytes();
        while (!entries.isEmpty() && (entries.size() >= maxEntries || totalBytes + bytes > maxTotalBytes)) {
            var oldest = entries.entrySet().iterator(); var entry = oldest.next();
            totalBytes -= entry.getValue().bytes(); oldest.remove();
        }
        entries.put(key, new Entry(saved, now, bytes)); totalBytes += bytes;
    }

    private void expire(long now) {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next().getValue(); long age = now - entry.created();
            if (age < 0 || age >= ttlNanos) { totalBytes -= entry.bytes(); iterator.remove(); }
        }
    }
}
