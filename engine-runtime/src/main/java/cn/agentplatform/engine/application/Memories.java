package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.MemoryRepository;
import cn.agentplatform.engine.infra.RuntimeClients;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * A3-4（接口稿 §5.4 / 主方案 §6.5）：跨会话记忆——CRUD、状态增量（DRAFT 审批）与读写策略。
 *
 * <p>策略来源（两版本口径）：显式保存携带 sessionId 时按会话钉住的发布快照判定（会话行内
 * snapshot 即创建时固化的发布版本）；资产管理与不带 sessionId 的路径用当前生效发布版本
 * （{@code /internal/apps/{code}/published}）。快照缺失或无 memoryPolicy 时一律收窄为仅
 * PRIVATE（接口稿：未配置时 read/writeScopes 均仅含 PRIVATE）。
 *
 * <p>不变量：①可见性裁决先于任何检索（列表/单条/检索都以 tenant+可见性谓词收口，REVOKED
 * 恒不返回——删除先撤销检索资格）；②USER 恒不写 SHARED（403 SCOPE_WRITE_DENIED），SHARED
 * 内容更新与审批归 ADMIN；③策略收窄不剥夺撤销权——条目所有人（或共享层 ADMIN）始终可
 * REVOKED 原条目，旧发布任务从下一次检索起不再引用；④更新一律 expectedVersion CAS。
 */
@Service
public class Memories {
    private final TraceStore store;
    private final MemoryRepository repository;
    /** 资产管理/无 sessionId 路径的当前发布快照来源；dev/test 组装可为 null（按无策略收窄）。 */
    private final RuntimeClients clients;

    public Memories(TraceStore store, MemoryRepository repository, RuntimeClients clients) {
        this.store = store;
        this.repository = repository;
        this.clients = clients;
    }

    /** 测试/无网关组装：发布快照来源缺失时按"快照无 policy→仅 PRIVATE"收窄。 */
    public Memories(TraceStore store, MemoryRepository repository) {
        this(store, repository, null);
    }

    // ------------------------------------------------------------------
    // 策略（memoryPolicy：readScopes/writeScopes/kinds/topK）
    // ------------------------------------------------------------------

    /**
     * memoryPolicy 语义（接口稿 §5.4 memoryPolicy 段）：readScopes/writeScopes 分别控制任务
     * 检索与写入范围，未配置时仅含 PRIVATE；请求读写权限=工作区策略∩当前主体授权。kinds
     * 限制可写种类（空=不限）。快照形状容错：memoryPolicy 在顶层或 manifest.memoryPolicy。
     */
    public record MemoryPolicy(Set<String> readScopes, Set<String> writeScopes, Set<String> kinds, int topK) {

        public static MemoryPolicy restricted() {
            return new MemoryPolicy(Set.of("PRIVATE"), Set.of("PRIVATE"), Set.of(), 5);
        }

        public static MemoryPolicy from(JsonNode snapshot) {
            JsonNode absent = Json.read("{}");
            JsonNode policy = snapshot == null ? absent : snapshot.path("memoryPolicy");
            if (!policy.isObject())
                policy = snapshot == null ? absent : snapshot.path("manifest").path("memoryPolicy");
            if (!policy.isObject()) return restricted();
            return new MemoryPolicy(
                    scopes(policy.path("readScopes")),
                    scopes(policy.path("writeScopes")),
                    kinds(policy.path("kinds")),
                    topK(policy.path("topK")));
        }

        /** 未配置/空集/越界值收窄为仅 PRIVATE；越界项丢弃。 */
        private static Set<String> scopes(JsonNode raw) {
            Set<String> values = new LinkedHashSet<>();
            if (raw.isArray()) raw.forEach(v -> values.add(v.asText()));
            values.retainAll(Set.of("PRIVATE", "SHARED"));
            return values.isEmpty() ? Set.of("PRIVATE") : Set.copyOf(values);
        }

        private static Set<String> kinds(JsonNode raw) {
            Set<String> values = new LinkedHashSet<>();
            if (raw.isArray()) raw.forEach(v -> values.add(v.asText()));
            values.retainAll(Set.of("PREFERENCE", "EXPERIENCE"));
            return values.isEmpty() ? Set.of() : Set.copyOf(values);
        }

        private static int topK(JsonNode raw) {
            if (raw.isInt() && raw.intValue() >= 1 && raw.intValue() <= 50) return raw.intValue();
            return 5;
        }

        /** 主体读取范围∩策略：PRIVATE 为本人主体权，SHARED 须策略显式允许。 */
        public boolean canRead(String scope) {
            return readScopes.contains(scope);
        }

        /** 写入范围∩策略（SHARED 写入还须 ADMIN 角色，在调用方裁决）。 */
        public boolean canWrite(String scope) {
            return writeScopes.contains(scope);
        }
    }

    /**
     * 两版本策略解析：sessionId 携带→按会话钉住版本（会话行 snapshot 固化的发布快照）；
     * 否则→当前生效发布版本（资产管理用当前生效版，接口稿 §5.4 策略版本段）。
     * 发布快照不可得（未发布/停用/调用失败）按"快照无 policy→仅 PRIVATE"收窄——策略收窄
     * 不剥夺归属权（撤销仍可达），不放大读取范围。
     */
    public MemoryPolicy policyFor(String code, Actor actor, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return MemoryPolicy.from(TraceStore.json(ownedSession(code, sessionId, actor).get("snapshot")));
        }
        return MemoryPolicy.from(publishedSnapshot(code, actor));
    }

    /** 当前生效发布快照；不可得时返回 null（收窄为仅 PRIVATE）。 */
    protected JsonNode publishedSnapshot(String code, Actor actor) {
        if (clients == null) return null;
        try {
            return clients.get("app", "/internal/apps/" + code + "/published", actor);
        } catch (PlatformException unavailable) {
            // 未发布/停用/版本切换竞态：按无策略收窄，撤销权等归属操作不受阻（§5.4 策略收窄行）。
            return null;
        }
    }

    private Map<String, Object> ownedSession(String code, String sessionId, Actor actor) {
        var rows =
                store.db()
                        .queryForList(
                                "select * from engine.chat_session where public_id=? and app_code=?"
                                    + " and tenant_id=? and principal_id=?",
                                sessionId,
                                code,
                                actor.tenantId(),
                                actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "SESSION_NOT_FOUND", "会话不存在");
        return rows.getFirst();
    }

    // ------------------------------------------------------------------
    // 列表（先权限过滤再检索；列表不含正文）
    // ------------------------------------------------------------------

    /**
     * GET /v1/apps/{code}/memories：先权限过滤（tenant+workspace+本人 PRIVATE+策略允许的
     * SHARED）再 scope/status/tags/q 过滤，最后分页（page/pageSize 默认 1/20、上限 100）。
     * 列表项不含正文——正文经单条接口读取（接口稿 §5.4）。REVOKED 恒不返回（先撤销检索
     * 资格）；显式 status=DRAFT 面向确认中心待办（可见性同读范围，审批权另按角色裁决）。
     */
    public Map<String, Object> list(
            String code,
            String scope,
            String status,
            String tags,
            String q,
            String sessionId,
            String page,
            String pageSize,
            Actor actor) {
        if (scope != null && !scope.isBlank() && !Set.of("PRIVATE", "SHARED").contains(scope))
            throw new PlatformException(400, "MEMORY_SCOPE_FILTER_INVALID", "scope 仅支持 PRIVATE 或 SHARED");
        String effectiveStatus = status == null || status.isBlank() ? "ACTIVE" : status;
        if (!Set.of("ACTIVE", "DRAFT").contains(effectiveStatus))
            throw new PlatformException(
                    400, "MEMORY_STATUS_FILTER_INVALID", "status 仅支持 ACTIVE 或 DRAFT（REVOKED 已退出检索不再返回）");
        List<String> tagsFilter = parseTagFilter(tags);
        int pageNo = parseBounded(page, "page", 1, 1000000, "MEMORY_PAGE_INVALID");
        int size = parseBounded(pageSize, "pageSize", 1, 100, "MEMORY_PAGE_INVALID");
        MemoryPolicy policy = policyFor(code, actor, sessionId);
        boolean sharedVisible = policy.canRead("SHARED");
        long total =
                repository.count(
                        actor.tenantId(), code, actor.principalId(), sharedVisible, blankToNull(scope),
                        effectiveStatus, tagsFilter, blankToNull(q));
        var rows =
                repository.list(
                        actor.tenantId(), code, actor.principalId(), sharedVisible, blankToNull(scope),
                        effectiveStatus, tagsFilter, blankToNull(q), size, (long) (pageNo - 1) * size);
        var taskIds = new LinkedHashSet<Long>();
        for (var row : rows)
            if (row.get("source_task_id") != null) taskIds.add(((Number) row.get("source_task_id")).longValue());
        var publicIds = repository.taskPublicIds(taskIds);
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) items.add(summary(row, publicIds));
        var data = new LinkedHashMap<String, Object>();
        data.put("memories", items);
        data.put("page", pageNo);
        data.put("pageSize", size);
        data.put("total", total);
        return data;
    }

    // ------------------------------------------------------------------
    // 单条（归属核验→读范围复核→返回含正文）
    // ------------------------------------------------------------------

    /**
     * GET /v1/apps/{code}/memories/{memoryId}：先核验主体（tenant=JWT）、智能体（workspace_code
     * 匹配，不符按嵌套路径归属不匹配 404）、条目归属与读范围（PRIVATE=本人；SHARED=策略
     * readScopes），存在但无权读取 403 并记拒绝审计（接口稿 §6）。DRAFT 可被其可见者预览
     * （确认通道正文预览）；REVOKED 已删除，404。
     */
    public Map<String, Object> get(String code, String memoryId, String sessionId, Actor actor) {
        MemoryPolicy policy = policyFor(code, actor, sessionId);
        var row = repository.findById(actor.tenantId(), memoryId);
        if (row == null || !code.equals(row.get("workspace_code"))) throw notFound(memoryId);
        String status = String.valueOf(row.get("status"));
        if ("REVOKED".equals(status)) throw notFound(memoryId);
        requireReadable(row, actor, policy, "READ", code);
        return detail(row, repository.taskPublicIds(
                row.get("source_task_id") == null
                        ? Set.of()
                        : Set.of(((Number) row.get("source_task_id")).longValue())));
    }

    // ------------------------------------------------------------------
    // 显式保存（requestId 幂等）
    // ------------------------------------------------------------------

    /**
     * POST /v1/apps/{code}/memories：USER 显式保存（source_kind=USER_EXPLICIT）→生效即 ACTIVE。
     * 携带 sessionId 时核验会话归属并按其钉住版本 policy 判写范围；否则按当前生效版本。
     * USER 写 SHARED 一律拒绝（403 SCOPE_WRITE_DENIED，接口稿：USER 不能写入 SHARED）。
     * 幂等：memory_id=tenant|workspace|owner|requestId 摘要派生——同号同参重放原条目，
     * 同号异参 409 REQUEST_REPLAY（照 evaluationTurn 的确定性 ID+行锁先例，无需额外表）。
     */
    public Map<String, Object> create(String code, JsonNode body, Actor actor) {
        String requestId = body.path("requestId").asText(null);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            throw new PlatformException(400, "MEMORY_REQUEST_ID_INVALID", "必须提供 requestId（≤128 字）");
        JsonNode contentNode = body.path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank())
            throw new PlatformException(400, "MEMORY_CONTENT_REQUIRED", "正文不能为空");
        String content = contentNode.asText().trim();
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new PlatformException(400, "MEMORY_CONTENT_TOO_LARGE", "正文超过 64KB 上限（库稿 §4.4）");
        String kind = body.path("kind").asText(null);
        if (!Set.of("PREFERENCE", "EXPERIENCE").contains(kind))
            throw new PlatformException(400, "MEMORY_KIND_INVALID", "kind 仅支持 PREFERENCE 或 EXPERIENCE");
        String scope = body.path("scope").asText("PRIVATE");
        if (!Set.of("PRIVATE", "SHARED").contains(scope))
            throw new PlatformException(400, "MEMORY_SCOPE_INVALID", "scope 仅支持 PRIVATE 或 SHARED");
        List<String> tags = parseTags(body.path("tags"));
        Instant expiresAt = parseExpiresAt(body);
        Long sourceTaskId = resolveSourceTask(actor.tenantId(), body.path("sourceTaskId"));
        String sessionId = body.path("sessionId").asText(null);
        if (sessionId != null && sessionId.isBlank()) sessionId = null;

        MemoryPolicy policy = policyFor(code, actor, sessionId);
        if (!policy.kinds().isEmpty() && !policy.kinds().contains(kind))
            throw new PlatformException(
                    400, "MEMORY_KIND_NOT_ALLOWED", "当前发布策略不允许写入记忆种类 " + kind);
        requireWriteScope(scope, policy, actor);

        String memoryId =
                "m_"
                        + Digest.sha256(
                                        actor.tenantId() + "|" + code + "|" + actor.principalId() + "|" + requestId)
                                .substring(0, 32);
        return store.atomic(
                () -> {
                    // 确定性 ID（tenant|workspace|owner|requestId 摘要）：先行锁探测——已存在即重放
                    // 分支逐参数比对（同参返原条目/异参 409）；不存在则首插。同号并发首插以 PK
                    // 冲突让行锁汇合，照 evaluationTurn 的确定性 ID+行锁先例，无需额外幂等表。
                    var row = repository.lockById(actor.tenantId(), memoryId);
                    if (row == null) {
                        try {
                            repository.insert(
                                    memoryId,
                                    actor.tenantId(),
                                    code,
                                    scope,
                                    actor.principalId(),
                                    kind,
                                    content,
                                    tags,
                                    sourceTaskId,
                                    "USER_EXPLICIT",
                                    "ACTIVE",
                                    expiresAt);
                        } catch (org.springframework.dao.DataAccessException raced) {
                            // 并发同号首插：回锁后统一走比对分支（同参幂等/异参冲突）。
                        }
                        row = repository.lockById(actor.tenantId(), memoryId);
                    }
                    if (row == null)
                        throw new PlatformException(500, "MEMORY_INSERT_RACE", "保存冲突未能汇合，请重试");
                    if (!replayEquals(row, kind, scope, content, tags, expiresAt, sourceTaskId))
                        throw new PlatformException(
                                409, "REQUEST_REPLAY", "requestId 已绑定其他保存参数，不能复用");
                    return detail(row, Map.of());
                });
    }

    /** 同号重放参数比对（sessionId 不参与身份——同一逻辑保存可从不同会话重放）。 */
    private static boolean replayEquals(
            Map<String, Object> row,
            String kind,
            String scope,
            String content,
            List<String> tags,
            Instant expiresAt,
            Long sourceTaskId) {
        Object rowTask = row.get("source_task_id");
        Long rowTaskId = rowTask == null ? null : ((Number) rowTask).longValue();
        return kind.equals(row.get("kind"))
                && scope.equals(row.get("scope"))
                && content.equals(row.get("content"))
                && Json.canonical(tags(row)).equals(Json.canonical(tags))
                && Objects.equals(rowTaskId, sourceTaskId)
                && Objects.equals(expiresAt(row), expiresAt);
    }

    /** 写范围裁决：scope 须在策略 writeScopes 内；SHARED 仅 ADMIN（USER 恒拒绝）。 */
    private static void requireWriteScope(String scope, MemoryPolicy policy, Actor actor) {
        if (!policy.canWrite(scope))
            throw new PlatformException(
                    403, "SCOPE_WRITE_DENIED", "当前策略 writeScopes 不含 " + scope + "，拒绝写入");
        if ("SHARED".equals(scope) && !actor.roles().contains("ADMIN"))
            throw new PlatformException(403, "SCOPE_WRITE_DENIED", "共享条目仅 ADMIN 可创建（USER 不写 SHARED）");
    }

    // ------------------------------------------------------------------
    // 更新（CAS + status 增量审批）
    // ------------------------------------------------------------------

    /**
     * PATCH /v1/apps/{code}/memories/{memoryId}：expectedVersion CAS（version 列），过期
     * 409 MEMORY_VERSION_CONFLICT。status 增量（评审九轮 P1-1）即 DRAFT 审批通道——确认
     * →ACTIVE、拒绝→REVOKED；权限：私有层=来源用户，共享层=ADMIN（所有人仅可 REVOKED
     * 自有条目——策略收窄不剥夺撤销权）。content/tags/expiresAt：私有=本人；共享=ADMIN
     * 且策略 writeScopes 含 SHARED（USER 不写 SHARED）。
     */
    public Map<String, Object> patch(String code, String memoryId, JsonNode body, Actor actor) {
        JsonNode expected = body.path("expectedVersion");
        if (!expected.isInt() || expected.intValue() < 1)
            throw new PlatformException(400, "MEMORY_EXPECTED_VERSION_INVALID", "必须提供正整数 expectedVersion");
        String status = body.hasNonNull("status") ? body.path("status").asText() : null;
        if (status != null && !Set.of("ACTIVE", "REVOKED").contains(status))
            throw new PlatformException(400, "MEMORY_STATUS_INVALID", "status 增量仅支持 ACTIVE 或 REVOKED");
        boolean hasContent = body.hasNonNull("content");
        boolean hasTags = body.has("tags") && !body.path("tags").isNull();
        boolean hasExpires = body.has("expiresAt");
        List<String> tags = hasTags ? parseTags(body.path("tags")) : null;
        Instant expiresAt = hasExpires ? parseExpiresAt(body) : null;
        if (hasContent && body.path("content").asText().isBlank())
            throw new PlatformException(400, "MEMORY_CONTENT_REQUIRED", "正文不能为空");
        if (hasContent
                && body.path("content").asText().trim().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > 65536)
            throw new PlatformException(400, "MEMORY_CONTENT_TOO_LARGE", "正文超过 64KB 上限（库稿 §4.4）");

        MemoryPolicy policy = policyFor(code, actor, null);
        var row = repository.findById(actor.tenantId(), memoryId);
        if (row == null || !code.equals(row.get("workspace_code"))) throw notFound(memoryId);
        String current = String.valueOf(row.get("status"));
        if ("REVOKED".equals(current)) throw notFound(memoryId);
        boolean owner = actor.principalId().equals(row.get("owner_id"));
        boolean admin = actor.roles().contains("ADMIN");
        String scope = String.valueOf(row.get("scope"));

        if ((hasContent || hasTags || hasExpires)) {
            boolean allowed =
                    "PRIVATE".equals(scope)
                            ? owner
                            : admin && policy.canWrite("SHARED");
            if (!allowed) {
                auditForbidden("UPDATE", actor, code, memoryId,
                        "scope=" + scope + " owner=" + owner + " admin=" + admin);
                throw new PlatformException(403, "MEMORY_UPDATE_FORBIDDEN", "无权更新该记忆内容");
            }
        }
        if (status != null) {
            boolean allowed;
            if ("PRIVATE".equals(scope)) allowed = owner;
            else if ("REVOKED".equals(status)) allowed = owner || admin;
            else allowed = admin; // 共享层 DRAFT→ACTIVE 仅 ADMIN（共享草稿由 ADMIN 确认）
            if (!allowed) {
                auditForbidden("STATUS", actor, code, memoryId,
                        "scope=" + scope + " target=" + status + " owner=" + owner + " admin=" + admin);
                throw new PlatformException(403, "MEMORY_STATUS_FORBIDDEN", "无权变更该记忆状态");
            }
        }

        int updated =
                repository.casUpdate(
                        actor.tenantId(), memoryId, expected.intValue(),
                        hasContent ? body.path("content").asText().trim() : null,
                        tags, expiresAt, hasExpires, status);
        if (updated == 0) {
            var latest = repository.findById(actor.tenantId(), memoryId);
            if (latest == null || "REVOKED".equals(String.valueOf(latest.get("status"))))
                throw notFound(memoryId);
            throw new PlatformException(
                    409, "MEMORY_VERSION_CONFLICT",
                    "版本已推进（当前 " + latest.get("version") + "），请基于最新版本重试");
        }
        var latest = repository.findById(actor.tenantId(), memoryId);
        return detail(latest, repository.taskPublicIds(
                latest.get("source_task_id") == null
                        ? Set.of()
                        : Set.of(((Number) latest.get("source_task_id")).longValue())));
    }

    // ------------------------------------------------------------------
    // 删除（先置 REVOKED 立即退出检索；幂等；正文异步清理占位）
    // ------------------------------------------------------------------

    /**
     * DELETE /v1/apps/{code}/memories/{memoryId}：先置 REVOKED 立即退出检索（列表/检索不再
     * 返回），正文按保留策略异步清理（见 MemoryRepository.revoke 占位——清理任务随保留策略
     * 卡交付）。删除走当前归属权限（接口稿 §5.4 策略版本段）：私有=本人；共享=所有人或
     * ADMIN——策略收窄不剥夺撤销权。幂等：重复 DELETE 返回原终态 {memoryId,status=REVOKED}。
     */
    public Map<String, Object> delete(String code, String memoryId, JsonNode body, Actor actor) {
        String requestId = body == null ? null : body.path("requestId").asText(null);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            throw new PlatformException(400, "MEMORY_DELETE_REQUEST_INVALID", "必须提供 requestId（≤128 字）");
        var row = repository.findById(actor.tenantId(), memoryId);
        if (row == null || !code.equals(row.get("workspace_code"))) throw notFound(memoryId);
        if ("REVOKED".equals(String.valueOf(row.get("status"))))
            return Map.of("memoryId", memoryId, "status", "REVOKED");
        boolean owner = actor.principalId().equals(row.get("owner_id"));
        boolean allowed =
                "PRIVATE".equals(row.get("scope")) ? owner : owner || actor.roles().contains("ADMIN");
        if (!allowed) {
            auditForbidden("DELETE", actor, code, memoryId,
                    "scope=" + row.get("scope") + " owner=" + owner);
            throw new PlatformException(403, "MEMORY_DELETE_FORBIDDEN", "无权删除该记忆");
        }
        repository.revoke(actor.tenantId(), memoryId);
        return Map.of("memoryId", memoryId, "status", "REVOKED");
    }

    // ------------------------------------------------------------------
    // 引擎内部检索（任务装配；非 HTTP）
    // ------------------------------------------------------------------

    /**
     * 引擎内部检索（主方案 §6.5 检索顺序）：①先按权限过滤——tenant/工作区/本人 PRIVATE+
     * 策略 readScopes 允许的 SHARED，仅 ACTIVE 且未过期；②再按 scope/标签/文本相关性排序
     * （scope：本人 PRIVATE 优先于 SHARED——个性化先于共享背景；标签命中数、文本命中数
     * 次之；created_at 兜底稳定序）；③最后按 topK 预算截取。policy 为任务钉住版本的策略
     * （运行检索按任务钉住版判定，调用方从任务快照解析），条目版本随行返回供装配材料
     * 清单记录（审计）。topK<=0 时取 policy.topK。
     */
    public List<Map<String, Object>> retrieve(
            String workspaceCode, Actor actor, String query, MemoryPolicy policy, int topK) {
        MemoryPolicy effective = policy == null ? MemoryPolicy.restricted() : policy;
        int budget = topK > 0 ? Math.min(topK, 100) : effective.topK();
        var candidates =
                repository.retrievalCandidates(
                        actor.tenantId(),
                        workspaceCode,
                        actor.principalId(),
                        effective.canRead("SHARED"));
        List<String> terms = new ArrayList<>();
        if (query != null)
            for (String term : query.toLowerCase(Locale.ROOT).split("\\s+"))
                if (!term.isBlank()) terms.add(term);
        record Scored(Map<String, Object> row, long tagHits, long textHits, int scopeRank) {}
        var scored = new ArrayList<Scored>();
        for (var row : candidates) {
            List<String> tags = tags(row);
            String content = String.valueOf(row.get("content")).toLowerCase(Locale.ROOT);
            long tagHits = 0;
            for (String tag : tags) {
                String lower = tag.toLowerCase(Locale.ROOT);
                for (String term : terms)
                    if (lower.contains(term) || term.contains(lower)) {
                        tagHits++;
                        break;
                    }
            }
            long textHits = 0;
            for (String term : terms) if (content.contains(term)) textHits++;
            scored.add(
                    new Scored(
                            row,
                            tagHits,
                            textHits,
                            "PRIVATE".equals(row.get("scope")) ? 1 : 0));
        }
        scored.sort(
                Comparator.comparingInt(Scored::scopeRank).reversed()
                        .thenComparing(Comparator.comparingLong(Scored::tagHits).reversed())
                        .thenComparing(Comparator.comparingLong(Scored::textHits).reversed())
                        .thenComparing(
                                (a, b) -> Comparator.<Instant>naturalOrder().reversed()
                                        .compare(expiresCreatedAt(a.row()), expiresCreatedAt(b.row())))
                        .thenComparing(
                                (a, b) ->
                                        String.valueOf(a.row().get("memory_id"))
                                                .compareTo(String.valueOf(b.row().get("memory_id")))));
        var result = new ArrayList<Map<String, Object>>();
        var taskIds = new LinkedHashSet<Long>();
        for (var entry : scored)
            if (entry.row().get("source_task_id") != null)
                taskIds.add(((Number) entry.row().get("source_task_id")).longValue());
        var publicIds = repository.taskPublicIds(taskIds);
        for (var entry : scored) {
            if (result.size() >= budget) break;
            var row = entry.row();
            var item = new LinkedHashMap<String, Object>();
            item.put("memoryId", row.get("memory_id").toString());
            item.put("kind", row.get("kind"));
            item.put("scope", row.get("scope"));
            item.put("tags", tags(row));
            // 条目版本随行返回：装配材料清单记录记忆版本（审计，主方案 §6.5）。
            item.put("version", ((Number) row.get("version")).intValue());
            item.put("content", row.get("content"));
            item.put("status", row.get("status"));
            if (row.get("source_task_id") != null)
                item.put("sourceTaskId", publicIds.get(((Number) row.get("source_task_id")).longValue()));
            result.add(item);
        }
        return result;
    }

    /**
     * 引擎内部提取入口（非 HTTP）：模型提取经验按 writeScopes 写 DRAFT（source_kind=
     * MODEL_EXTRACTED），走确认通道——私有草稿由本人确认、共享草稿由 ADMIN 确认（审批走
     * {@code patch} 的 status 增量）。policy 为任务钉住版本策略；scope 不在 writeScopes 内
     * 拒绝（引擎侧契约违例同样以 SCOPE_WRITE_DENIED 表达）。
     */
    public Map<String, Object> extractDraft(
            String workspaceCode,
            Actor actor,
            String kind,
            String scope,
            String content,
            List<String> tags,
            Long sourceTaskId,
            MemoryPolicy policy) {
        MemoryPolicy effective = policy == null ? MemoryPolicy.restricted() : policy;
        if (!Set.of("PREFERENCE", "EXPERIENCE").contains(kind))
            throw new PlatformException(400, "MEMORY_KIND_INVALID", "kind 仅支持 PREFERENCE 或 EXPERIENCE");
        if (!Set.of("PRIVATE", "SHARED").contains(scope))
            throw new PlatformException(400, "MEMORY_SCOPE_INVALID", "scope 仅支持 PRIVATE 或 SHARED");
        if (content == null || content.isBlank())
            throw new PlatformException(400, "MEMORY_CONTENT_REQUIRED", "提取正文不能为空");
        String trimmed = content.trim();
        if (trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new PlatformException(400, "MEMORY_CONTENT_TOO_LARGE", "提取正文超过 64KB 上限");
        if (!effective.canWrite(scope))
            throw new PlatformException(403, "SCOPE_WRITE_DENIED", "任务钉住策略 writeScopes 不含 " + scope);
        String memoryId = "m_" + UUID.randomUUID().toString().replace("-", "");
        repository.insert(
                memoryId,
                actor.tenantId(),
                workspaceCode,
                scope,
                actor.principalId(),
                kind,
                trimmed,
                tags == null ? List.of() : tags,
                sourceTaskId,
                "MODEL_EXTRACTED",
                "DRAFT",
                null);
        return detail(
                repository.findById(actor.tenantId(), memoryId), Map.of());
    }

    // ------------------------------------------------------------------
    // 投影与校验辅助
    // ------------------------------------------------------------------

    /** 列表项投影（接口稿 §5.4：不含正文）。 */
    private Map<String, Object> summary(Map<String, Object> row, Map<Long, String> publicIds) {
        var item = new LinkedHashMap<String, Object>();
        item.put("memoryId", row.get("memory_id").toString());
        item.put("kind", row.get("kind"));
        item.put("scope", row.get("scope"));
        item.put("tags", tags(row));
        item.put("version", ((Number) row.get("version")).intValue());
        item.put("status", row.get("status"));
        if (row.get("expires_at") != null) item.put("expiresAt", instantText(row.get("expires_at")));
        if (row.get("source_task_id") != null)
            item.put("sourceTaskId", publicIds.get(((Number) row.get("source_task_id")).longValue()));
        return item;
    }

    /** 单条/写入返回投影（接口稿 §5.4 单条形状：列表项+正文）。 */
    private Map<String, Object> detail(Map<String, Object> row, Map<Long, String> publicIds) {
        var item = summary(row, publicIds);
        item.put("content", row.get("content"));
        // 键序按接口稿单条形状整理：memoryId 在前、content 紧随其后。
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("memoryId", item.remove("memoryId"));
        ordered.put("content", item.remove("content"));
        ordered.putAll(item);
        return ordered;
    }

    private static List<String> tags(Map<String, Object> row) {
        var node = TraceStore.json(row.get("tags"));
        var values = new ArrayList<String>();
        if (node.isArray()) node.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static Instant expiresAt(Map<String, Object> row) {
        return row.get("expires_at") == null ? null : Instant.parse(instantText(row.get("expires_at")));
    }

    private static Instant expiresCreatedAt(Map<String, Object> row) {
        Object value = row.get("created_at");
        if (value instanceof Instant direct) return direct;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toInstant();
        if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant();
        return Instant.parse(value.toString());
    }

    /** timestamptz 读回统一 ISO-8601 UTC 文本（照 ChatSessions.text 口径）。 */
    private static String instantText(Object value) {
        if (value instanceof Instant direct) return direct.toString();
        if (value instanceof java.sql.Timestamp stamp) return stamp.toInstant().toString();
        if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant().toString();
        return Instant.parse(value.toString()).toString();
    }

    private void requireReadable(Map<String, Object> row, Actor actor, MemoryPolicy policy, String action, String code) {
        boolean readable =
                "PRIVATE".equals(row.get("scope"))
                        ? actor.principalId().equals(row.get("owner_id"))
                        : policy.canRead("SHARED");
        if (!readable) {
            auditForbidden(action, actor, code, row.get("memory_id").toString(),
                    "scope=" + row.get("scope") + " owner=" + row.get("owner_id"));
            throw new PlatformException(403, "MEMORY_FORBIDDEN", "无权读取该记忆");
        }
    }

    private static PlatformException notFound(String memoryId) {
        return new PlatformException(404, "MEMORY_NOT_FOUND", "记忆不存在或已删除");
    }

    /** 403 拒绝审计：引擎无独立审计表（归口 monitor §5.7），先落结构化日志保证可追溯。 */
    private static void auditForbidden(String action, Actor actor, String code, String memoryId, String reason) {
        org.slf4j.LoggerFactory.getLogger(Memories.class)
                .warn(
                        "AUDIT MEMORY_{}_FORBIDDEN tenant={} principal={} appCode={} memoryId={} reason={}",
                        action,
                        actor.tenantId(),
                        actor.principalId(),
                        code,
                        memoryId,
                        reason);
    }

    /** POST tags：字符串数组（trim、去空、单条 ≤128 字、≤100 条）；缺失/非法收窄为空。 */
    private static List<String> parseTags(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) return List.of();
        if (!raw.isArray()) throw new PlatformException(400, "MEMORY_TAGS_INVALID", "tags 必须为字符串数组");
        var values = new ArrayList<String>();
        for (var item : raw) {
            if (!item.isTextual()) throw new PlatformException(400, "MEMORY_TAGS_INVALID", "tags 必须为字符串数组");
            String tag = item.asText().trim();
            if (tag.isEmpty()) continue;
            if (tag.length() > 128)
                throw new PlatformException(400, "MEMORY_TAGS_INVALID", "单个标签不得超过 128 字");
            values.add(tag);
        }
        if (values.size() > 100)
            throw new PlatformException(400, "MEMORY_TAGS_INVALID", "标签数量不得超过 100");
        return List.copyOf(values);
    }

    /** 列表 tags 过滤参数：逗号分隔（mock-memories F4-2 同语义，任一命中即入选）。 */
    private static List<String> parseTagFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var values = new ArrayList<String>();
        for (String tag : raw.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return values.isEmpty() ? null : values;
    }

    private static Instant parseExpiresAt(JsonNode body) {
        JsonNode raw = body.path("expiresAt");
        if (raw.isMissingNode() || raw.isNull()) return null;
        if (!raw.isTextual()) throw new PlatformException(400, "MEMORY_EXPIRES_AT_INVALID", "expiresAt 必须为 ISO-8601 时间");
        try {
            return Instant.parse(raw.asText());
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new PlatformException(400, "MEMORY_EXPIRES_AT_INVALID", "expiresAt 必须为 ISO-8601 时间");
        }
    }

    private Long resolveSourceTask(String tenantId, JsonNode raw) {
        if (raw.isMissingNode() || raw.isNull()) return null;
        if (!raw.isTextual() || raw.asText().isBlank())
            throw new PlatformException(400, "MEMORY_SOURCE_TASK_INVALID", "sourceTaskId 必须为任务公共标识");
        Long id = repository.sourceTaskId(tenantId, raw.asText());
        if (id == null)
            throw new PlatformException(400, "MEMORY_SOURCE_TASK_INVALID", "来源任务不存在或不属于当前租户");
        return id;
    }

    private static int parseBounded(String raw, String name, int min, int max, String code) {
        if (raw == null || raw.isBlank()) return name.equals("pageSize") ? 20 : 1;
        if (!raw.matches("[0-9]{1,9}"))
            throw new PlatformException(400, code, name + " 必须为 " + min + " 至 " + max + " 的整数");
        int value = Integer.parseInt(raw);
        if (value < min || value > max)
            throw new PlatformException(400, code, name + " 必须为 " + min + " 至 " + max + " 的整数");
        return value;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
