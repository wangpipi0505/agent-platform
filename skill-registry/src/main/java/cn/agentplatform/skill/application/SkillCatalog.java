package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.ProcessContract;
import cn.agentplatform.skill.domain.SkillDigest;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.AdapterRegistry;
import cn.agentplatform.skill.infra.McpAdapter;
import cn.agentplatform.skill.infra.OpenApiHttpMapping;
import cn.agentplatform.skill.infra.ProcessWhitelist;
import cn.agentplatform.skill.infra.SkillAdapters;
import cn.agentplatform.skill.infra.SkillClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** 技能版本只保存契约，发布指针与历史引用分离，已经发布的契约不被后续草稿修改。 */
@Service
public class SkillCatalog {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SkillClients clients;
    private final String appUrl;
    private final boolean legacyBearer;
    private final boolean evidenceDigestGate;
    /** PLAT-21d：PROCESS 可执行文件白名单（归一化后的绝对路径集；空集=未配置=不限制）。 */
    private final Set<String> allowedExecutables;
    /** PLAT-22C：kind 白名单与组合校验的单一来源（注册表），normalize 不再自持类型枚举。 */
    private final AdapterRegistry adapterRegistry;

    /** 单测直连四参构造：等价于默认 legacy-bearer=true、digest 门禁开启，既有测试无需感知本卡开关。 */
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            String appUrl) {
        this(db, manager, clients, appUrl, true, true);
    }

    /**
     * 兼容既有测试的五参构造（T10 之前只有 legacy-bearer 一个开关）。测试里需要验证回滚时改用六参，
     * 语义与容器注入完全一致，不允许出现“测试构造 = 另一套行为”。
     */
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            String appUrl,
            boolean legacyBearer) {
        this(db, manager, clients, appUrl, legacyBearer, true);
    }

    /** 兼容 T10 时代的六参构造：白名单未配置（T21d 兼容窗口，登记期不做白名单约束）。 */
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            String appUrl,
            boolean legacyBearer,
            boolean evidenceDigestGate) {
        this(db, manager, clients, appUrl, legacyBearer, evidenceDigestGate, "");
    }

    /**
     * 兼容 T21d 的七参构造（PLAT-22C 前的容器形态）：注册表落到内置单元——注册的单元类与 Spring
     * 装配完全同一批（见 {@link AdapterRegistry#withPlatformDefaults}），直接构造不产生第二套行为。
     */
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            String appUrl,
            boolean legacyBearer,
            boolean evidenceDigestGate,
            String allowedExecutables) {
        this(
                db,
                manager,
                clients,
                appUrl,
                legacyBearer,
                evidenceDigestGate,
                allowedExecutables,
                AdapterRegistry.withPlatformDefaults());
    }

    /**
     * 容器注入的正式构造。legacy-bearer 只影响注册期归一化（未声明 authScheme 时补 BEARER 还是
     * 拒绝），见 {@link cn.agentplatform.common.auth.AuthScheme} 的兼容窗口说明；默认 true 保证
     * 升级瞬间存量技能的对外凭据形态不变。
     *
     * <p>evidence-digest-gate 是 T10 的回滚开关：关闭后发布门禁退回只比 candidate_sha256（与
     * V315 之前逐字一致），已写入的 semantic_digest 列与新端点全部保留。开关只影响“是否额外要求
     * 摘要相等”，不会放行未通过的证据；默认 true，升级后立即获得“展示文案改动不再要求重测”。
     *
     * <p>PLAT-21d：allowed-executables 是 PROCESS 可执行文件白名单（逗号分隔绝对路径）。
     * 已配置时注册期即拒绝白名单外的可执行文件（400 可读，见 {@link #normalize}）；
     * 未配置时保持兼容行为（ADMIN 登记 + 发布预检兜底），执行期由 ProcessAdapter 的第二道闸兜住。
     *
     * <p>PLAT-22C：adapterRegistry 是注册期 kind 白名单/组合校验/工具发现能力的单一来源。注册表由
     * Spring 装配（内置单元 @Component 自动发现 + 外部新执行方式实现 {@link cn.agentplatform.skill.infra.SkillAdapter}
     * 即接入），目录层不再自持类型枚举——新 kind 能否登记由“是否已注册”决定，而不是由本类硬编码决定。
     */
    @Autowired
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.skill.auth.legacy-bearer:true}") boolean legacyBearer,
            @Value("${agent.skill.evidence-digest-gate:true}") boolean evidenceDigestGate,
            @Value("${agent.process.allowed-executables:}") String allowedExecutables,
            AdapterRegistry adapterRegistry) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.clients = clients;
        this.appUrl = appUrl;
        this.legacyBearer = legacyBearer;
        this.evidenceDigestGate = evidenceDigestGate;
        this.allowedExecutables = ProcessWhitelist.parse(allowedExecutables);
        this.adapterRegistry = adapterRegistry;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    public static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    public static String text(Map<String, Object> b, String key) {
        String value = Objects.toString(b.get(key), "");
        if (value.isBlank() || value.length() > 4096)
            throw error(400, "INVALID_SKILL", "缺少或无效字段: " + key);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map)) throw error(400, "INVALID_SKILL", "字段必须为JSON对象");
        return (Map<String, Object>) raw;
    }

    public static long number(Map<String, Object> b, String key) {
        if (!(b.get(key) instanceof Number n) || n.longValue() < 1)
            throw error(400, "INVALID_SKILL", "缺少正整数: " + key);
        return n.longValue();
    }

    private Map<String, Object> main(String code, boolean lock) {
        var rows =
                db.queryForList(
                        "select * from skill.skill where tenant_id=? and code=?"
                                + (lock ? " for update" : ""),
                        tenant(),
                        code);
        if (rows.isEmpty()) throw error(404, "SKILL_NOT_FOUND", "技能不存在");
        return rows.getFirst();
    }

    private Map<String, Object> versionRow(ResultSet row, int ignored) throws SQLException {
        var result =
                new LinkedHashMap<String, Object>(Json.read(row.getString("contract"), Map.class));
        result.put("skillVersionId", row.getLong("id"));
        result.put("id", row.getLong("id"));
        result.put("versionNo", row.getInt("version_no"));
        result.put("version", row.getInt("version_no"));
        result.put("status", row.getString("status"));
        result.put("sha256", row.getString("sha256"));
        result.put("digest", row.getString("sha256"));
        // T10：digest 沿用旧字段名指向 sha256（历史前端用它做“草稿字节指纹”），语义摘要另开字段，
        // 两个指纹含义不同，不能互相顶替：sha 证明字节未变，semanticDigest 证明语义未变。
        result.put("semanticDigest", row.getString("semantic_digest"));
        result.put("digestVersion", SkillDigest.VERSION);
        result.put("definition", Json.read(row.getString("contract"), Object.class));
        result.put("code", row.getString("skill_code"));
        return result;
    }

    public List<Map<String, Object>> versions(String code) {
        main(code, false);
        return db.query(
                "select * from skill.skill_version where tenant_id=? and skill_code=? order by"
                        + " version_no desc",
                this::versionRow,
                tenant(),
                code);
    }

    public Map<String, Object> version(String code, int version) {
        var rows =
                db.query(
                        "select v.* from skill.skill_version v join skill.skill s on"
                            + " s.tenant_id=v.tenant_id and s.code=v.skill_code where v.tenant_id=?"
                            + " and v.skill_code=? and v.version_no=?",
                        this::versionRow,
                        tenant(),
                        code,
                        version);
        if (rows.isEmpty()) throw error(404, "SKILL_VERSION_NOT_FOUND", "技能版本不存在");
        var result = rows.getFirst();
        result.put("skillStatus", main(code, false).get("status"));
        return result;
    }

    /** 能力包首次绑定只读现有发布事实和实际注册表，不归一化、改写契约或执行适配器。 */
    public Map<String, Object> registeredVersion(String code, Integer requested) {
        if (requested == null || requested < 1)
            throw error(400, "SKILL_VERSION_REQUIRED", "绑定必须指定精确正整数技能版本");
        var result = version(code, requested);
        if (!"PUBLISHED".equals(result.get("status"))
                || Set.of("DISABLED", "ARCHIVED").contains(result.get("skillStatus")))
            throw error(409, "SKILL_NOT_PUBLISHED", "绑定需要当前可用的精确已发布技能版本");
        var definition = object(result.get("definition"));
        if (!Digest.sha256(Json.canonical(definition)).equals(result.get("sha256")))
            throw error(409, "SKILL_DIGEST_MISMATCH", "技能版本摘要与持久契约不符");
        String type = Objects.toString(definition.get("type"), "");
        String kind = Objects.toString(definition.get("invocationKind"), "");
        if (!adapterRegistry.isSupportedCombination(type, kind) || adapterRegistry.find(definition) == null)
            throw error(409, "SKILL_ADAPTER_NOT_REGISTERED", "当前服务未注册该技能的执行组合");
        result.put("registered", true);
        // 历史规范化契约省略默认 protocol；使用同一解释器读取，绝不重写 definition/digest。
        if ("PROCESS".equals(type))
            result.put("resolvedProcessProtocol", ProcessContract.parse(Json.tree(definition.get("process"))).protocol());
        return result;
    }

    public Map<String, Object> versionId(long id) {
        var rows =
                db.query(
                        "select * from skill.skill_version where tenant_id=? and id=?",
                        this::versionRow,
                        tenant(),
                        id);
        if (rows.isEmpty()) throw error(404, "SKILL_VERSION_NOT_FOUND", "技能版本不存在");
        var result = rows.getFirst();
        result.put("skillStatus", main(result.get("code").toString(), false).get("status"));
        return result;
    }

    /** Only the tenant's saved draft chooses endpoint/credential; discovery never calls tools. */
    public Map<String, Object> mcpTools(String code, int version) {
        Actor.current().requireRole("EDITOR");
        var candidate = version(code, version);
        if (!"DRAFT".equals(candidate.get("status")))
            throw error(409, "SKILL_STATE", "仅草稿可列取MCP工具");
        // PLAT-22C：工具目录发现是注册单元声明的能力（MCP 单元支持），不再按类型字符串硬判。
        if (!adapterRegistry.supportsToolDiscovery(Objects.toString(candidate.get("type"), "")))
            throw error(400, "INVALID_SKILL", "该草稿不是MCP技能");
        if (!Objects.toString(candidate.get("authRef"), "").isBlank())
            Actor.current().requireRole("ADMIN");
        try {
            return new McpAdapter().discover(candidate);
        } catch (SkillAdapters.Failure failure) {
            int status =
                    switch (failure.semantic) {
                        case "NO_PERMISSION" -> 403;
                        case "QUOTA" -> 429;
                        case "PARAM_ERROR" -> 400;
                        default -> 502;
                    };
            throw error(status, "MCP_" + failure.semantic, failure.getMessage());
        }
    }

    public Map<String, Object> get(String code, Integer requested) {
        var main = main(code, false);
        if (requested != null) return version(code, requested);
        var versions = versions(code);
        Map<String, Object> selected =
                main.get("current_version_id") == null
                        ? (versions.isEmpty() ? new LinkedHashMap<>() : versions.getFirst())
                        : versionId(((Number) main.get("current_version_id")).longValue());
        var result = new LinkedHashMap<>(selected);
        result.put("id", main.get("id"));
        result.put("code", code);
        result.put("name", main.get("name"));
        result.put("status", main.get("status"));
        result.put(
                "currentVersion",
                main.get("current_version_id") == null ? null : selected.get("versionNo"));
        result.put("currentVersionId", main.get("current_version_id"));
        result.put("versions", versions);
        result.put("category", main.get("category"));
        result.put("tags", Json.read(main.get("tags").toString(), Object.class));
        return result;
    }

    public List<Map<String, Object>> list() {
        return db
                .queryForList(
                        "select code from skill.skill where tenant_id=? order by updated_at desc",
                        tenant())
                .stream()
                .map(r -> get(r.get("code").toString(), null))
                .toList();
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String code = text(body, "code"), name = text(body, "name");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || name.length() > 128)
            throw error(400, "INVALID_SKILL", "编码或名称无效");
        return tx.execute(
                status -> {
                    db.update(
                            "insert into skill.skill(tenant_id,code,name,category,tags)"
                                    + " values(?,?,?,?,?::jsonb)",
                            tenant(),
                            code,
                            name,
                            Objects.toString(body.get("category"), ""),
                            Json.write(body.getOrDefault("tags", List.of())));
                    var contract =
                            body.containsKey("type") || body.containsKey("invocation")
                                    ? normalize(body)
                                    : Map.<String, Object>of();
                    // 两个指纹一起写、取同一个规范化对象：sha 覆盖整份契约（含展示文案），
                    // semanticDigest 只覆盖语义字段（排除集在 SkillDigest 内唯一实现）。
                    db.update(
                            "insert into"
                                + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256,semantic_digest)"
                                + " values(?,?,1,?::jsonb,?,?)",
                            tenant(),
                            code,
                            Json.write(contract),
                            Digest.sha256(Json.canonical(contract)),
                            SkillDigest.of(contract));
                    return get(code, null);
                });
    }

    public Map<String, Object> save(String code, Map<String, Object> body) {
        return tx.execute(
                status -> {
                    main(code, true);
                    var all = versions(code);
                    int version = ((Number) all.getFirst().get("versionNo")).intValue();
                    var effective = new LinkedHashMap<String, Object>(body);
                    var previous = object(all.getFirst().get("definition"));
                    // 旧编辑器省略新增字段不等于删除来源/执行映射；在行锁内补回，避免并发草稿覆盖。
                    if (previous.containsKey("importSource")) {
                        if (effective.containsKey("importSource")
                                && !Json.tree(previous.get("importSource"))
                                        .equals(Json.tree(effective.get("importSource"))))
                            throw error(400, "IMPORT_SOURCE_IMMUTABLE", "导入来源不能通过编辑替换；请重新导入为独立草稿");
                        effective.putIfAbsent("importSource", previous.get("importSource"));
                    }
                    if (previous.containsKey("httpMapping")
                            && !effective.containsKey("httpMapping"))
                        effective.put("httpMapping", previous.get("httpMapping"));
                    // authScheme 同属“旧编辑器省略不等于删除”的字段：局部保存若把 API_KEY 静默降级成
                    // 默认 BEARER，出站凭据形态会在一次无关编辑后改变且不留痕迹；显式声明过的契约
                    // 以旧值为准，本次提交若带新值仍以新值为准（putIfAbsent 语义）。
                    if (previous.containsKey(AuthScheme.FIELD)
                            && !effective.containsKey(AuthScheme.FIELD))
                        effective.put(AuthScheme.FIELD, previous.get(AuthScheme.FIELD));
                    // identity 出站声明同理（PLAT-12）：旧编辑器局部保存静默丢掉 identity，出站
                    // X-User-* 会在一次无关编辑后消失且不留痕；已保存的声明以旧值为准补回。
                    if (previous.containsKey(SkillRules.IDENTITY_FIELD)
                            && !effective.containsKey(SkillRules.IDENTITY_FIELD))
                        effective.put(
                                SkillRules.IDENTITY_FIELD, previous.get(SkillRules.IDENTITY_FIELD));
                    // 省略不等于删除新声明；显式null仍留给纯规则拒绝，不用putIfAbsent吞掉坏输入。
                    for (String field : List.of("exposure", "pagination"))
                        if (previous.containsKey(field) && !effective.containsKey(field))
                            effective.put(field, previous.get(field));
                    boolean projected =
                            previous.containsKey("exposure")
                                    || (previous.get("importSource") instanceof Map<?, ?> source
                                            && "ONTOLOGY".equals(source.get("kind")));
                    if (projected
                            && !effective.containsKey("riskLevel")
                            && "CONFIRM_REQUIRED".equals(previous.get("riskLevel")))
                        effective.put("riskLevel", previous.get("riskLevel"));
                    var contract = normalize(effective);
                    String sha = Digest.sha256(Json.canonical(contract));
                    String semanticDigest = SkillDigest.of(contract);
                    if (all.getFirst().get("status").equals("DRAFT")) {
                        // 草稿原地保存会同时换掉两个指纹：只改展示文案时 sha 变、semanticDigest 不变，
                        // 已通过的证据仍然有效；改了语义则两者都变，旧证据被门禁挡住。
                        db.update(
                                "update skill.skill_version set contract=?::jsonb,sha256=?,semantic_digest=? where"
                                        + " tenant_id=? and skill_code=? and version_no=? and"
                                        + " status='DRAFT'",
                                Json.write(contract),
                                sha,
                                semanticDigest,
                                tenant(),
                                code,
                                version);
                    } else {
                        version++;
                        db.update(
                                "insert into"
                                    + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256,semantic_digest)"
                                    + " values(?,?,?,?::jsonb,?,?)",
                                tenant(),
                                code,
                                version,
                                Json.write(contract),
                                sha,
                                semanticDigest);
                    }
                    db.update(
                            "update skill.skill set updated_at=now() where tenant_id=? and code=?",
                            tenant(),
                            code);
                    return version(code, version);
                });
    }

    /** 本体导入使用实际注册表预检组合；注册单元不是binding_ref名义绑定目录。 */
    void validateOntologyTransport(Map<String, Object> manifest) {
        String type = Objects.toString(manifest.get("adapter_type"), "");
        if (!adapterRegistry.isSupportedType(type))
            throw error(400, "INVALID_MANIFEST", "adapter_type未登记");
        if (!"HTTP".equals(type))
            for (String field : List.of("endpoint", "http_method"))
                if (manifest.containsKey(field))
                    throw error(400, "INVALID_MANIFEST", field + "与非HTTP adapter_type冲突");
        String kind = Objects.toString(manifest.get("invocation_kind"), "");
        if (!adapterRegistry.isSupportedCombination(type, kind))
            throw error(400, "INVALID_MANIFEST", "invocation_kind与adapter_type组合未登记");
    }

    public Map<String, Object> normalize(Map<String, Object> body) {
        // 编辑既有SYNC技能时，清空httpMapping即表示改为无映射的异步提交；空映射按缺省处理，
        // 否则OpenAPI映射校验会把“已清空”误判为形状不一致，导入来源的技能就无法收敛为SUBMIT_QUERY。
        if (body.containsKey("httpMapping") && object(body.get("httpMapping")).isEmpty()) {
            body = new LinkedHashMap<>(body);
            body.remove("httpMapping");
        }
        var invocation = object(body.getOrDefault("invocation", Map.of()));
        String type =
                Objects.toString(
                        body.getOrDefault("type", invocation.getOrDefault("adapterType", "")), "");
        // PLAT-22C：适配器类型白名单的单一来源是注册表（内置五单元 + 已注册的新执行方式）。
        // 未注册 kind 的拒绝保持既有 400 码与文案；新执行方式接入 = 实现注册单元，不改本处。
        if (!adapterRegistry.isSupportedType(type))
            throw error(400, "INVALID_SKILL", "适配器类型无效");
        var result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("description", text(body, "description"));
        boolean process = type.equals("PROCESS");
        String endpoint =
                Objects.toString(
                        body.getOrDefault("endpoint", invocation.getOrDefault("endpoint", "")), "");
        // PROCESS在本机执行、不出站：endpoint是HTTP寻址，httpMapping是OpenAPI参数映射，二者与process
        // 子契约互斥。这里点名冲突字段而不是笼统报“契约无效”，编辑器才能把错误定位到具体输入项。
        if (process) {
            if (!endpoint.isBlank())
                throw error(
                        400,
                        "INVALID_SKILL",
                        "PROCESS 技能不能携带 endpoint：本地进程不发起 HTTP 请求，请移除端点");
            if (body.containsKey("httpMapping"))
                throw error(
                        400,
                        "INVALID_SKILL",
                        "PROCESS 技能不能携带 httpMapping：本地进程不走 OpenAPI 参数映射");
        }
        if (body.containsKey("httpMapping")) {
            var mapped = new LinkedHashMap<String, Object>(body);
            mapped.put("type", type);
            mapped.put("endpoint", endpoint);
            mapped.put(
                    "httpMethod",
                    body.getOrDefault("httpMethod", invocation.getOrDefault("httpMethod", "POST")));
            OpenApiHttpMapping.validate(mapped);
        } else if (!type.equals("ALGORITHM") && !process) SkillRules.endpoint(endpoint);
        result.put("endpoint", endpoint);
        String auth =
                Objects.toString(
                        body.getOrDefault("authRef", invocation.getOrDefault("authRef", "")), "");
        if (!auth.isBlank()
                && !auth.startsWith("vault:")
                && !auth.matches("env:AGENT_SECRET_[A-Z0-9_]+"))
            throw error(400, "INVALID_SECRET_REF", "认证只能使用已配置secret_ref");
        if (!auth.isBlank()) Actor.current().requireRole("ADMIN");
        result.put("authRef", auth);
        // authScheme 放在 authRef 校验之后归一化：组合规则（NONE 不得带凭据、API_KEY 必须带凭据）
        // 只有在 authRef 已是合法 secret_ref 之后才有意义，否则两类错误会互相掩盖。
        // 归一化结果一律显式落库，执行侧不再回读 legacy 开关，避免同一契约因部署配置不同而被解释成
        // 不同鉴权方式。缺省补 BEARER 的兼容窗口由 SkillRules/AuthScheme 统一裁决。
        AuthScheme scheme =
                SkillRules.authScheme(
                        body.getOrDefault(
                                AuthScheme.FIELD, invocation.getOrDefault(AuthScheme.FIELD, "")),
                        auth,
                        type,
                        legacyBearer);
        result.put(AuthScheme.FIELD, scheme.name());
        // identity 出站声明（PLAT-12 §4.12）与 authScheme 同期校验：mode/headers 白名单在注册期
        // 拒绝，错误配置从未被发布。未声明不落键——契约形状与出站行为对存量技能零变化。
        Object declaredIdentity =
                body.containsKey(SkillRules.IDENTITY_FIELD)
                        ? body.get(SkillRules.IDENTITY_FIELD)
                        : invocation.get(SkillRules.IDENTITY_FIELD);
        if (declaredIdentity != null)
            result.put(
                    SkillRules.IDENTITY_FIELD,
                    SkillRules.identity(declaredIdentity, type));
        result.put(
                "httpMethod",
                body.getOrDefault("httpMethod", invocation.getOrDefault("httpMethod", "POST")));
        String kind =
                Objects.toString(
                        invocation.getOrDefault(
                                "kind",
                                body.getOrDefault(
                                        "invocationKind", adapterRegistry.defaultKind(type))));
        // PLAT-22C：类型×调用形态组合白名单收敛到注册表（单元声明 supportedKinds，注册表取并集）。
        // 错误码与触发条件与既有完全一致；PROCESS 的定制诊断由其注册单元承载（unsupportedKindMessage），
        // 编辑器可读性不退化；未注册 kind 同样在此给出可读 400。
        if (!adapterRegistry.isSupportedCombination(type, kind))
            throw error(
                    400,
                    "UNSUPPORTED_INVOCATION_KIND",
                    adapterRegistry.combinationMessage(type, kind));
        // PROCESS子契约只由ProcessContract.parse解释（绝对路径可执行文件、参数个数、超时、stdin/env、
        // 输出上限），目录层负责把规范化结果写回持久化契约：缺省值在注册期落库，执行期不再猜默认值，
        // 草稿读回的取值就是执行时的取值（与HTTP契约用OpenApiHttpMapping归一化同一做法）。
        Map<String, Object> processContract = null;
        if (process) {
            processContract = normalizedProcess(body);
            // PLAT-21d：白名单已配置时，注册期即拒绝白名单外的可执行文件（400 可读），
            // 不把“发布后发现跑不了”推迟到第一次真实调用。空集=未配置=兼容窗口，由
            // ADMIN 登记 + 发布预检 + 执行期第二道闸兜底。
            if (!ProcessWhitelist.contains(
                    allowedExecutables, ((List<?>) processContract.get("command")).get(0).toString()))
                throw error(
                        400,
                        "PROCESS_EXECUTABLE_FORBIDDEN",
                        "可执行文件不在白名单 agent.process.allowed-executables 内: "
                                + processContract.get("command").toString());
            result.put("process", processContract);
        }
        if (type.equals("HTTP") && kind.equals("CALLBACK")) {
            if (!"POST".equals(result.get("httpMethod")) || body.containsKey("httpMapping"))
                throw error(400, "INVALID_ASYNC_PROTOCOL", "回调提交仅支持POST JSON参数");
            var callback =
                    object(
                            body.getOrDefault(
                                    "callback", invocation.getOrDefault("callback", Map.of())));
            String reference = text(callback, "secretRef");
            Actor.current().requireRole("ADMIN");
            // Resolve now to prove tenant ownership; only the opaque reference is frozen.
            SecretVault.resolve(reference);
            result.put("callback", Map.of("secretRef", reference));
            result.put(
                    "jobIdPath",
                    invocation.getOrDefault(
                            "jobIdPath", body.getOrDefault("jobIdPath", "$.jobId")));
            cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                    Json.tree(Map.of()), result.get("jobIdPath").toString());
        }
        if (type.equals("HTTP")
                && (kind.equals("SUBMIT_QUERY")
                        || kind.equals("CALLBACK")
                                && !object(body.getOrDefault("reconciliation", Map.of()))
                                        .isEmpty())) {
            if (!"POST".equals(result.get("httpMethod")) || body.containsKey("httpMapping"))
                throw error(
                        400, "INVALID_ASYNC_PROTOCOL", "异步HTTP提交当前仅支持POST JSON参数，不能混用OpenAPI映射");
            var policy = object(body.getOrDefault("reconciliation", Map.of()));
            Object echo = policy.get("idempotencyKeyEcho");
            if (echo != null && !(echo instanceof Boolean))
                throw error(400, "INVALID_ASYNC_PROTOCOL", "idempotencyKeyEcho必须为布尔值");
            String query = text(policy, "endpoint");
            // PLAT-06：先校验声明式映射（错误带字段路径与修复建议），再做端点形状检查——否则
            // {remoteTaskId} 会在 URI.create 报出无法定位的参数错误，接入方无从得知要配 pathParams。
            // resultEndpoint/lookupEndpoint 是可选字段，必须用容忍读取而不是text()（缺键即400）。
            var pathParams =
                    cn.agentplatform.skill.infra.HttpAsyncAdapter.validatedPathParams(
                            policy,
                            query,
                            Objects.toString(policy.get("resultEndpoint"), ""),
                            Objects.toString(policy.get("lookupEndpoint"), ""));
            SkillRules.endpoint(
                    cn.agentplatform.skill.infra.HttpAsyncAdapter.dummyUrl(query, pathParams));
            boolean addressable =
                    cn.agentplatform.skill.infra.HttpAsyncAdapter.placeholders(query).stream()
                            .anyMatch(
                                    name ->
                                            cn.agentplatform.skill.infra.HttpAsyncAdapter.legacyPlaceholder(
                                                    name)
                                                    || pathParams.containsKey(name));
            if (!Objects.toString(policy.getOrDefault("method", "GET")).equals("GET")
                    || !addressable
                    || !policy.containsKey("doneValue"))
                throw error(
                        400,
                        "INVALID_ASYNC_PROTOCOL",
                        "异步查询需要GET、可解析的任务占位符（{jobId}或pathParams映射）及完成标记");
            cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                    Json.tree(Map.of()), text(policy, "donePath"));
            result.put(
                    "jobIdPath",
                    invocation.getOrDefault(
                            "jobIdPath", body.getOrDefault("jobIdPath", "$.jobId")));
            for (String path :
                    List.of(
                            result.get("jobIdPath").toString(),
                            Objects.toString(
                                    policy.getOrDefault("idempotencyKeyPath", "$.idempotencyKey")),
                            Objects.toString(policy.getOrDefault("resultPath", "$.result"))))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(Json.tree(Map.of()), path);
            if (policy.containsKey("lookupEndpoint")) {
                String lookup = text(policy, "lookupEndpoint");
                if (!lookup.contains("{idempotencyKey}") || lookup.contains("{jobId}"))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "丢失提交响应后的查询必须仅使用原幂等键");
                // 该查询只在任务标识未知时可达：映射到 externalJobId 的占位符在这里永远取不到值。
                for (String name :
                        cn.agentplatform.skill.infra.HttpAsyncAdapter.placeholders(lookup))
                    if (!cn.agentplatform.skill.infra.HttpAsyncAdapter.legacyPlaceholder(name)
                            && "externalJobId".equals(pathParams.get(name)))
                        throw error(
                                400,
                                "INVALID_ASYNC_PROTOCOL",
                                "丢失提交响应后的查询不能依赖任务标识占位符 {"
                                        + name
                                        + "}：该场景下任务标识未知，请改用幂等键映射");
                SkillRules.endpoint(
                        cn.agentplatform.skill.infra.HttpAsyncAdapter.dummyUrl(lookup, pathParams));
            }
            if (policy.containsKey("failedValue") || policy.containsKey("failedValues")) {
                Object raw =
                        policy.containsKey("failedValues")
                                ? policy.get("failedValues")
                                : policy.get("failedValue");
                var failed = Json.tree(raw);
                if (!(failed.isArray() || failed.isValueNode()))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态必须为标量或标量数组");
                if (failed.isArray() && failed.isEmpty())
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态数组不能为空");
                var values = failed.isArray() ? failed : Json.mapper().createArrayNode().add(failed);
                for (var value : values)
                    if (!value.isValueNode())
                        throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态元素必须为标量");
                if (Json.tree(policy.get("doneValue")).equals(failed))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态不能与完成终态相同");
            }
            if (policy.containsKey("failureMessagePath"))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                        Json.tree(Map.of()), text(policy, "failureMessagePath"));
            if (policy.containsKey("resultEndpoint")) {
                String resultEndpoint = text(policy, "resultEndpoint");
                // 占位符与 pathParams 的一一对应已由 validatedPathParams 统一校验（含本端点）；
                // 这里只要求至少存在一个可解析的任务占位符，否则结果端点寻址不到原任务。
                if (cn.agentplatform.skill.infra.HttpAsyncAdapter.placeholders(resultEndpoint)
                        .isEmpty())
                    throw error(
                            400,
                            "INVALID_ASYNC_PROTOCOL",
                            "结果端点需要任务占位符（{jobId}或pathParams映射）");
                SkillRules.endpoint(
                        cn.agentplatform.skill.infra.HttpAsyncAdapter.dummyUrl(
                                resultEndpoint, pathParams));
            }
            if (policy.containsKey("resultPath"))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                        Json.tree(Map.of()), text(policy, "resultPath"));
            if (policy.containsKey("resultEndpointPath"))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                        Json.tree(Map.of()), text(policy, "resultEndpointPath"));
        }
        result.put("invocationKind", kind);
        long timeout;
        if (process) {
            // PROCESS只有一个真实超时：process.timeoutMs（缺省60s、上限30min）。顶层timeoutMs是所有
            // 适配器共用的形状字段，这里派生而不是再独立声明——否则编辑页显示30s、执行器按60s跑，
            // 同一契约里两个互相矛盾的数。通用100..600000是HTTP调用时长的上界，不适用于本地进程。
            timeout = ((Number) processContract.get("timeoutMs")).longValue();
        } else {
            timeout =
                    ((Number)
                                    body.getOrDefault(
                                            "timeoutMs", invocation.getOrDefault("timeoutMs", 30000)))
                            .longValue();
            if (timeout < 100 || timeout > 600000)
                throw error(400, "INVALID_SKILL", "timeoutMs必须为100至600000");
        }
        result.put("timeoutMs", timeout);
        // 所有适配器（HTTP/MCP/ALGORITHM/PROCESS）的契约 Schema 都走同一个方言入口，返回的规范形
        // 就是下面落库的契约值：nullable 与多具体类型联合在注册期收敛，读回/执行期不再解释旧写法。
        // 从前这里按 type 分支只给 MCP 调 mcpSchema、其余走 schema，两者其实共用同一份规则
        // （mcp 参数从未参与判定），分支只会让同一契约在不同适配器下被两套标准检查。
        var input = object(body.get("inputSchema"));
        var output = object(body.getOrDefault("outputSchema", Map.of("type", "object")));
        try {
            input = SkillRules.normalizedSchema(input);
            output = SkillRules.normalizedSchema(output);
        } catch (IllegalArgumentException invalid) {
            // 保持 schema 失败既有的 400 契约错误码；IllegalArgumentException 的 message 会被
            // PlatformErrors 统一吞成固定文案，方言 pointer 只有走 PlatformException 才到得了前端。
            throw error(400, "INVALID_INPUT", invalid.getMessage());
        }
        result.put("inputSchema", input);
        result.put("outputSchema", output);
        // PLAT-15：normalizeApproval 会在"空 approval 视为缺省"时移除该键，这里落库的是归一化副本，
        // 不改写调用方传入的（可能不可变的）契约 Map。
        var execution = new LinkedHashMap<>(object(body.get("execution")));
        for (String flag : List.of("readonly", "sideEffect", "idempotent", "cancellable"))
            if (!(execution.get(flag) instanceof Boolean))
                throw error(400, "INVALID_SKILL", "execution需显式布尔字段: " + flag);
        result.putAll(SkillRules.projection(body));
        if (Boolean.TRUE.equals(execution.get("readonly"))
                && Boolean.TRUE.equals(execution.get("sideEffect")))
            throw error(400, "INVALID_SKILL", "只读与副作用互斥");
        normalizeApproval(execution, type, kind);
        String risk =
                Boolean.TRUE.equals(execution.get("readonly"))
                                && !"CONFIRM_REQUIRED".equals(body.get("riskLevel"))
                        ? "READ_ONLY"
                        : "CONFIRM_REQUIRED";
        result.put("riskLevel", risk);
        result.put("execution", execution);
        var guard =
                new LinkedHashMap<String, Object>(
                        object(body.getOrDefault("guard", body.getOrDefault("access", Map.of()))));
        var attributes =
                new LinkedHashMap<String, Object>(
                        object(guard.getOrDefault("attributes", Map.of())));
        if (guard.get("guardAttrs") instanceof Collection<?> names)
            for (Object name : names) attributes.putIfAbsent(name.toString(), List.of());
        guard.remove("guardAttrs");
        guard.put("attributes", attributes);
        guard.putIfAbsent("roles", List.of());
        if (!(guard.get("roles") instanceof Collection<?> roles)
                || roles.stream().anyMatch(role -> !(role instanceof String r) || r.isBlank()))
            throw error(400, "INVALID_GUARD", "守卫角色必须为明确字符串数组");
        for (var entry : attributes.entrySet())
            if (!(entry.getValue() instanceof Collection<?> values)
                    || values.stream().anyMatch(value -> !(value instanceof String)))
                throw error(400, "INVALID_GUARD", "守卫属性值必须为字符串数组");
        result.put("guard", guard);
        // PLAT-05：对象形态的 errorSemantics（codePath/messagePath/retryableCodes）在注册期完成
        // 路径与类型校验，执行期只按已验证声明解释，畸形声明不可能流进运行时；v1.2 数组形态
        // （match 列表）原样透传，执行侧按平台默认映射处理。
        var errorSemantics = body.getOrDefault("errorSemantics", List.of());
        try {
            cn.agentplatform.skill.domain.ErrorSemantics.Declared.parse(errorSemantics);
        } catch (IllegalArgumentException malformed) {
            throw error(400, "INVALID_SKILL", malformed.getMessage());
        }
        result.put("errorSemantics", errorSemantics);
        result.put("returnSpec", body.getOrDefault("returnSpec", Map.of()));
        var constraints = object(body.getOrDefault("constraints", Map.of()));
        int max = ((Number) constraints.getOrDefault("outputMaxBytes", 1048576)).intValue();
        if (max < 1024 || max > 10485760)
            throw error(400, "INVALID_SKILL", "outputMaxBytes范围为1024至10485760");
        result.put("outputMaxBytes", max);
        result.put("reconciliation", body.getOrDefault("reconciliation", Map.of()));
        if (type.equals("MCP")) {
            Object raw = body.getOrDefault("toolName", invocation.get("toolName"));
            if (!(raw instanceof String name) || !name.matches("[A-Za-z0-9_.-]{1,128}"))
                throw error(400, "INVALID_SKILL", "MCP需要明确的toolName（1至128字符）");
            result.put("toolName", name);
        }
        if (body.containsKey("jobTemplate")) result.put("jobTemplate", body.get("jobTemplate"));
        // 来源与映射属于被摘要的不可变契约；草稿修改不丢来源，也不能携带额外执行字段。
        if (body.containsKey("httpMapping")) result.put("httpMapping", body.get("httpMapping"));
        if (body.containsKey("importSource")) {
            var source = object(body.get("importSource"));
            if ("ONTOLOGY".equals(source.get("kind"))) {
                // 来源坐标和摘要同属冻结契约；只接纳导入器的六字段，不能夹带其它执行声明。
                boolean valid =
                        source.keySet()
                                        .equals(
                                                Set.of(
                                                        "kind",
                                                        "toolCode",
                                                        "spaceCode",
                                                        "releaseId",
                                                        "version",
                                                        "contentDigest"))
                                && source.values().stream()
                                        .allMatch(value -> value instanceof String s && !s.isBlank())
                                && Objects.toString(source.get("contentDigest"), "")
                                        .matches("[a-f0-9]{64}");
                try {
                    UUID.fromString(Objects.toString(source.get("releaseId"), ""));
                } catch (IllegalArgumentException invalid) {
                    valid = false;
                }
                if (!valid)
                    throw error(400, "INVALID_IMPORT_SOURCE", "本体导入来源结构、releaseId或contentDigest无效");
            } else {
                if (!source.keySet()
                                .equals(
                                        Set.of(
                                                "kind",
                                                "operationId",
                                                "method",
                                                "path",
                                                "documentSha256"))
                        || !"OPENAPI".equals(source.get("kind"))
                        || !Objects.toString(source.get("documentSha256"), "")
                                .matches("[a-f0-9]{64}"))
                    throw error(400, "INVALID_IMPORT_SOURCE", "导入来源结构无效");
                for (String field : List.of("operationId", "method", "path")) text(source, field);
            }
            result.put("importSource", source);
        }
        return result;
    }

    /**
     * PLAT-15（契约 §14.1）execution.approval 的注册期归一化。兼容口径（定死 D1）：缺省不落键 =
     * SINGLE_STEP，存量契约与执行行为零变化；显式 PREPARE_EXECUTE 才进入两段。两段只支持
     * HTTP 同步写技能（定死 D2）：prepare 必须声明无明文凭据的 POST 预演入口；risk 只允许
     * STANDARD/HIGH（定死 D4，高危判别以契约为准）。校验通过后 approval 原样随 execution 落库，
     * 进入语义摘要——发布门禁照常要求重测。
     */
    private static void normalizeApproval(Map<String, Object> execution, String type, String kind) {
        Object rawApproval = execution.get("approval");
        if (!(rawApproval instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            // 缺省（或空对象）= SINGLE_STEP：不落键，存量契约与执行行为零变化。
            execution.remove("approval");
            return;
        }
        var approval = object(rawApproval);
        String mode = Objects.toString(approval.get("mode"), SkillRules.APPROVAL_MODE_SINGLE_STEP);
        if (!Set.of(SkillRules.APPROVAL_MODE_TWO_PHASE, SkillRules.APPROVAL_MODE_SINGLE_STEP)
                .contains(mode))
            throw error(
                    400,
                    "APPROVAL_MODE_INVALID",
                    "execution.approval.mode 只允许 PREPARE_EXECUTE 或 SINGLE_STEP，收到: " + mode);
        if (!SkillRules.APPROVAL_MODE_TWO_PHASE.equals(mode)) return;
        if (!"HTTP".equals(type) || !"SYNC".equals(kind))
            throw error(
                    400,
                    "APPROVAL_PREPARE_UNSUPPORTED",
                    "两段审批（PREPARE_EXECUTE）仅支持 HTTP 同步技能，当前: "
                            + type
                            + "/"
                            + kind);
        if (!Boolean.TRUE.equals(execution.get("sideEffect")))
            throw error(
                    400,
                    "APPROVAL_PREPARE_INVALID",
                    "两段审批只对有副作用的写操作有意义，请声明 execution.sideEffect=true");
        if (!(approval.get("prepare") instanceof Map<?, ?> declared)
                || Objects.toString(declared.get("endpoint"), "").isBlank())
            throw error(
                    400,
                    "APPROVAL_PREPARE_INVALID",
                    "两段审批需要 execution.approval.prepare.endpoint（业务侧无副作用预演入口）");
        try {
            SkillRules.endpoint(declared.get("endpoint").toString());
        } catch (IllegalArgumentException invalid) {
            throw error(400, "APPROVAL_PREPARE_INVALID", "prepare.endpoint 无效: " + invalid.getMessage());
        }
        if (declared.containsKey("httpMethod") && !"POST".equals(declared.get("httpMethod")))
            throw error(
                    400,
                    "APPROVAL_PREPARE_INVALID",
                    "prepare.httpMethod 只允许 POST（预演以请求体承载参数），收到: "
                            + declared.get("httpMethod"));
        Object risk = approval.get("risk");
        if (risk != null && !Set.of("STANDARD", SkillRules.APPROVAL_RISK_HIGH).contains(risk.toString()))
            throw error(
                    400,
                    "APPROVAL_RISK_INVALID",
                    "execution.approval.risk 只允许 STANDARD 或 HIGH，收到: " + risk);
    }

    /**
     * PROCESS 子契约的唯一解释器是 {@link ProcessContract#parse}。目录层不复写它的边界（命令首元素绝对路径、
     * 参数个数上限、超时范围、stdin/env/输出上限），只把规范化结果整理成可持久化的 JSON 形状：缺省值在注册期
     * 落库而不是执行期再补，可选字段未声明时不写空值，避免草稿里出现一串无意义的 null。
     */
    private static Map<String, Object> normalizedProcess(Map<String, Object> body) {
        ProcessContract contract = ProcessContract.parse(Json.tree(body.get("process")));
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("command", contract.command());
        normalized.put("timeoutMs", contract.timeout().toMillis());
        if (contract.workDirRoot() != null) normalized.put("workDirRoot", contract.workDirRoot());
        if (contract.stdinJson() != null)
            normalized.put("stdin", Json.mapper().convertValue(contract.stdinJson(), Object.class));
        if (!contract.allowedEnv().isEmpty()) normalized.put("env", contract.allowedEnv());
        normalized.put("maxOutputBytes", contract.maxOutputBytes());
        return normalized;
    }

    public Map<String, Object> publish(String code, int version) {
        return tx.execute(
                status -> {
                    main(code, true);
                    var candidate = version(code, version);
                    long id = ((Number) candidate.get("skillVersionId")).longValue();
                    if (candidate.get("status").equals("PUBLISHED")) return candidate;
                    if (!candidate.get("status").equals("DRAFT"))
                        throw error(409, "SKILL_STATE", "只有草稿可发布");
                    // PROCESS 的自检放在发布口：command 指向不存在文件、workDirRoot 不可达是配置错误，
                    // 放它进 PUBLISHED 只会把失败推迟到第一次真实调用（而且那次可能已经产生外部影响）。
                    processPrecheck(candidate);
                    var execution = object(candidate.get("execution"));
                    var guard = object(candidate.get("guard"));
                    if ("CONFIRM_REQUIRED".equals(candidate.get("riskLevel"))
                            && (!(execution.get("confirmRoles") instanceof Collection<?> roles)
                                    || roles.isEmpty()
                                    || roles.stream()
                                            .anyMatch(
                                                    r ->
                                                            !(r instanceof String role)
                                                                    || role.isBlank())))
                        throw error(409, "CONFIRM_ROLES_REQUIRED", "需确认的技能必须声明明确confirmRoles");
                    if (Boolean.TRUE.equals(execution.get("readonly"))
                            && object(guard.getOrDefault("attributes", Map.of())).isEmpty())
                        throw error(409, "QUERY_GUARD_REQUIRED", "查询技能必须声明守卫属性");
                    // T10：门禁从“只比 candidate_sha256”升级为“sha + semanticDigest + passed”三条同时成立。
                    // 理由：sha 覆盖整份契约，只改 description/title 也会让证据失效；只比 sha 又无法识别
                    // “拿旧字节的通过记录冒充新语义”。两个指纹分别证明“同一份草稿字节”和“同一份语义”。
                    // 回滚开关只放宽 digest 这一条，sha 与 passed 仍然必须满足（绝不静默放行未通过证据）。
                    Object digest = candidate.get("semanticDigest");
                    if (digest == null)
                        throw error(
                                409,
                                "CONNECTION_TEST_REQUIRED",
                                "当前草稿缺少语义摘要（semantic_digest 为空，疑似手工写入）；下一步：重新保存草稿后重跑连接测试");
                    String connectionSql =
                            "select count(*) from skill.connection_test where tenant_id=?"
                                    + " and skill_version_id=? and candidate_sha256=? and passed";
                    if (evidenceDigestGate) connectionSql += " and semantic_digest=?";
                    Integer connections =
                            evidenceDigestGate
                                    ? db.queryForObject(
                                            connectionSql,
                                            Integer.class,
                                            tenant(),
                                            id,
                                            candidate.get("sha256"),
                                            digest)
                                    : db.queryForObject(
                                            connectionSql,
                                            Integer.class,
                                            tenant(),
                                            id,
                                            candidate.get("sha256"));
                    if (connections == 0)
                        throw error(
                                409,
                                "CONNECTION_TEST_REQUIRED",
                                "当前草稿尚无通过的真实连接/受控调试报告；下一步：对 v"
                                        + version
                                        + " 重跑连接测试");
                    String acceptanceSql =
                            "select count(*) from skill.acceptance where tenant_id=?"
                                    + " and skill_version_id=? and candidate_sha256=? and passed"
                                    + " and hit_rate>=0.8";
                    if (evidenceDigestGate) acceptanceSql += " and semantic_digest=?";
                    Integer acceptances =
                            evidenceDigestGate
                                    ? db.queryForObject(
                                            acceptanceSql,
                                            Integer.class,
                                            tenant(),
                                            id,
                                            candidate.get("sha256"),
                                            digest)
                                    : db.queryForObject(
                                            acceptanceSql,
                                            Integer.class,
                                            tenant(),
                                            id,
                                            candidate.get("sha256"));
                    if (acceptances == 0)
                        throw error(
                                409,
                                "ACCEPTANCE_REQUIRED",
                                "当前草稿选型验收尚未通过；下一步：对 v" + version + " 重跑选型验收");
                    db.update(
                            "update skill.skill_version set status='ARCHIVED' where tenant_id=? and"
                                    + " skill_code=? and status='PUBLISHED'",
                            tenant(),
                            code);
                    db.update(
                            "update skill.skill_version set"
                                    + " status='PUBLISHED',published_by=?,published_at=now() where"
                                    + " tenant_id=? and id=?",
                            Actor.current().principalId(),
                            tenant(),
                            id);
                    db.update(
                            "update skill.skill set"
                                + " status='PUBLISHED',current_version_id=?,updated_at=now() where"
                                + " tenant_id=? and code=?",
                            id,
                            tenant(),
                            code);
                    return version(code, version);
                });
    }

    /**
     * T10 受控证据重绑定：把来源版本已通过的连接测试/选型验收复制到目标草稿。
     *
     * <p>不变量一：历史证据行一字不改。已发布版本受 {@code protect_published_contract()} 保护，本方法
     * 也不对 connection_test/acceptance 做任何 UPDATE/DELETE——只 insert 新行，report 里追加
     * {@code reboundFrom}，让“这份证据原本属于谁”在目标行自身可读，不依赖审计表 join。
     *
     * <p>不变量二：只有语义摘要相同才可复制。展示文案（description/title 等）改动让 sha 变化但
     * semanticDigest 不变，此时允许复用证据；端点/Schema/执行语义变化则摘要变化，必须重跑真实测试，
     * 返回 409 SEMANTIC_CHANGE_REQUIRES_RETEST 并给出下一步。
     *
     * <p>不变量三：同一目标草稿只绑定一次。审计表 UNIQUE(tenant_id,to_version_id) 是并发下的唯一
     * 裁决者：两个请求都过了预检时，数据库只允许一个提交，另一个得到 409 EVIDENCE_ALREADY_BOUND，
     * 而不是静默成功或复制两份证据。审计与复制在同一个事务里，任一步失败整体回滚。
     *
     * <p>复制行的 candidate_sha256 一律改写为目标草稿当前的 sha256（证据从此证明的是目标字节），
     * semantic_digest 保持来源摘要（两版本相等才允许走到这里）；旧 report 保留，另加 reboundFrom。
     */
    public Map<String, Object> rebindEvidence(String code, int version, Map<String, Object> body) {
        if (!(body.get("fromVersion") instanceof Number from) || from.longValue() < 1)
            throw error(400, "INVALID_REBIND", "fromVersion 必须是正整数版本号");
        int fromVersion = from.intValue();
        String reason = Objects.toString(body.get("reason"), "").trim();
        if (reason.length() < 10 || reason.length() > 500)
            throw error(400, "INVALID_REBIND", "reason 需为10至500字符，写明为什么语义没有变化");
        Actor actor = Actor.current();
        return tx.execute(
                status -> {
                    main(code, true);
                    var target = version(code, version);
                    long targetId = ((Number) target.get("skillVersionId")).longValue();
                    if (!"DRAFT".equals(target.get("status")))
                        throw error(409, "SKILL_STATE", "只有草稿可重绑定证据；下一步：先保存为目标草稿");
                    if (fromVersion == version)
                        throw error(400, "INVALID_REBIND", "来源版本不能是目标版本本身");
                    var source = version(code, fromVersion);
                    long sourceId = ((Number) source.get("skillVersionId")).longValue();
                    String targetDigest = Objects.toString(target.get("semanticDigest"), "");
                    String sourceDigest = Objects.toString(source.get("semanticDigest"), "");
                    if (targetDigest.isBlank() || !targetDigest.equals(sourceDigest))
                        throw error(
                                409,
                                "SEMANTIC_CHANGE_REQUIRES_RETEST",
                                "来源版本 v"
                                        + fromVersion
                                        + " 与目标草稿 v"
                                        + version
                                        + " 的语义摘要不同，旧证据不能为新契约放行；下一步：对该版本重跑连接测试与选型验收");
                    String at = java.time.Instant.now().toString();
                    try {
                        db.update(
                                "insert into"
                                    + " skill.evidence_rebind(tenant_id,skill_code,from_version_id,to_version_id,semantic_digest,reason,operator)"
                                    + " values(?,?,?,?,?,?,?)",
                                tenant(),
                                code,
                                sourceId,
                                targetId,
                                targetDigest,
                                reason,
                                actor.principalId());
                    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                        // 唯一约束比“先查再插”更可靠：并发时只有一个事务能提交，其余显式失败。
                        throw error(
                                409,
                                "EVIDENCE_ALREADY_BOUND",
                                "目标草稿已绑定过证据，不能重复绑定；下一步：直接发布，或修改契约后重新测试");
                    }
                    var reboundFrom =
                            Map.of(
                                    "version", fromVersion,
                                    "sha256", Objects.toString(source.get("sha256"), ""),
                                    "at", at,
                                    "operator", actor.principalId(),
                                    "reason", reason);
                    int connections =
                            db.update(
                                    "insert into"
                                        + " skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report,semantic_digest)"
                                        + " select tenant_id,?,?,true,report||jsonb_build_object('reboundFrom',?::jsonb),?"
                                        + " from skill.connection_test where tenant_id=?"
                                        + " and skill_version_id=? and passed and semantic_digest=?",
                                    targetId,
                                    target.get("sha256"),
                                    Json.write(reboundFrom),
                                    targetDigest,
                                    tenant(),
                                    sourceId,
                                    sourceDigest);
                    // acceptance 的主键是业务 id（sa_...），复制必须换新 id，否则同租户下必然主键冲突；
                    // 原 id 仍留在旧行，新行的 report.reboundFrom 记录来源版本，可反查。
                    int acceptances = 0;
                    for (var evidence :
                            db.queryForList(
                                    "select id,hit_rate,report from skill.acceptance where tenant_id=?"
                                            + " and skill_version_id=? and passed and hit_rate>=0.8"
                                            + " and semantic_digest=?",
                                    tenant(),
                                    sourceId,
                                    sourceDigest)) {
                        var report =
                                new LinkedHashMap<String, Object>(
                                        Json.read(
                                                Objects.toString(evidence.get("report"), "{}"),
                                                Map.class));
                        report.put("reboundFrom", reboundFrom);
                        db.update(
                                "insert into"
                                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report,semantic_digest)"
                                    + " values(?,?,?,?,true,?,?::jsonb,?)",
                                "sa_" + UUID.randomUUID().toString().replace("-", ""),
                                tenant(),
                                targetId,
                                target.get("sha256"),
                                evidence.get("hit_rate"),
                                Json.write(report),
                                targetDigest);
                        acceptances++;
                    }
                    if (connections == 0 || acceptances == 0)
                        throw error(
                                409,
                                "EVIDENCE_INCOMPLETE",
                                "来源版本缺少可复用的通过证据（连接测试与选型验收必须都存在）；下一步：对该版本重跑连接测试与选型验收");
                    return Map.of(
                            "rebound", true,
                            "fromVersion", fromVersion,
                            "semanticDigest", targetDigest,
                            "connectionTest", true,
                            "acceptance", true);
                });
    }

    /**
     * PLAT-21c 发布预检（方案 §4.4–§4.6）。只检查“静态可判定”的事项：command[0] 是绝对路径、
     * 指向存在的普通文件且可执行（T21d 补上可执行位校验）、白名单命中（已配置时）、workDirRoot
     * 是绝对路径且可创建、timeoutMs 落在 1..1800000（由 {@link ProcessContract#parse} 强制）、
     * outputSchema 能被 {@link SkillRules#schema} 加载。
     *
     * <p>这里不做 true 值校验（跑一次进程），因为发布动作不应产生外部副作用；真实可用性由连接/受控
     * 调试报告与选型验收负责。失败统一 400：调用方需要改契约，重试发布没有意义。
     */
    void processPrecheck(Map<String, Object> candidate) {
        if (!"PROCESS".equals(candidate.get("type"))) return;
        var raw = Json.tree(candidate.get("process"));
        // 先按原始 JSON 检查路径字段，再交给 ProcessContract.parse：parse 的 400 语义是
        // “契约字段非法”，而发布口需要明确区分“契约语法没问题但指向的文件不可用”。
        precheckCommand(raw.get("command"));
        var workDirRoot = raw.get("workDirRoot");
        if (workDirRoot != null && workDirRoot.isTextual())
            precheckWorkDirRoot(workDirRoot.asText());
        ProcessContract contract = ProcessContract.parse(raw);
        // 发布是最后的人类可读关口：白名单在注册期之后收紧的情况在这里再挡一次。
        if (!ProcessWhitelist.contains(allowedExecutables, contract.command().get(0)))
            throw error(
                    400,
                    "PROCESS_EXECUTABLE_FORBIDDEN",
                    "可执行文件不在白名单 agent.process.allowed-executables 内: "
                            + contract.command().get(0));
        if (contract.hasOutputSchema()) {
            try {
                SkillRules.schema(
                        Json.mapper().convertValue(contract.outputSchema(), Object.class));
            } catch (IllegalArgumentException ex) {
                throw precheckError("outputSchema 无效: " + ex.getMessage());
            }
        }
    }

    private void precheckCommand(JsonNode command) {
        if (command == null || !command.isArray() || command.isEmpty()) return;
        JsonNode first = command.get(0);
        if (!first.isTextual()) return;
        Path executable;
        try {
            executable = Path.of(first.asText());
        } catch (InvalidPathException ex) {
            throw precheckError("command[0] 不是合法的路径: " + first.asText());
        }
        if (!executable.isAbsolute())
            throw precheckError("command[0] 必须是绝对路径，不依赖 PATH 解析: " + executable);
        if (!Files.exists(executable))
            throw precheckError("command[0] 指向的文件不存在: " + executable);
        if (!Files.isRegularFile(executable))
            throw precheckError("command[0] 必须是普通文件（可执行文件或脚本）: " + executable);
        // T21d（契约 §4.3）：补可执行位校验。Windows 没有独立 exec 位，JDK 对普通文件按 ACL
        // 判定（正常都返回 true）；Unix 上能拦住“传了脚本但忘了 chmod +x”的配置错误。
        if (!Files.isExecutable(executable))
            throw precheckError("command[0] 不可执行（缺少可执行权限）: " + executable);
    }

    private void precheckWorkDirRoot(String workDirRoot) {
        if (workDirRoot == null) return;
        Path dir;
        try {
            dir = Path.of(workDirRoot);
        } catch (InvalidPathException ex) {
            throw precheckError("workDirRoot 不是合法的路径: " + workDirRoot);
        }
        if (!dir.isAbsolute()) throw precheckError("workDirRoot 必须是绝对路径: " + dir);
        if (Files.exists(dir)) {
            if (!Files.isDirectory(dir)) throw precheckError("workDirRoot 存在但不是目录: " + dir);
            return;
        }
        // 不在这里真的建目录：发布事务回滚会留下空目录；只验证最近的已存在祖先可写即可。
        Path ancestor = dir.getParent();
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null || !Files.isDirectory(ancestor) || !Files.isWritable(ancestor))
            throw precheckError("workDirRoot 及其祖先都不可创建: " + dir);
        try {
            Path probe = Files.createTempDirectory(ancestor, ".precheck-");
            Files.deleteIfExists(probe);
        } catch (IOException ex) {
            throw precheckError("workDirRoot 不可创建: " + dir + " (" + ex.getMessage() + ")");
        }
    }

    private static PlatformException precheckError(String message) {
        return error(400, "PROCESS_PRECHECK_FAILED", message);
    }

    public void delete(String code) {
        tx.executeWithoutResult(
                status -> {
                    var entity = main(code, true);
                    if (!entity.get("status").equals("DRAFT")
                            || versions(code).stream()
                                    .anyMatch(v -> !v.get("status").equals("DRAFT")))
                        throw error(409, "SKILL_STATE", "只能删除从未发布的技能草稿");
                    db.update(
                            "delete from skill.connection_test t using skill.skill_version v where"
                                    + " t.tenant_id=? and v.tenant_id=t.tenant_id and"
                                    + " v.id=t.skill_version_id and v.skill_code=?",
                            tenant(),
                            code);
                    db.update(
                            "delete from skill.acceptance t using skill.skill_version v where"
                                    + " t.tenant_id=? and v.tenant_id=t.tenant_id and"
                                    + " v.id=t.skill_version_id and v.skill_code=?",
                            tenant(),
                            code);
                    db.update(
                            "delete from skill.skill_version where tenant_id=? and skill_code=? and"
                                    + " status='DRAFT'",
                            tenant(),
                            code);
                    db.update(
                            "delete from skill.skill where tenant_id=? and code=?", tenant(), code);
                });
    }

    public Object references(String code) {
        main(code, false);
        return clients.get(appUrl + "/internal/skills/" + code + "/references");
    }

    public Map<String, Object> disable(String code) {
        if (!((com.fasterxml.jackson.databind.JsonNode) references(code)).isEmpty())
            throw error(409, "SKILL_REFERENCED", "技能存在应用版本引用，不能停用");
        db.update(
                "update skill.skill set status='DISABLED',updated_at=now() where tenant_id=? and"
                        + " code=?",
                tenant(),
                code);
        return get(code, null);
    }
}
