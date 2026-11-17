package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SkillClients;
import cn.agentplatform.skill.infra.StubMcpTransport;
import cn.agentplatform.skill.infra.StubMcpTransport.Scenario;
import cn.agentplatform.skill.infra.StubMcpTransport.StubPage;
import cn.agentplatform.skill.infra.McpTransport.Categories;
import cn.agentplatform.skill.infra.McpTransport.McpHandshake;
import cn.agentplatform.skill.infra.McpTransport.McpToolDescriptor;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A3-6 MCP 连接档案闭环验证（skill V322；库稿 §4.9、接口稿 §5.6、主方案 §6.3、台账 §7.4 A3-6）。
 * 专用库 agent_platform_v322_test（drop/create，不触碰共享库与其他 v3xx 库）：
 *
 * <ul>
 *   <li>V322 双路径——空库全链 migrate 到 322（两表/revision 列/快照复合键/枚举/CHECK/不可变与
 *       单调触发器/行形状负例）+ V321 存量升级路径（存量插件数据不动、新表随链尾交付）；</li>
 *   <li>连接登记 revision=1、配置变更 revision+1、租户与角色边界；</li>
 *   <li>连接测试三分支（OK/鉴权失败/超时，StubMcpTransport）+ 修订变更后迟到探针结果拦截丢弃；</li>
 *   <li>发现批次串行——完整批次才发布目录（分页不完整不生效）、失败保留上一代、批次号单调；</li>
 *   <li>旧修订/旧批次/停用连接的发布一律 409 拦截，目录不覆盖；</li>
 *   <li>差异 ADDED/CHANGED/REMOVED 三态 + review 生成技能草稿 skillDraftRef（含幂等与冲突）。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class McpConnectionTest {
    private static final String DATABASE = "agent_platform_v322_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
    private static final String TENANT = "mcp-v322-tests";

    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;
    private static StubMcpTransport stub;

    private McpConnectionRegistry registry;
    private SkillCatalog catalog;

    @BeforeAll
    static void prepareDedicatedDatabase() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        // 专用库 drop/create（任务纪律）：FORCE 清掉遗留连接，不触碰共享库与其他 v3xx 库。
        admin.execute("drop database if exists " + DATABASE + " with (force)");
        admin.execute("create database " + DATABASE);
        var ds = new DriverManagerDataSource(HOST, "agent_v12", password);
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        stub = new StubMcpTransport();
    }

    @BeforeEach
    void migrateFreshChainAndPrepareActor() {
        resetSchema();
        var result = migrateFull();
        System.out.println(
                "[A3-6 fresh] url="
                        + HOST
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("mcp-tester")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        stub.clear();
        catalog = new SkillCatalog(db, manager, mock(SkillClients.class), "http://unused");
        registry = new McpConnectionRegistry(db, manager, stub, catalog);
    }

    private static void resetSchema() {
        db.execute("drop schema if exists skill cascade");
    }

    private static org.flywaydb.core.api.output.MigrateResult migrateFull() {
        return Flyway.configure()
                .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static String schemaVersion() {
        return db.queryForObject(
                "select coalesce(max(version),'none') from skill.flyway_schema_history where success",
                String.class);
    }

    private void actor(String tenant, String... roles) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("mcp-tester")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of(roles))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    // ------------------------------------------------------------------ 夹具

    private Map<String, Object> connBody(String code, String baseEndpoint) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", "数据平台 MCP");
        body.put("transport", "SSE_LEGACY");
        body.put("baseEndpoint", baseEndpoint);
        body.put("timeoutMs", 5000);
        return body;
    }

    private String registerConnection() {
        String code = "conn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        registry.register(connBody(code, "http://127.0.0.1:1/mcp"));
        return code;
    }

    private void scenario(String code, McpHandshake handshake, StubPage... pages) {
        stub.scenario(TENANT, code, Scenario.of(handshake, pages));
    }

    /** Schema 用 JSON 字符串书写，逐个解析为 Map（digest 断言由服务层 canonical 归一负责）。 */
    private static McpToolDescriptor tool(String name, String schemaJson) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) Json.read(schemaJson, Object.class);
        return new McpToolDescriptor(name, "工具 " + name, schema);
    }

    /** 批次签发缝（发现受理）：返回签发批次号。 */
    private long allocate(String code) {
        return ((Number) registry.allocateBatch(code).get("issued_batch_no")).longValue();
    }

    private static final String SCHEMA_V1 =
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";
    private static final String SCHEMA_V2 =
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"},\"top\":{\"type\":\"integer\"}}}";

    private Map<String, Object> connectionRow(String code) {
        return db.queryForMap("select * from skill.mcp_connection where tenant_id=? and code=?", TENANT, code);
    }

    private List<Map<String, Object>> snapshotRows(String code, long batch) {
        return db.queryForList(
                "select tool_name,status,schema_digest,skill_draft_ref,reviewed_at"
                        + " from skill.mcp_tool_snapshot where tenant_id=? and connection_code=?"
                        + " and connection_revision=? and batch_no=? order by tool_name",
                TENANT,
                code,
                1,
                batch);
    }

    // ------------------------------------------------------------------ V322 双路径

    @Test
    void freshChainMigratesTo322AndDeliversMcpConnectionTables() {
        resetSchema();
        var result = migrateFull();
        System.out.println(
                "[A3-6 dual-path fresh] url="
                        + HOST
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        assertEquals("322", schemaVersion(), "skill schema 必须迁移到 V322");

        // 两表在位（库稿 §4.9）。
        var tables =
                db.queryForList(
                        "select tablename from pg_tables where schemaname='skill' order by tablename",
                        String.class);
        assertTrue(tables.contains("mcp_connection"), "mcp_connection 必须随 V322 交付");
        assertTrue(tables.contains("mcp_tool_snapshot"), "mcp_tool_snapshot 必须随 V322 交付");

        // mcp_connection：组合 PK + revision 列（NOT NULL DEFAULT 1）+ 台账批次列。
        var pkColumns =
                db.queryForList(
                        "select kcu.column_name from information_schema.table_constraints tc"
                                + " join information_schema.key_column_usage kcu"
                                + " on kcu.constraint_name=tc.constraint_name"
                                + " and kcu.constraint_schema=tc.constraint_schema"
                                + " where tc.constraint_schema='skill' and tc.table_name='mcp_connection'"
                                + " and tc.constraint_type='PRIMARY KEY' order by kcu.ordinal_position",
                        String.class);
        assertEquals(List.of("tenant_id", "code"), pkColumns, "mcp_connection 必须 (tenant_id,code) 组合 PK");
        var revisionDefault =
                db.queryForObject(
                        "select is_nullable||'|'||coalesce(column_default,'') from information_schema.columns"
                                + " where table_schema='skill' and table_name='mcp_connection' and column_name='revision'",
                        String.class);
        assertTrue(revisionDefault.startsWith("NO|"), "revision 必须 NOT NULL");
        assertTrue(revisionDefault.contains("1"), "revision 必须默认 1: " + revisionDefault);
        for (String column :
                new String[] {"batch_no", "issued_batch_no", "last_test_at", "last_test_result",
                        "endpoint_profile_ref", "base_endpoint", "secret_ref", "timeout_ms"})
            assertTrue(
                    db.queryForObject(
                            "select count(*) from information_schema.columns where table_schema='skill'"
                                    + " and table_name='mcp_connection' and column_name=?",
                            Integer.class,
                            column)
                            == 1,
                    "mcp_connection 缺列: " + column);

        // mcp_tool_snapshot：五成员复合 PK（含租户、修订、批次，库稿 §4.9 修订绑定/批次串行）。
        var snapshotPk =
                db.queryForList(
                        "select kcu.column_name from information_schema.table_constraints tc"
                                + " join information_schema.key_column_usage kcu"
                                + " on kcu.constraint_name=tc.constraint_name"
                                + " and kcu.constraint_schema=tc.constraint_schema"
                                + " where tc.constraint_schema='skill' and tc.table_name='mcp_tool_snapshot'"
                                + " and tc.constraint_type='PRIMARY KEY' order by kcu.ordinal_position",
                        String.class);
        assertEquals(
                List.of("tenant_id", "connection_code", "connection_revision", "batch_no", "tool_name"),
                snapshotPk,
                "快照复合 PK 必须含租户/连接/修订/批次/工具名");

        // CHECK 枚举与形状。
        assertEnum("mcp_connection", "mcp_connection_transport_check", "STREAMABLE_HTTP", "SSE_LEGACY");
        assertEnum("mcp_connection", "mcp_connection_status_check", "DRAFT", "ACTIVE", "DISABLED");
        assertEnum(
                "mcp_tool_snapshot",
                "mcp_tool_snapshot_status_check",
                "AVAILABLE",
                "CHANGED",
                "REMOVED",
                "PENDING_REVIEW",
                "AUTHORIZED");
        String digestShape =
                constraintDef("mcp_tool_snapshot_schema_digest_check", "mcp_tool_snapshot");
        assertNotNull(digestShape, "schema_digest 形状 CHECK 缺失");
        String schemaCap = constraintDef("mcp_tool_snapshot_schema_check", "mcp_tool_snapshot");
        assertTrue(schemaCap.contains("262144"), "快照 schema 256KB 上限缺失");
        assertNotNull(
                constraintDef("mcp_connection_endpoint_choice", "mcp_connection"),
                "端点二选一 CHECK 缺失");
        assertNotNull(
                constraintDef("mcp_connection_batch_order", "mcp_connection"),
                "批次单调 CHECK 缺失");
        assertNotNull(
                constraintDef("mcp_tool_snapshot_authorized_has_ref", "mcp_tool_snapshot"),
                "AUTHORIZED 携带 ref CHECK 缺失");
        assertNotNull(
                constraintDef("mcp_tool_snapshot_ref_state", "mcp_tool_snapshot"),
                "ref 状态投影 CHECK 缺失");

        // 触发器在位：快照不可变 + 连接单调/禁删。
        var triggers =
                db.queryForList(
                        "select tgname from pg_trigger where tgrelid in"
                                + " (select c.oid from pg_class c join pg_namespace n on n.oid=c.relnamespace"
                                + " where n.nspname='skill' and c.relname in ('mcp_connection','mcp_tool_snapshot'))"
                                + " and not tgisinternal",
                        String.class);
        assertTrue(triggers.contains("immutable_mcp_tool_snapshot"), "快照不可变触发器缺失");
        assertTrue(triggers.contains("mcp_connection_monotonic_guard"), "连接单调护栏触发器缺失");

        // 行形状负例：枚举、端点二选一、超时、批次序、摘要形状、schema 大小与形状、审核投影。
        String digest = "c".repeat(64);
        db.update(
                "insert into skill.mcp_connection(tenant_id,code,name,transport,base_endpoint,"
                        + "timeout_ms,created_by) values('v322_neg','neg_ok','连接','SSE_LEGACY',"
                        + "'http://127.0.0.1:1/mcp',1000,'tester')");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_connection(tenant_id,code,name,transport,base_endpoint,"
                                        + "timeout_ms,created_by) values('v322_neg','neg_t','连接','HTTP',"
                                        + "'http://127.0.0.1:1/mcp',1000,'tester')"),
                "transport 越界值必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_connection(tenant_id,code,name,transport,"
                                        + "timeout_ms,created_by) values('v322_neg','neg_e1','连接','SSE_LEGACY',"
                                        + "1000,'tester')"),
                "端点二选一：两者皆空必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_connection(tenant_id,code,name,transport,base_endpoint,"
                                        + "endpoint_profile_ref,timeout_ms,created_by) values('v322_neg','neg_e2',"
                                        + "'连接','SSE_LEGACY','http://127.0.0.1:1/mcp','profile_1',1000,'tester')"),
                "端点二选一：两者同设必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_connection(tenant_id,code,name,transport,base_endpoint,"
                                        + "timeout_ms,created_by) values('v322_neg','neg_to','连接','SSE_LEGACY',"
                                        + "'http://127.0.0.1:1/mcp',0,'tester')"),
                "timeout_ms<=0 必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_connection(tenant_id,code,name,transport,base_endpoint,"
                                        + "timeout_ms,status,created_by) values('v322_neg','neg_st','连接','SSE_LEGACY',"
                                        + "'http://127.0.0.1:1/mcp',1000,'PAUSED','tester')"),
                "status 越界值必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "update skill.mcp_connection set batch_no=1"
                                        + " where tenant_id='v322_neg' and code='neg_ok'"),
                "batch_no > issued_batch_no 必须被批次序 CHECK 拒绝");
        db.update(
                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,connection_revision,"
                        + "batch_no,tool_name,schema,schema_digest) values('v322_neg','neg_ok',1,1,'t1',"
                        + "'{}'::jsonb,?)",
                digest);
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,connection_revision,"
                                        + "batch_no,tool_name,schema,schema_digest) values('v322_neg','neg_ok',1,1,'t2',"
                                        + "'{}'::jsonb,'not-hex')"),
                "schema_digest 非法形状必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,connection_revision,"
                                        + "batch_no,tool_name,schema,schema_digest) values('v322_neg','neg_ok',1,1,'t3',"
                                        + "'[]'::jsonb,?)",
                                digest),
                "快照 schema 必须为 JSON 对象");
        String big = "{\"pad\":\"" + "x".repeat(262144) + "\"}";
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,connection_revision,"
                                        + "batch_no,tool_name,schema,schema_digest) values('v322_neg','neg_ok',1,1,'t4',"
                                        + "?::jsonb,?)",
                                big,
                                digest),
                "快照 schema 超过 256KB 必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,connection_revision,"
                                        + "batch_no,tool_name,schema,schema_digest,status) values('v322_neg','neg_ok',1,1,"
                                        + "'t5','{}'::jsonb,?,'PENDING')",
                                digest),
                "快照 status 越界值必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,connection_revision,"
                                        + "batch_no,tool_name,schema,schema_digest) values('v322_neg','neg_ok',1,0,'t6',"
                                        + "'{}'::jsonb,?)",
                                digest),
                "快照 batch_no<=0 必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "update skill.mcp_tool_snapshot set skill_draft_ref='mcp__x__y'"
                                        + " where tool_name='t1'"),
                "skill_draft_ref 只能伴随 AUTHORIZED/REMOVED 出现");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "update skill.mcp_tool_snapshot set status='AUTHORIZED'"
                                        + " where tool_name='t1'"),
                "AUTHORIZED 必须携带 skill_draft_ref");

        // 触发器行为：快照身份字段不可变、行禁删；连接修订/批次单调、行禁删。
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "update skill.mcp_tool_snapshot set schema='{\"tampered\":true}'::jsonb"
                                        + " where tool_name='t1'"),
                "快照 schema 必须不可改");
        assertThrows(
                DataAccessException.class,
                () -> db.update("delete from skill.mcp_tool_snapshot where tool_name='t1'"),
                "快照行不可删除");
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "update skill.mcp_connection set revision=0"
                                        + " where tenant_id='v322_neg' and code='neg_ok'"),
                "revision 递减必须被单调触发器拒绝");
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "delete from skill.mcp_connection where tenant_id='v322_neg' and code='neg_ok'"),
                "连接行不可删除（停用走 DISABLED）");
    }

    @Test
    void legacyV321UpgradePathAddsMcpTablesWithoutTouchingExistingData() {
        resetSchema();
        migrateTo("321");
        System.out.println("[A3-6 dual-path legacy] url=" + HOST + " stepped to V321 (存量形状：无 MCP 两表)");
        assertFalse(
                db.queryForList(
                                "select tablename from pg_tables where schemaname='skill' and tablename in"
                                        + " ('mcp_connection','mcp_tool_snapshot')",
                                String.class)
                        .contains("mcp_connection"),
                "V321 不应已有 MCP 连接表");
        db.update(
                "insert into skill.plugin_source(tenant_id,code,name,source_type,location,created_by)"
                        + " values('v322_legacy','legacy_src','来源','DIRECTORY','dir://x','tester')");
        migrateFull();
        assertEquals("322", schemaVersion(), "存量升级必须到达 V322");
        var tables =
                db.queryForList(
                        "select tablename from pg_tables where schemaname='skill' and tablename in"
                                + " ('mcp_connection','mcp_tool_snapshot')",
                        String.class);
        assertTrue(tables.contains("mcp_connection"), "存量升级后 mcp_connection 必须在位");
        assertTrue(tables.contains("mcp_tool_snapshot"), "存量升级后 mcp_tool_snapshot 必须在位");
        Long count =
                db.queryForObject(
                        "select count(*) from skill.plugin_source where tenant_id='v322_legacy'",
                        Long.class);
        assertEquals(1L, count, "V321 存量插件数据不受 V322 迁移影响");
    }

    // ------------------------------------------------------------------ 登记 / 配置变更 / 边界

    @Test
    void registrationStartsAtRevisionOneAndConfigChangeIncrementsRevision() {
        String code = registerConnection();
        var view = registry.connection(code);
        assertEquals(1, ((Number) view.get("revision")).intValue(), "登记即 revision=1");
        assertEquals("DRAFT", view.get("status"));
        assertEquals(0L, ((Number) view.get("batchNo")).longValue());
        assertEquals("SSE_LEGACY", view.get("transport"));
        assertEquals(Boolean.FALSE, view.get("secretConfigured"), "凭据只回已配置标记");
        assertFalse(String.valueOf(view).contains("secret_ref"), "opaque secret_ref 不出现在回包");

        // 配置变更：端点变更 revision+1；仅名称变更不动修订号。
        registry.updateConfig(code, Map.of("baseEndpoint", "http://127.0.0.1:2/mcp"));
        assertEquals(2, ((Number) registry.connection(code).get("revision")).intValue(), "端点变更 revision+1");
        registry.updateConfig(code, Map.of("name", "改名"));
        assertEquals(2, ((Number) registry.connection(code).get("revision")).intValue(), "名称变更不动修订号");
        // 停用/启用不动修订号。
        registry.updateConfig(code, Map.of("status", "DISABLED"));
        assertEquals("DISABLED", registry.connection(code).get("status"));
        assertEquals(2, ((Number) registry.connection(code).get("revision")).intValue());
        // 停用连接不受理测试与发现。
        var disabled = assertThrows(PlatformException.class, () -> registry.test(code));
        assertEquals(409, disabled.status());
        assertEquals("MCP_CONNECTION_DISABLED", disabled.code());
        assertEquals(
                409, assertThrows(PlatformException.class, () -> registry.discover(code)).status());
        registry.updateConfig(code, Map.of("status", "ACTIVE"));
        assertEquals("ACTIVE", registry.connection(code).get("status"));

        // 登记负例：重复编码、非法传输、端点二选一、非法凭据引用、超时越界。
        var duplicate = connBody(code, "http://127.0.0.1:1/mcp");
        var dupError = assertThrows(PlatformException.class, () -> registry.register(duplicate));
        assertEquals(409, dupError.status());
        assertEquals("MCP_CONNECTION_CONFLICT", dupError.code());
        var badTransport = connBody("conn_" + UUID.randomUUID().toString().substring(0, 8), "http://x");
        badTransport.put("transport", "HTTP");
        assertEquals(400, assertThrows(PlatformException.class, () -> registry.register(badTransport)).status());
        var bothEndpoints = connBody("conn_" + UUID.randomUUID().toString().substring(0, 8), "http://x");
        bothEndpoints.put("endpointProfileRef", "profile_1");
        assertEquals(400, assertThrows(PlatformException.class, () -> registry.register(bothEndpoints)).status());
        var noEndpoint = connBody("conn_" + UUID.randomUUID().toString().substring(0, 8), null);
        noEndpoint.remove("baseEndpoint");
        assertEquals(400, assertThrows(PlatformException.class, () -> registry.register(noEndpoint)).status());
        var badSecret = connBody("conn_" + UUID.randomUUID().toString().substring(0, 8), "http://x");
        badSecret.put("secretRef", "password123");
        assertEquals(400, assertThrows(PlatformException.class, () -> registry.register(badSecret)).status());
        var badTimeout = connBody("conn_" + UUID.randomUUID().toString().substring(0, 8), "http://x");
        badTimeout.put("timeoutMs", 50);
        assertEquals(400, assertThrows(PlatformException.class, () -> registry.register(badTimeout)).status());

        // 端点引用形态可登记（库稿 §4.9），但按次建连解析随接入卡——测试/发现明确拒绝而非静默假成功。
        String profileCode = "conn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var profileBody = connBody(profileCode, null);
        profileBody.remove("baseEndpoint");
        profileBody.put("endpointProfileRef", "profile_1");
        registry.register(profileBody);
        var profileError = assertThrows(PlatformException.class, () -> registry.test(profileCode));
        assertEquals(409, profileError.status());
        assertEquals("MCP_CONNECTION_PROFILE_UNRESOLVED", profileError.code());
        assertEquals(
                "MCP_CONNECTION_PROFILE_UNRESOLVED",
                assertThrows(PlatformException.class, () -> registry.discover(profileCode)).code());

        // 角色与租户边界。
        actor(TENANT, "EDITOR");
        assertEquals(
                403, assertThrows(PlatformException.class, () -> registry.connection(code)).status());
        actor(TENANT + "-other", "ADMIN");
        assertEquals(
                404, assertThrows(PlatformException.class, () -> registry.connection(code)).status());
        actor(TENANT, "ADMIN", "EDITOR", "SERVICE");
    }

    // ------------------------------------------------------------------ 连接测试三分支

    @Test
    void testReportsOkAuthFailureAndTimeoutBranchesWithRevisionBinding() {
        String code = registerConnection();

        // OK：握手+完整列举都过，探针投影落档案。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(
                        List.of(tool("search_rows", SCHEMA_V1), tool("export_report", SCHEMA_V1)),
                        null));
        var ok = registry.test(code);
        assertEquals("OK", ok.get("result"));
        assertEquals(Categories.OK, ok.get("category"));
        assertEquals(2, ((Number) ok.get("toolCount")).intValue());
        assertEquals(Boolean.FALSE, ok.get("discarded"));
        var stored =
                (Map<?, ?>) Json.read(connectionRow(code).get("last_test_result").toString(), Object.class);
        assertEquals("OK", stored.get("result"), "OK 探针投影落 last_test_result");
        assertEquals(2, ((Number) stored.get("toolCount")).intValue());

        // 鉴权失败：握手失败类别回执。
        scenario(code, McpHandshake.fail(Categories.AUTH_FAILED, "凭据被拒"), StubPage.of(List.of(), null));
        var auth = registry.test(code);
        assertEquals("FAILED", auth.get("result"));
        assertEquals(Categories.AUTH_FAILED, auth.get("category"));
        stored = (Map<?, ?>) Json.read(connectionRow(code).get("last_test_result").toString(), Object.class);
        assertEquals(Categories.AUTH_FAILED, stored.get("category"), "失败类别落 last_test_result");

        // 超时：握手通过但列举中断（失败类别来自传输异常）。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(List.of(tool("search_rows", SCHEMA_V1)), "p1"),
                StubPage.failing(Categories.TIMEOUT, "分页超时"));
        var timeout = registry.test(code);
        assertEquals("FAILED", timeout.get("result"));
        assertEquals(Categories.TIMEOUT, timeout.get("category"));

        // 未登记场景：桩明确 UNREACHABLE，不做静默成功。
        String bare = registerConnection();
        var unreachable = registry.test(bare);
        assertEquals("FAILED", unreachable.get("result"));
        assertEquals(Categories.UNREACHABLE, unreachable.get("category"));

        // 修订绑定：探针在途期间配置变更（revision+1），迟到探针结果拦截丢弃。
        String racing = registerConnection();
        int revisionBefore = ((Number) connectionRow(racing).get("revision")).intValue();
        stub.scenario(
                TENANT,
                racing,
                Scenario.of(
                        McpHandshake.ok("2025-06-18", "stub"),
                        () ->
                                registry.updateConfig(
                                        racing, Map.of("baseEndpoint", "http://127.0.0.1:9/mcp")),
                        StubPage.of(List.of(tool("search_rows", SCHEMA_V1)), null)));
        var discarded = registry.test(racing);
        assertEquals("OK", discarded.get("result"));
        assertEquals(Boolean.TRUE, discarded.get("discarded"), "修订已变的迟到探针结果必须拦截丢弃");
        assertEquals("REVISION_CHANGED", discarded.get("discardReason"));
        assertEquals(
                revisionBefore + 1, ((Number) connectionRow(racing).get("revision")).intValue());
        assertNull(connectionRow(racing).get("last_test_result"), "被拦截的探针结果不落档案");
    }

    // ------------------------------------------------------------------ 发现批次串行

    @Test
    void discoveryPublishesOnlyCompleteBatchesAndKeepsPreviousCatalogOnFailure() {
        String code = registerConnection();

        // 失败发现（无目录）：批次号已签发但目录仍为空。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(List.of(tool("search_rows", SCHEMA_V1)), "p1"),
                StubPage.failing(Categories.TIMEOUT, "第二页超时"));
        var failed = registry.discover(code);
        assertEquals("FAILED", failed.get("status"));
        assertEquals(Categories.TIMEOUT, failed.get("category"));
        assertEquals(1L, ((Number) failed.get("batchNo")).longValue(), "失败发现同样消耗签发批次号");
        assertEquals(Boolean.TRUE, failed.get("catalogPreserved"), "失败保留上一代目录");
        assertEquals(0L, ((Number) registry.connection(code).get("batchNo")).longValue());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.mcp_tool_snapshot where connection_code=?", Integer.class, code),
                "失败发现不落任何快照行");

        // 分页链断裂（游标悬空）同样是"分页不完整"，整批作废。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(List.of(tool("search_rows", SCHEMA_V1)), "more"));
        var dangling = registry.discover(code);
        assertEquals("FAILED", dangling.get("status"));
        assertEquals(Categories.PROTOCOL_ERROR, dangling.get("category"));
        assertEquals(2L, ((Number) dangling.get("batchNo")).longValue());
        assertEquals(0L, ((Number) registry.connection(code).get("batchNo")).longValue());

        // 完整批次（两页走完）才发布目录：批次号随签发单调，首代目录 = 批次 3。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(
                        List.of(tool("search_rows", SCHEMA_V1), tool("export_report", SCHEMA_V1)), "p1"),
                StubPage.of(List.of(tool("purge_cache", SCHEMA_V1)), null));
        var first = registry.discover(code);
        assertEquals("SUCCEEDED", first.get("status"));
        assertEquals(3L, ((Number) first.get("batchNo")).longValue(), "完整批次才发布（签发序连续，失败不回退）");
        assertEquals(3, ((Number) first.get("toolCount")).intValue());
        assertEquals(List.of("export_report", "purge_cache", "search_rows"), first.get("added"));
        assertEquals(3L, ((Number) registry.connection(code).get("batchNo")).longValue(), "目录版本=最近完整发布批次");
        assertEquals(3, snapshotRows(code, 3).size(), "批次 3 快照行齐备（完整批次才发布）");

        // 分页不完整：批次 4 整批作废，目录停留在批次 3。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(List.of(tool("search_rows", SCHEMA_V1)), "p1"),
                StubPage.failing(Categories.TIMEOUT, "第二页超时"));
        var incomplete = registry.discover(code);
        assertEquals("FAILED", incomplete.get("status"));
        assertEquals(3L, ((Number) registry.connection(code).get("batchNo")).longValue(), "分页不完整不生效");
        assertEquals(3, snapshotRows(code, 3).size(), "上一代目录原样保留");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.mcp_tool_snapshot where connection_code=? and batch_no=4",
                        Integer.class,
                        code),
                "作废批次不留半批快照");

        // 批次单调：下一次成功发现批次号继续递增；差异 CHANGED/REMOVED 落库。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(
                        List.of(tool("search_rows", SCHEMA_V2), tool("health_check", SCHEMA_V1)), null));
        var second = registry.discover(code);
        assertEquals("SUCCEEDED", second.get("status"));
        assertEquals(5L, ((Number) second.get("batchNo")).longValue(), "批次号单调递增");
        assertEquals(List.of("health_check"), second.get("added"));
        assertEquals(List.of("search_rows"), second.get("changed"));
        assertEquals(List.of("export_report", "purge_cache"), second.get("removed"));
        assertEquals(
                "CHANGED",
                snapshotRows(code, 5).stream()
                        .filter(r -> r.get("tool_name").equals("search_rows"))
                        .findFirst().orElseThrow()
                        .get("status"));
        assertEquals(
                "REMOVED",
                snapshotRows(code, 3).stream()
                        .filter(r -> r.get("tool_name").equals("export_report"))
                        .findFirst().orElseThrow()
                        .get("status"),
                "上一代目录缺席工具标记 REMOVED（历史批次保留原始快照）");
    }

    @Test
    void staleRevisionAndOldBatchPublicationsAreIntercepted() {
        String code = registerConnection();
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(List.of(tool("search_rows", SCHEMA_V1), tool("export_report", SCHEMA_V1)), null));
        registry.discover(code);
        assertEquals(1L, ((Number) connectionRow(code).get("batch_no")).longValue());

        // 旧批次不覆盖新批次：批次 3 已发布后，迟到批次 2 发布被 409 拦截。
        long batch2 = allocate(code);
        long batch3 = allocate(code);
        assertEquals(2L, batch2);
        assertEquals(3L, batch3);
        registry.publishBatch(
                code, 1, batch3, List.of(tool("search_rows", SCHEMA_V1), tool("audit_log", SCHEMA_V1)));
        assertEquals(3L, ((Number) connectionRow(code).get("batch_no")).longValue());
        var stale =
                assertThrows(
                        PlatformException.class,
                        () ->
                                registry.publishBatch(
                                        code, 1, batch2, List.of(tool("legacy_tool", SCHEMA_V1))));
        assertEquals(409, stale.status());
        assertEquals("MCP_DISCOVERY_BATCH_STALE", stale.code(), "旧批次结果不覆盖目录");
        assertEquals(3L, ((Number) connectionRow(code).get("batch_no")).longValue(), "目录版本不变");
        assertEquals(
                List.of("audit_log", "search_rows"),
                snapshotRows(code, 3).stream()
                        .map(r -> r.get("tool_name").toString())
                        .collect(Collectors.toList()),
                "被拦截批次的快照行整体回滚");

        // 旧修订拦截丢弃：绑定旧修订的发现结果在发布端 409。
        long batch4 = allocate(code);
        registry.updateConfig(code, Map.of("baseEndpoint", "http://127.0.0.1:3/mcp"));
        assertEquals(2, ((Number) connectionRow(code).get("revision")).intValue(), "配置变更 revision+1");
        var staleRevision =
                assertThrows(
                        PlatformException.class,
                        () ->
                                registry.publishBatch(
                                        code, 1, batch4, List.of(tool("orphan_tool", SCHEMA_V1))));
        assertEquals(409, staleRevision.status());
        assertEquals("MCP_CONNECTION_REVISION_STALE", staleRevision.code(), "旧修订发现结果拦截丢弃");
        assertEquals(3L, ((Number) connectionRow(code).get("batch_no")).longValue(), "旧修订不得覆盖当前目录");

        // 停用连接使未完成批次失效：DISABLED 后发布 409。
        long batch5 = allocate(code);
        registry.updateConfig(code, Map.of("status", "DISABLED"));
        var disabled =
                assertThrows(
                        PlatformException.class,
                        () ->
                                registry.publishBatch(
                                        code, 2, batch5, List.of(tool("late_tool", SCHEMA_V1))));
        assertEquals(409, disabled.status());
        assertEquals("MCP_CONNECTION_DISABLED", disabled.code());
        assertEquals(3L, ((Number) connectionRow(code).get("batch_no")).longValue());

        // 库层兜底：batch_no/revision 递减被单调触发器拒绝。
        assertThrows(
                DataAccessException.class,
                () -> db.update("update skill.mcp_connection set batch_no=1 where code=?", code),
                "batch_no 递减必须被单调触发器拒绝");
    }

    // ------------------------------------------------------------------ 差异与审核

    @Test
    void diffShowsAddedChangedRemovedAndReviewGeneratesSkillDraft() {
        String code = registerConnection();
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(
                        List.of(tool("search_rows", SCHEMA_V1), tool("export.report", SCHEMA_V1)), null));
        registry.discover(code);

        // 第一代目录：全部 ADDED（无上一代）。
        var diff1 = registry.tools(code, true);
        var toolNames1 =
                ((List<Map<String, Object>>) (List<?>) diff1.get("tools"))
                        .stream().map(t -> t.get("toolName")).collect(Collectors.toList());
        assertEquals(List.of("export.report", "search_rows"), toolNames1);
        var diff1View = (Map<?, ?>) diff1.get("diff");
        assertEquals(List.of("export.report", "search_rows"), diff1View.get("added"));
        assertEquals(List.of(), diff1View.get("changed"));
        assertEquals(List.of(), diff1View.get("removed"));

        // 第二代：search_rows 变更、health_check 新增、export.report 移除。
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(
                        List.of(tool("search_rows", SCHEMA_V2), tool("health_check", SCHEMA_V1)), null));
        registry.discover(code);
        var diff2 = (Map<?, ?>) registry.tools(code, true).get("diff");
        assertEquals(List.of("health_check"), diff2.get("added"), "差异 ADDED");
        assertEquals(List.of("search_rows"), diff2.get("changed"), "差异 CHANGED");
        assertEquals(List.of("export.report"), diff2.get("removed"), "差异 REMOVED");

        // review APPROVE：差异审核生成技能草稿（现有入口），快照行 AUTHORIZED 冻结引用。
        var approved = registry.review(code, Map.of("toolName", "search_rows"));
        assertEquals("AUTHORIZED", approved.get("status"));
        String draftRef = approved.get("skillDraftRef").toString();
        assertEquals("mcp__" + code + "__search_rows", draftRef, "技能编码沿用 mcp__<连接>__<工具> 前缀约定");
        assertNotNull(approved.get("reviewedAt"));
        var row = snapshotRows(code, 2).stream()
                .filter(r -> r.get("tool_name").equals("search_rows"))
                .findFirst().orElseThrow();
        assertEquals("AUTHORIZED", row.get("status"));
        assertEquals(draftRef, row.get("skill_draft_ref"));

        // 生成的草稿确实经既有技能入口落库：DRAFT + type=MCP + 契约冻结工具与端点。
        var skillRow = db.queryForMap("select kind,name from skill.skill where tenant_id=? and code=?", TENANT, draftRef);
        assertEquals("TOOL", skillRow.get("kind"));
        var contractRow = db.queryForMap(
                "select contract from skill.skill_version where tenant_id=? and skill_code=? and version_no=1",
                TENANT,
                draftRef);
        var contract = (Map<?, ?>) Json.read(contractRow.get("contract").toString(), Object.class);
        assertEquals("MCP", contract.get("type"), "草稿契约为 MCP 执行契约");
        assertEquals("search_rows", contract.get("toolName"), "契约冻结远端工具名");
        assertEquals("http://127.0.0.1:1/mcp", contract.get("endpoint"), "契约冻结连接端点（按次建连调用沿用现有执行链路）");
        assertEquals(5000, ((Number) contract.get("timeoutMs")).intValue(), "契约继承连接超时");

        // 幂等：重复审核返回既有引用，不重复建草稿。
        var again = registry.review(code, Map.of("toolName", "search_rows"));
        assertEquals(draftRef, again.get("skillDraftRef"));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.skill where tenant_id=? and code=?",
                        Integer.class,
                        TENANT,
                        draftRef),
                "重复审核不重复建草稿");

        // 新增工具同样可审核生成草稿（首代目录全 ADDED 也要能走完闭环）。
        var addedReview = registry.review(code, Map.of("toolName", "health_check"));
        assertEquals("AUTHORIZED", addedReview.get("status"));
        assertEquals("mcp__" + code + "__health_check", addedReview.get("skillDraftRef"));

        // 移除工具不可审核；状态机 SUBMIT/REJECT/冲突分支。
        assertEquals(
                404,
                assertThrows(PlatformException.class, () -> registry.review(code, Map.of("toolName", "export.report")))
                        .status(),
                "已移除工具不在当前目录，审核 404");
        scenario(
                code,
                McpHandshake.ok("2025-06-18", "stub"),
                StubPage.of(List.of(tool("search_rows", SCHEMA_V1), tool("health_check", SCHEMA_V1)), null));
        registry.discover(code); // 批次 3：search_rows 再次变更
        var submit = registry.review(code, Map.of("toolName", "search_rows", "decision", "SUBMIT"));
        assertEquals("PENDING_REVIEW", submit.get("status"));
        var reject = registry.review(code, Map.of("toolName", "search_rows", "decision", "REJECT"));
        assertEquals("CHANGED", reject.get("status"), "驳回回 CHANGED 待审");
        var conflict =
                assertThrows(PlatformException.class, () -> registry.review(code, Map.of("toolName", "search_rows")));
        assertEquals(409, conflict.status());
        assertEquals("MCP_REVIEW_CONFLICT", conflict.code(), "同名草稿已存在，审核冲突明确暴露");
        assertEquals(
                400,
                assertThrows(
                                PlatformException.class,
                                () -> registry.review(code, Map.of("toolName", "search_rows", "decision", "MAYBE")))
                        .status());
        assertEquals(
                404,
                assertThrows(PlatformException.class, () -> registry.review(code, Map.of("toolName", "no_such_tool")))
                        .status(),
                "不在当前目录的工具审核 404");
    }

    // ------------------------------------------------------------------ 断言工具

    private static void assertEnum(String table, String constraint, String... values) {
        String def = constraintDef(constraint, table);
        assertNotNull(def, table + "." + constraint + " 缺失");
        for (String value : values)
            assertTrue(def.contains("'" + value + "'"), constraint + " 缺枚举值 " + value);
    }

    private static String constraintDef(String conname, String relname) {
        return db.queryForObject(
                "select pg_get_constraintdef(c.oid) from pg_constraint c"
                        + " join pg_class t on t.oid=c.conrelid"
                        + " where t.relnamespace = (select oid from pg_namespace where nspname='skill')"
                        + " and t.relname='"
                        + relname
                        + "' and c.conname='"
                        + conname
                        + "'",
                String.class);
    }
}
