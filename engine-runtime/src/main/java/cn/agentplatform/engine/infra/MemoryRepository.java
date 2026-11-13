package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * A3-4（库稿 §4.4 / V231）：memory_entry 数据访问层——只做行读写，不做权限裁决。
 *
 * <p>不变量：所有查询都以 tenant_id 收口（租户隔离先行），可见性（scope/owner/策略交集）
 * 由 {@code Memories} 按发布快照 memoryPolicy 与当前主体裁决；本层只提供带可见性谓词的
 * 参数化 SQL。检索纪律（库稿 §4.4）：先按权限过滤（tenant/scope/owner/status/有效期），
 * 再按相关性排序，最后按预算截取——排序与截取在 {@code Memories.retrieve} 内完成。
 *
 * <p>删除纪律：先置 REVOKED（立即退出检索），正文按保留策略异步清理（本卡占位，
 * 清理任务随保留策略卡交付）；历史运行的引用保留版本号（审计）。
 */
@Repository
public class MemoryRepository {
    private final TraceStore store;

    public MemoryRepository(TraceStore store) {
        this.store = store;
    }

    TraceStore store() {
        return store;
    }

    /** 插入一条记忆；memory_id 由调用方生成（显式保存为 requestId 派生幂等键，见 Memories）。 */
    public void insert(
            String memoryId,
            String tenantId,
            String workspaceCode,
            String scope,
            String ownerId,
            String kind,
            String content,
            List<String> tags,
            Long sourceTaskId,
            String sourceKind,
            String status,
            java.time.Instant expiresAt) {
        store.db()
                .update(
                        "insert into engine.memory_entry(memory_id,tenant_id,workspace_code,scope,"
                            + "owner_id,kind,content,tags,source_task_id,source_kind,version,status,"
                            + "expires_at) values(?,?,?,?,?,?,?,?::jsonb,?,?,1,?,?)",
                        memoryId,
                        tenantId,
                        workspaceCode,
                        scope,
                        ownerId,
                        kind,
                        content,
                        Json.write(tags == null ? List.of() : tags),
                        sourceTaskId,
                        sourceKind,
                        status,
                        expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
    }

    /** 按主键读取（tenant 收口）；visibility 谓词由调用方补充——返回全部列供裁决。 */
    public Map<String, Object> findById(String tenantId, String memoryId) {
        var rows =
                store.db()
                        .queryForList(
                                "select * from engine.memory_entry where memory_id=? and tenant_id=?",
                                memoryId,
                                tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 行锁读取（requestId 重放/并发写入的同一行收口，照 evaluationTurn 先例）。 */
    public Map<String, Object> lockById(String tenantId, String memoryId) {
        var rows =
                store.db()
                        .queryForList(
                                "select * from engine.memory_entry where memory_id=? and tenant_id=?"
                                    + " for update",
                                memoryId,
                                tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 资产列表：先权限过滤（tenant+workspace+可见性谓词）再过滤条件，REVOKED 一律不返回
     * （删除先撤销检索资格，接口稿 §5.4）。可见性谓词：本人 PRIVATE（owner_id=principal）
     * 恒可见；SHARED 仅当策略允许（sharedVisible=false 时恒排除）。q 命中正文或标签文本
     * （mock-memories F4-2 同语义）；tags 为 OR 语义（任一请求标签命中即入选）。
     *
     * @param tagsFilter 请求标签（null=不过滤）
     * @param status 显式状态过滤（null=默认 ACTIVE；REVOKED 在 SQL 中恒排除）
     * @param q 关键词（null=不过滤）
     */
    public List<Map<String, Object>> list(
            String tenantId,
            String workspaceCode,
            String principalId,
            boolean sharedVisible,
            String scope,
            String status,
            List<String> tagsFilter,
            String q,
            int limit,
            long offset) {
        var args = new ArrayList<Object>(
                List.of(tenantId, workspaceCode, principalId, sharedVisible));
        StringBuilder sql =
                new StringBuilder(
                        "select memory_id,workspace_code,scope,owner_id,kind,tags,version,status,"
                            + "expires_at,source_task_id,source_kind,created_at,updated_at"
                            + " from engine.memory_entry"
                            + " where tenant_id=? and workspace_code=?"
                            + " and ((scope='PRIVATE' and owner_id=?)"
                            + " or (scope='SHARED' and ?)) and status<>'REVOKED'");
        if (scope != null) {
            sql.append(" and scope=?");
            args.add(scope);
        }
        sql.append(" and status=?");
        args.add(status == null ? "ACTIVE" : status);
        if (tagsFilter != null && !tagsFilter.isEmpty()) {
            // jsonb 存在算子用函数形态 jsonb_exists_any：`?|` 里的 ? 会被 PG JDBC 当作
            // 参数占位符吞掉（占位符计数漂移），函数名形态不含裸 ?。
            sql.append(" and jsonb_exists_any(tags, array[");
            for (int i = 0; i < tagsFilter.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append("?::text");
                args.add(tagsFilter.get(i));
            }
            sql.append("])");
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (content ilike ? escape '\\' or tags::text ilike ? escape '\\')");
            String escaped = likeEscape(q.trim());
            args.add("%" + escaped + "%");
            args.add("%" + escaped + "%");
        }
        sql.append(" order by updated_at desc, memory_id limit ? offset ?");
        args.add(limit);
        args.add(offset);
        return store.db().queryForList(sql.toString(), args.toArray());
    }

    /** 与 {@link #list} 同谓词的总数（分页 total；同参数语义保证页数一致）。 */
    public long count(
            String tenantId,
            String workspaceCode,
            String principalId,
            boolean sharedVisible,
            String scope,
            String status,
            List<String> tagsFilter,
            String q) {
        var args = new ArrayList<Object>(
                List.of(tenantId, workspaceCode, principalId, sharedVisible));
        StringBuilder sql =
                new StringBuilder(
                        "select count(*) from engine.memory_entry"
                            + " where tenant_id=? and workspace_code=?"
                            + " and ((scope='PRIVATE' and owner_id=?)"
                            + " or (scope='SHARED' and ?)) and status<>'REVOKED'");
        if (scope != null) {
            sql.append(" and scope=?");
            args.add(scope);
        }
        sql.append(" and status=?");
        args.add(status == null ? "ACTIVE" : status);
        if (tagsFilter != null && !tagsFilter.isEmpty()) {
            // jsonb 存在算子用函数形态 jsonb_exists_any：`?|` 里的 ? 会被 PG JDBC 当作
            // 参数占位符吞掉（占位符计数漂移），函数名形态不含裸 ?。
            sql.append(" and jsonb_exists_any(tags, array[");
            for (int i = 0; i < tagsFilter.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append("?::text");
                args.add(tagsFilter.get(i));
            }
            sql.append("])");
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (content ilike ? escape '\\' or tags::text ilike ? escape '\\')");
            String escaped = likeEscape(q.trim());
            args.add("%" + escaped + "%");
            args.add("%" + escaped + "%");
        }
        var count = store.db().queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /**
     * 检索候选集（仅 ACTIVE+未过期+可见性谓词；库稿 §4.4 索引路径
     * (tenant_id,workspace_code,scope,status,expires_at)）。相关性排序与 topK 截取在服务层；
     * 扫描上限 1000 行是进程内有界输出的安全阀（单工作区活跃记忆远小于该值）。
     */
    public List<Map<String, Object>> retrievalCandidates(
            String tenantId, String workspaceCode, String principalId, boolean sharedVisible) {
        return store.db()
                .queryForList(
                        "select memory_id,scope,owner_id,kind,content,tags,version,status,"
                            + "expires_at,source_task_id,created_at from engine.memory_entry"
                            + " where tenant_id=? and workspace_code=? and status='ACTIVE'"
                            + " and (expires_at is null or expires_at>now())"
                            + " and ((scope='PRIVATE' and owner_id=?)"
                            + " or (scope='SHARED' and ?))"
                            + " order by updated_at desc limit 1000",
                        tenantId,
                        workspaceCode,
                        principalId,
                        sharedVisible);
    }

    /**
     * CAS 更新（接口稿 §5.4：版本号乐观锁，过期 409 MEMORY_VERSION_CONFLICT）。
     * 只更新提供的字段；任何更新都推进 version 并刷新 updated_at。返回受影响行数：
     * 0=版本冲突或行已不在（并发删除/撤销），调用方回读裁决错误码。
     */
    public int casUpdate(
            String tenantId,
            String memoryId,
            long expectedVersion,
            String content,
            List<String> tags,
            java.time.Instant expiresAt,
            boolean expiresAtPresent,
            String status) {
        var sets = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (content != null) {
            sets.add("content=?");
            args.add(content);
        }
        if (tags != null) {
            sets.add("tags=?::jsonb");
            args.add(Json.write(tags));
        }
        if (expiresAtPresent) {
            sets.add("expires_at=?");
            args.add(expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
        }
        if (status != null) {
            sets.add("status=?");
            args.add(status);
        }
        if (sets.isEmpty()) return 0;
        sets.add("version=version+1");
        sets.add("updated_at=now()");
        args.add(expectedVersion);
        args.add(tenantId);
        args.add(memoryId);
        return store.db()
                .update(
                        "update engine.memory_entry set "
                                + String.join(",", sets)
                                + " where version=? and tenant_id=? and memory_id=?"
                                + " and status<>'REVOKED'",
                        args.toArray());
    }

    /**
     * 删除第一步：置 REVOKED 立即退出检索（接口稿 §5.4）。幂等由调用方回读保证；
     * version 不推进——终态条目不再参与 CAS，历史运行引用的版本号保持可审计。
     * 正文异步清理占位：按保留策略由后续清理任务剥离 content（本卡只撤检索资格）。
     */
    public int revoke(String tenantId, String memoryId) {
        return store.db()
                .update(
                        "update engine.memory_entry set status='REVOKED',updated_at=now()"
                            + " where memory_id=? and tenant_id=? and status<>'REVOKED'",
                        memoryId,
                        tenantId);
    }

    /** 来源任务 public_id → 内部 FK（engine.task.id）；tenant 收口，未知任务返回 null。 */
    public Long sourceTaskId(String tenantId, String publicTaskId) {
        var rows =
                store.db()
                        .queryForList(
                                "select id from engine.task where public_id=? and tenant_id=?",
                                publicTaskId,
                                tenantId);
        return rows.isEmpty() ? null : ((Number) rows.getFirst().get("id")).longValue();
    }

    /** 内部 FK → 任务公共标识批量映射（页有界 ≤100，一次批查；对外只暴露 public_id）。 */
    public Map<Long, String> taskPublicIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var rows =
                store.db()
                        .queryForList(
                                "select id,public_id from engine.task where id in (" + placeholders + ")",
                                ids.toArray());
        var result = new LinkedHashMap<Long, String>();
        for (var row : rows)
            result.put(((Number) row.get("id")).longValue(), row.get("public_id").toString());
        return result;
    }

    /** q 关键词进 ILIKE 前转义通配符（%/_/\），保持字面匹配语义。 */
    private static String likeEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
