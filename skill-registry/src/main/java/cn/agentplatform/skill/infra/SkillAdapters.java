package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.common.schema.SchemaWorkBudget;
import cn.agentplatform.skill.domain.ErrorSemantics;
import cn.agentplatform.skill.domain.ProcessContract;
import cn.agentplatform.skill.domain.SkillRules;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.OpenOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

@Component
public class SkillAdapters {
    private cn.agentplatform.skill.application.ReadOnlyToolCache readOnlyCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void readOnlyCache(cn.agentplatform.skill.application.ReadOnlyToolCache cache) { this.readOnlyCache = cache; }
    private IdentityMappingClient identityMapping;

    @org.springframework.beans.factory.annotation.Autowired
    void identityMapping(IdentityMappingClient client) { this.identityMapping = client; }

    private Map<String, String> mappedIdentityHeaders(Map<String, Object> contract) {
        if (!IdentityMappingClient.declared(contract)) return Map.of();
        if (identityMapping == null) throw IdentityMappingClient.missingClient();
        return identityMapping.headers(contract);
    }

    public record Raw(
            JsonNode structured,
            List<Map<String, Object>> fileRefs,
            String summary,
            JsonNode ontologyMeta,
            String cardPayloadJson,
            Validation validation,
            Map<String, Object> processFact) {
        public Raw(
                JsonNode structured,
                List<Map<String, Object>> refs,
                String summary,
                JsonNode ontology,
                String cardPayloadJson,
                Validation validation) {
            this(structured, refs, summary, ontology, cardPayloadJson, validation, null);
        }

        public Raw(
                JsonNode structured,
                List<Map<String, Object>> refs,
                String summary,
                JsonNode ontology,
                String cardPayloadJson) {
            this(structured, refs, summary, ontology, cardPayloadJson, null, null);
        }

        public Raw(
                JsonNode structured,
                List<Map<String, Object>> refs,
                String summary,
                JsonNode ontology) {
            this(structured, refs, summary, ontology, "", null, null);
        }
    }

    /**
     * 一次输出校验的可见结论（PLAT-07）：{@code mode=OK|DEGRADED}，降级时附
     * {@code warning=SCHEMA_BUDGET_EXCEEDED} 与估计工作量。它随工具结果 envelope 落
     * {@code skill.execution_fact.result}，调用方据此区分“严格通过”与“只做了顶层校验”。
     */
    public record Validation(String mode, String warning, long estimatedWork) {
        public static final String OK = "OK";
        public static final String DEGRADED = "DEGRADED";
        public static final String BUDGET_EXCEEDED = "SCHEMA_BUDGET_EXCEEDED";

        public static Validation of(SchemaWorkBudget.Admission admission) {
            Objects.requireNonNull(admission, "Schema准入结论不能为空");
            return admission.admitted()
                    ? new Validation(OK, null, admission.estimatedWork())
                    : new Validation(DEGRADED, BUDGET_EXCEEDED, admission.estimatedWork());
        }

        /** 通过时不写 warning 键：空告警会被前端读成“有告警但未说明”。 */
        public Map<String, Object> asMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("mode", mode);
            if (warning != null) map.put("warning", warning);
            map.put("estimatedWork", estimatedWork);
            return map;
        }
    }

    /**
     * PLAT-05：失败除了语义与诊断消息外，还携带远端业务码（code）与有界原文（raw），最终由
     * {@link #error(String)} 规范化成方案 §4.5 的七字段结构。code/raw 为 null 表示远端未提供。
     */
    public static class Failure extends RuntimeException {
        public final String semantic;
        public final boolean unknown;
        public final String code;
        public final String raw;

        public Failure(String semantic, boolean unknown, String message) {
            this(semantic, unknown, message, null, null);
        }

        public Failure(
                String semantic, boolean unknown, String message, String code, String raw) {
            super(message);
            this.semantic = semantic;
            this.unknown = unknown;
            this.code = code;
            this.raw = raw;
        }

        /** 七字段错误对象；traceId 由调用方按请求上下文显式传入，域模型不做 IO。 */
        public Map<String, Object> error(String traceId) {
            return ErrorSemantics.error(semantic, code, getMessage(), raw, traceId);
        }
    }

    /** Transient observation evidence accompanies a result; PG remains the adoption authority. */
    public record Observation(Raw raw, Map<String, Object> source) {
        public Observation {
            source = Map.copyOf(source);
        }
    }

    /**
     * PLAT-12：当前调用上下文的出站身份头。同步与异步两个发头点共用本方法，保证同一契约在两种
     * 调用形态下注入同一组身份头；主体不可用（无验签上下文、机器 workload 票据）时返回空表——
     * 跳过注入不伪造值，字段映射与序列化规则统一在 {@link SkillRules#identityHeaders}。
     */
    static Map<String, String> identityHeaders(Map<String, Object> contract) {
        SubjectSnapshot subject;
        try {
            subject = Actor.current().subject();
        } catch (PlatformException unresolvable) {
            // 401（无验签身份）/403（机器能力不映射为用户身份）都表示"没有可出站的用户主体"：
            // 身份头是补充可读身份而非鉴权依据，跳过比伪造服务账号身份诚实。
            return Map.of();
        }
        return SkillRules.identityHeaders(contract, subject);
    }

    /** 契约 errorSemantics 声明（PLAT-05）。历史脏数据按未声明处理，注册期校验负责挡新增。 */
    public static ErrorSemantics.Declared declaredMapping(Map<String, Object> contract) {
        try {
            return ErrorSemantics.Declared.parse(contract.get("errorSemantics"));
        } catch (IllegalArgumentException malformed) {
            return ErrorSemantics.Declared.NONE;
        }
    }

    /**
     * 错误响应原文的有界读取与业务码/原因提取（PLAT-05）。64KB 读取上限与提交确认一致；
     * 非 JSON、读失败、声明畸形一律按“远端未提供”处理，语义退回 HTTP 状态默认分类。
     */
    static ErrorSemantics.Remote remoteRejection(Map<String, Object> contract, InputStream body) {
        byte[] prefix;
        try {
            prefix = body.readNBytes(65537);
        } catch (IOException ignored) {
            return ErrorSemantics.Remote.NONE;
        }
        JsonNode parsed;
        try {
            parsed = ArtifactStore.readJson(new ByteArrayInputStream(prefix));
        } catch (Exception ignored) {
            return ErrorSemantics.Remote.NONE;
        }
        return ErrorSemantics.extract(parsed, declaredMapping(contract));
    }

    public static final class TerminalFailure extends Failure {
        public final Map<String, Object> source;

        TerminalFailure(Map<String, Object> source) {
            this("NON_RETRYABLE_FAILURE", "原算法Job已核实Failed；不表示未生效或允许重新发送", source);
        }

        TerminalFailure(String semantic, String message, Map<String, Object> source) {
            super(semantic, false, message);
            this.source = Map.copyOf(source);
        }
    }

    private final ArtifactStore artifacts;
    private final AlgorithmAdapter algorithm;
    private final HttpAsyncAdapter asyncHttp;
    private final ProcessAdapter process;
    private final AdapterRegistry registry;
    private final SkillRules.SchemaMode schemaMode;
    private final long maxExpandedWork;

    public SkillAdapters(ArtifactStore artifacts, AlgorithmAdapter algorithm) {
        this(artifacts, algorithm, null, null);
    }

    public SkillAdapters(
            ArtifactStore artifacts, AlgorithmAdapter algorithm, HttpAsyncAdapter asyncHttp) {
        this(artifacts, algorithm, asyncHttp, null);
    }

    /** 短构造保留给既有单测：输出校验按默认 DEGRADED 与共享展开上限运行。 */
    public SkillAdapters(
            ArtifactStore artifacts,
            AlgorithmAdapter algorithm,
            HttpAsyncAdapter asyncHttp,
            ProcessAdapter process) {
        this(
                artifacts,
                algorithm,
                asyncHttp,
                process,
                SkillRules.SchemaMode.DEGRADED.name(),
                SchemaWorkBudget.MAX_EXPANDED_WORK);
    }

    /**
     * 既有 6 参构造（PLAT-07 模式/预算开关）：注册表用内置单元——注册的单元类与 Spring 装配完全
     * 同一批（见 {@link AdapterRegistry#withPlatformDefaults}），不存在"测试构造 = 另一套分发"。
     */
    public SkillAdapters(
            ArtifactStore artifacts,
            AlgorithmAdapter algorithm,
            HttpAsyncAdapter asyncHttp,
            ProcessAdapter process,
            String schemaMode,
            long maxExpandedWork) {
        this(
                artifacts,
                algorithm,
                asyncHttp,
                process,
                AdapterRegistry.withPlatformDefaults(),
                schemaMode,
                maxExpandedWork);
    }

    /**
     * 容器注入的正式构造。PROCESS 是适配器的一支而不是旁路：只有装配了 {@link ProcessAdapter} 的进程才能执行
     * 本地进程技能，未装配时该类型按配置错误拒绝，而不是静默回退成别的调用方式。短构造保留给既有单测，
     * 它们只覆盖 HTTP/MCP/ALGORITHM。
     *
     * <p>PLAT-22C：kind→适配器分发改为 {@link AdapterRegistry} 查找——注册表是 Spring 装配的单一
     * bean（内置单元以 @Component 自动发现注册，外部新执行方式实现 {@link SkillAdapter} + @Component
     * 即接入，无需修改本类分发代码）。直接构造（既有单测）经 6 参构造落到内置注册表，两条路同一批单元类。
     *
     * <p>PLAT-07：输出校验模式与展开预算在启动时校验。非法值直接让进程起不来，而不是静默回退到
     * STRICT 或上限；{@code max-expanded-work} 只允许调低（1..8192），共享硬上限不能靠配置抬高。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SkillAdapters(
            ArtifactStore artifacts,
            AlgorithmAdapter algorithm,
            HttpAsyncAdapter asyncHttp,
            ProcessAdapter process,
            AdapterRegistry registry,
            @org.springframework.beans.factory.annotation.Value("${agent.schema.mode:DEGRADED}")
                    String schemaMode,
            @org.springframework.beans.factory.annotation.Value(
                            "${agent.schema.max-expanded-work:8192}")
                    long maxExpandedWork) {
        this.artifacts = artifacts;
        this.algorithm = algorithm;
        this.asyncHttp = asyncHttp;
        this.process = process;
        this.registry = registry;
        this.schemaMode = schemaMode(schemaMode);
        this.maxExpandedWork = expansionLimit(maxExpandedWork);
    }

    private static SkillRules.SchemaMode schemaMode(String value) {
        var text = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        try {
            return SkillRules.SchemaMode.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "agent.schema.mode 只能为 STRICT 或 DEGRADED，当前=" + value);
        }
    }

    private static long expansionLimit(long value) {
        if (value < 1 || value > SchemaWorkBudget.MAX_EXPANDED_WORK)
            throw new IllegalStateException(
                    "agent.schema.max-expanded-work 必须在 1..8192 之间，当前=" + value);
        return value;
    }

    /**
     * HTTP 异步形态判定（SUBMIT_QUERY/CALLBACK）：{@link HttpAsyncExecutionAdapter} 的 handles 路由与
     * {@link HttpSyncExecutionAdapter} 的互斥分流共用这一定义，执行编排（SkillExecution）的异步旁路
     * 询问也读它——三处不会出现两套判定。
     */
    static boolean asyncForm(Map<String, Object> contract) {
        return "HTTP".equals(contract.get("type"))
                && Set.of("SUBMIT_QUERY", "CALLBACK")
                        .contains(Objects.toString(contract.get("invocationKind"), ""));
    }

    public boolean asynchronous(Map<String, Object> contract) {
        return asyncForm(contract);
    }

    /**
     * PLAT-22C：提交前绑定经注册表路由到对应单元（ALGORITHM→算法 Job 绑定、HTTP 异步→幂等键登记，
     * 其余形态无操作）。未注册 kind 保持既有静默语义（find 返回 null），不在这里新增抛错面。
     */
    public void bind(Map<String, Object> contract, Map<String, Object> request) {
        SkillAdapter unit = registry.find(contract);
        if (unit != null) unit.bind(this, contract, request);
    }

    public boolean persistentBinding(Map<String, Object> contract) {
        SkillAdapter unit = registry.find(contract);
        return unit != null && unit.persistentBinding(contract);
    }

    public Observation observe(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        requireAttempt(expectedAttempt);
        return registry
                .dispatch(contract)
                .observe(this, contract, arguments, key, callId, queryOnly, runId, expectedAttempt);
    }

    public void completeAlgorithm(
            Map<String, Object> fact, Map<String, Object> contract, Map<String, Object> source) {
        algorithm.complete(fact, contract, source);
    }

    public void prepareAlgorithmRetry(
            Map<String, Object> contract,
            Map<String, Object> original,
            Map<String, Object> request) {
        algorithm.prepareRetry(contract, original, request);
    }

    /** HTTP 异步通道（{@link HttpAsyncExecutionAdapter} 路由目标）：对账/attempt 栅栏在 HttpAsyncAdapter。 */
    Raw httpAsync(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        return asyncHttp.invoke(
                contract, arguments, key, callId, queryOnly, runId, expectedAttempt, this);
    }

    /** HTTP 异步提交绑定（幂等键登记），原 bind 的 asynchronous 分支。 */
    void httpAsyncBind(Map<String, Object> contract, Map<String, Object> request) {
        asyncHttp.bind(contract, request);
    }

    /** 算法 Job 执行/观测/绑定（{@link AlgorithmExecutionAdapter} 路由目标）。 */
    Raw algorithmInvoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        return algorithm.invoke(
                contract, arguments, key, callId, queryOnly, this, runId, expectedAttempt);
    }

    Observation algorithmObserve(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        return algorithm.observe(
                contract, arguments, key, callId, queryOnly, this, runId, expectedAttempt);
    }

    void algorithmBind(Map<String, Object> contract, Map<String, Object> request) {
        algorithm.bind(contract, request);
    }

    /** LAST_REAL只由已核实事实调用；附件复制为当前run的独立归属，不能公开原run链接。 */
    public Raw recorded(
            JsonNode source,
            Map<String, Object> contract,
            long callId,
            String runId,
            String sourceRunId) {
        return recorded(source, contract, callId, runId, sourceRunId, 0);
    }

    public Raw recorded(
            JsonNode source, Map<String, Object> contract, long callId,
            String runId, String sourceRunId, long expectedAttempt) {
        boolean binary = "PROCESS".equals(contract.get("type"));
        List<Map<String, Object>> refs = new ArrayList<>();
        try {
            if (binary && source.path("fileRefs").size() > 16)
                throw invalidProcessOutput();
            long total = 0;
            for (JsonNode ref : source.path("fileRefs")) {
                Map<String, Object> copied;
                if (binary) {
                    if (expectedAttempt < 1) throw invalidProcessOutput();
                    copied = artifacts.copyProcessToRun(
                            ref.path("artifactId").asText(), sourceRunId, runId, callId, expectedAttempt);
                    var named = new LinkedHashMap<>(copied);
                    if (ref.path("name").isTextual()) named.put("name", ref.path("name").asText());
                    copied = named;
                } else {
                    copied = artifacts.copyToRun(
                            ref.path("artifactId").asText(), sourceRunId, runId, callId, contract.get("outputSchema"));
                }
                refs.add(copied);
                if (binary) {
                    total += ((Number) copied.get("bytes")).longValue();
                    if (total > ArtifactStore.MAX_ARTIFACT_BYTES) throw invalidProcessOutput();
                }
            }
        } catch (RuntimeException failure) {
            if (binary) artifacts.discardProcessOutputs(refs, runId, callId, expectedAttempt, failure);
            throw failure;
        }
        return new Raw(
                source.path("structured"),
                refs,
                source.path("modelSummary").asText(),
                source.path("ontologyMeta"),
                source.path("cardPayloadJson").asText(""));
    }

    /**
     * PLAT-15（契约 §14.2）：PREPARE 段——以守卫后参数调用契约声明的独立预演入口（POST + 同一
     * Idempotency-Key），只读、无业务副作用；复用同步 HTTP 通道，鉴权/身份头/大小硬闸同一套语义。
     * 失败一律归一为 ACTION_PLAN_UNAVAILABLE（可读 502）：预演失败绝不进入 WAITING_APPROVAL，
     * 确认人不会审批一份没有依据的计划。仅 HTTP 同步技能可达此路径（注册期已拒绝其它适配器）。
     */
    public Raw prepare(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            String runId,
            long expectedAttempt) {
        var prepare = SkillRules.prepareDeclaration(contract);
        if (prepare == null)
            throw new Failure("ACTION_PLAN_UNAVAILABLE", false, "技能未声明 PREPARE 入口，无法生成执行计划");
        var dry = new LinkedHashMap<String, Object>(contract);
        dry.put("endpoint", prepare.get("endpoint").toString());
        dry.put("httpMethod", "POST");
        // 预演是平台发起的只读调用：不携带业务映射（httpMapping/returnSpec 是 EXECUTE 的投影），
        // 输出按"预演摘要对象"验收，与 EXECUTE 的 outputSchema 无关。
        dry.remove("httpMapping");
        dry.remove("returnSpec");
        dry.put("outputSchema", Map.of("type", "object"));
        dry.put("execution", Map.of("readonly", true, "sideEffect", false));
        try {
            return httpUncached(dry, arguments, key, callId, runId, expectedAttempt);
        } catch (Failure failure) {
            throw new Failure(
                    "ACTION_PLAN_UNAVAILABLE",
                    false,
                    "PREPARE 预演调用失败（未产生业务副作用）: " + failure.getMessage());
        }
    }

    /**
     * PLAT-22C：kind→适配器分发 = 注册表查询。原 switch 的五个分支收敛为五个内置
     * {@link SkillAdapter} 单元（HTTP 同步/异步、MCP、PROCESS、ALGORITHM），各单元声明自己的
     * 调用形态与对账保护；未注册 kind 由注册表给出可读 PARAM_ERROR（与原 default 臂同码）。
     */
    public Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        requireAttempt(expectedAttempt);
        return registry
                .dispatch(contract)
                .invoke(this, contract, arguments, key, callId, queryOnly, runId, expectedAttempt);
    }

    /**
     * An original verified attempt is mandatory; reading a newer database generation cannot supply
     * it.
     */
    static void requireAttempt(long expectedAttempt) {
        if (expectedAttempt < 1) throw new Failure("PARAM_ERROR", false, "业务调用必须提供已核实的原尝试身份");
    }

    /**
     * PROCESS 分支只做“契约+调用参数→进程→Raw”的搬运（PLAT-22C：由 {@link ProcessExecutionAdapter}
     * 经注册表路由进入）。请求信封由 ProcessAdapter 按 §4.4 写 stdin
     * （protocol/requestId/operation/parameters/files），本方法只提供 requestId=callId、operation=技能 code、
     * parameters=已校验的 arguments；Raw 的输出取响应 {@code result}，而不是整段 stdout——stdout 是协议信封，
     * 把它当业务结果会把 requestId/ok/metrics 混进下游 schema。
     *
     * <p>ProcessAdapter 已用 PlatformException 区分 403（开关关闭/白名单外/允许根越界）、429（并发配额）、
     * 400（信封超限）、502（启动失败/退出码/协议违规/输出 schema/取消）、504（超时）；除文件输入边界窄映射
     * ARTIFACT_NOT_FOUND 外保留真实异常——包成通用的依赖不可用会混淆故障，调用方据此重试只会再执行一次本地
     * 进程（Y-3 的教训）。租户 ID 来自已验签上下文，仅用于每租户并发配额；解析不出（系统级调用）就跳过该层。
     */
    Raw executeLocalProcess(Map<String, Object> contract, Map<String, Object> arguments, long callId) {
        return executeLocalProcess(contract, arguments, callId, null, 0);
    }

    Raw executeLocalProcess(
            Map<String, Object> contract, Map<String, Object> arguments,
            long callId, String runId, long expectedAttempt) {
        if (process == null)
            throw new Failure("PARAM_ERROR", false, "当前进程未装配本地进程执行器，拒绝执行PROCESS技能");
        ProcessContract parsed = ProcessContract.parse(Json.tree(contract.get("process")));
        String operation = String.valueOf(contract.get("code"));
        String tenantId;
        Actor processActor = null;
        try {
            processActor = Actor.current();
            tenantId = processActor.tenantId();
        } catch (PlatformException unresolvable) {
            tenantId = null;
        }
        Map<String, Object> parameters = arguments;
        Map<String, String> inputArtifacts = Map.of();
        if (parsed.filesEnabled()) {
            parameters = new LinkedHashMap<>(arguments);
            if (parameters.containsKey("files"))
                inputArtifacts = processInputReferences(parameters.remove("files"));
        }
        Map<String, String> inputs = inputArtifacts;
        var outputRefs = new ArrayList<Map<String, Object>>();
        // 旧无上下文测试入口仍可执行，但不会匹配生产取消入口；真实执行使用同一已核主体和精确代次。
        var cancellationOwner = processActor != null && runId != null && expectedAttempt > 0
                && contract.get("skillVersionId") instanceof Number version
                ? new ProcessAdapter.CancellationOwner(tenantId, processActor.principalId(), runId, expectedAttempt, version.longValue()) : null;
        ProcessAdapter.Output executed;
        try {
            executed = process.execute(
                        parsed,
                        new ProcessAdapter.Request(
                                Long.toString(callId), operation, Json.tree(parameters), cancellationOwner),
                        tenantId,
                        inputs.isEmpty()
                                ? null
                                : (callDir, deadline) -> materializeProcessInputs(inputs, callDir, deadline),
                        !parsed.filesEnabled() ? null
                                : (callDir, output, deadline) -> outputRefs.addAll(persistProcessOutputs(
                                        callDir, output.files(), runId, callId, expectedAttempt, deadline)));
        } catch (RuntimeException failure) {
            // 输出交接后仍可能收到取消；只撤销本批新产物，不能留下失败调用的 READY 附件。
            if (!outputRefs.isEmpty())
                artifacts.discardProcessOutputs(outputRefs, runId, callId, expectedAttempt, failure);
            throw failure;
        }
        Map<String, Object> processFact = executed.fact();
        if (parsed.filesEnabled()) {
            processFact = new LinkedHashMap<>(processFact);
            processFact.remove("workDir");
            processFact.remove("command");
            processFact.put("runId", Objects.toString(runId, ""));
            processFact.put("callId", callId);
            processFact.put("attemptId", expectedAttempt);
        }
        return new Raw(
                executed.result(),
                List.copyOf(outputRefs),
                "本地进程返回成功，耗时 " + executed.durationMs() + "ms",
                Json.tree(null),
                "",
                null,
                processFact);
    }


    private record ProcessOutputFile(
            String name, String contentType, long bytes, String sha256, FileChannel channel) {}

    /** All descriptors and bytes are checked before the first artifact is registered. */
    private List<Map<String, Object>> persistProcessOutputs(
            Path callDir, JsonNode declared, String runId, long callId, long attempt, long deadline) {
        if (declared.isEmpty()) return List.of();
        if (runId == null || !runId.matches("r_[A-Za-z0-9_-]{1,125}") || callId < 1 || attempt < 1)
            throw new PlatformException(502, "PROCESS_OUTPUT_INVALID", "输出文件缺少已核实的执行归属");
        Actor.current();
        if (artifacts == null)
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "受控输出存储未装配");
        if (declared.size() > 16) throw invalidProcessOutput();
        var names = new HashSet<String>();
        var paths = new HashSet<Path>();
        var opened = new ArrayList<ProcessOutputFile>();
        var refs = new ArrayList<Map<String, Object>>();
        try {
            long total = 0;
            for (JsonNode item : declared) {
                checkProcessFileDeadline(deadline);
                if (!item.isObject() || !item.path("name").isTextual()
                        || item.path("name").asText().isBlank()
                        || !names.add(item.path("name").asText())
                        || !item.path("path").isTextual()
                        || !item.path("contentType").isTextual()
                        || item.path("contentType").asText().isBlank()
                        || item.path("contentType").asText().length() > 128
                        || !item.path("bytes").isIntegralNumber() || !item.path("bytes").canConvertToLong()
                        || !item.path("sha256").isTextual()
                        || !item.path("sha256").asText().matches("[0-9a-f]{64}"))
                    throw invalidProcessOutput();
                long bytes = item.path("bytes").asLong();
                if (bytes < 0 || bytes > ArtifactStore.MAX_ARTIFACT_BYTES
                        || bytes > ArtifactStore.MAX_ARTIFACT_BYTES - total) throw invalidProcessOutput();
                total += bytes;
                String relative = item.path("path").asText().replace('\\', '/');
                if (relative.startsWith("/") || relative.contains(":")) throw invalidProcessOutput();
                for (String segment : relative.split("/", -1))
                    if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
                        throw invalidProcessOutput();
                Path path = callDir.resolve(relative).normalize();
                if (!path.startsWith(callDir.resolve("output")) || !paths.add(path))
                    throw invalidProcessOutput();
                // Reject every ancestor link/reparse redirection, not only the final component.
                rejectProcessOutputLinks(path);
                var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!before.isRegularFile() || before.size() != bytes)
                    throw invalidProcessOutput();
                Set<OpenOption> options = new HashSet<>(Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                    throw invalidProcessOutput();
                // JDK Windows CreateFile sharing flags: an active writer/replacer makes open fail.
                options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE);
                options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
                FileChannel channel = FileChannel.open(path, options);
                var file = new ProcessOutputFile(item.path("name").asText(),
                        item.path("contentType").asText(), bytes, item.path("sha256").asText(), channel);
                opened.add(file);
                rejectProcessOutputLinks(path);
                var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // JDK WindowsFileAttributes.fileKey() returns null; an optional key is only
                // a supplementary check. Protection comes from the same guarded handle below.
                if ((before.fileKey() != null && !Objects.equals(before.fileKey(), after.fileKey()))
                        || after.size() != bytes
                        || channel.size() != bytes) throw invalidProcessOutput();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                var buffer = java.nio.ByteBuffer.allocate(8192);
                long actual = 0;
                while (true) {
                    checkProcessFileDeadline(deadline);
                    int read = channel.read(buffer);
                    if (read < 0) break;
                    if (read == 0) continue;
                    actual += read;
                    if (actual > bytes) throw invalidProcessOutput();
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
                if (actual != bytes || !file.sha256().equals(HexFormat.of().formatHex(digest.digest())))
                    throw invalidProcessOutput();
                channel.position(0);
            }
            for (var file : opened) {
                checkProcessFileDeadline(deadline);
                // Upload reads the same protected handle that was verified above; no path re-open.
                InputStream bytes = new FilterInputStream(Channels.newInputStream(file.channel())) {
                    @Override public int read() throws IOException {
                        checkProcessFileDeadline(deadline);
                        int value = super.read();
                        checkProcessFileDeadline(deadline);
                        return value;
                    }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        checkProcessFileDeadline(deadline);
                        int count = in.read(buffer, offset, length);
                        checkProcessFileDeadline(deadline);
                        return count;
                    }
                };
                var ref = new LinkedHashMap<>(artifacts.storeProcessOutput(
                        file.contentType(), file.bytes(), file.sha256(), bytes, runId, callId, attempt));
                ref.put("name", file.name());
                refs.add(ref);
            }
            checkProcessFileDeadline(deadline);
            return List.copyOf(refs);
        } catch (RuntimeException failure) {
            artifacts.discardProcessOutputs(refs, runId, callId, attempt, failure);
            throw failure;
        } catch (IOException failure) {
            var rejected = invalidProcessOutput();
            artifacts.discardProcessOutputs(refs, runId, callId, attempt, rejected);
            throw rejected;
        } catch (NoSuchAlgorithmException unavailable) {
            var failure = new IllegalStateException("SHA-256 unavailable", unavailable);
            artifacts.discardProcessOutputs(refs, runId, callId, attempt, failure);
            throw failure;
        } finally {
            for (var file : opened) {
                try {
                    file.channel().close();
                } catch (IOException ignored) {
                    // All platform-owned handles are closed; no worker path is retried or reopened.
                }
            }
        }
    }

    private static void rejectProcessOutputLinks(Path path) throws IOException {
        Path cursor = path.getRoot();
        if (cursor == null) throw invalidProcessOutput();
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !cursor.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(cursor.toRealPath()))
                throw invalidProcessOutput();
        }
    }

    private static PlatformException invalidProcessOutput() {
        return new PlatformException(502, "PROCESS_OUTPUT_INVALID", "输出文件声明、归属或内容不符合受控文件约定");
    }

    private static void checkProcessFileDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0)
            throw new PlatformException(504, "PROCESS_TIMEOUT", "文件交接超过执行时限或被中断");
    }

    private static Map<String, String> processInputReferences(Object value) {
        if (!(value instanceof Map<?, ?> declared))
            throw new PlatformException(422, "PROCESS_INPUT_INVALID", "files 必须是受控产物引用对象");
        if (declared.size() > 16)
            throw new PlatformException(400, "PROCESS_INPUT_TOO_LARGE", "输入文件不能超过16个");
        var inputs = new LinkedHashMap<String, String>();
        for (var entry : declared.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof Map<?, ?> reference)
                    || !reference.keySet().equals(Set.of("artifactId"))
                    || !(reference.get("artifactId") instanceof String id))
                throw new PlatformException(
                        422, "PROCESS_INPUT_INVALID", "每个输入文件仅接受字符串 artifactId");
            inputs.put(name, id);
        }
        return inputs;
    }

    /** 逻辑名只用作信封键；本机文件名由平台生成，全部输入验证完毕才允许启动 worker。 */
    private Map<String, String> materializeProcessInputs(
            Map<String, String> inputs, Path callDir, long deadline) {
        if (artifacts == null)
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "受控输入存储未装配");
        final long singleLimit = 16L * 1024 * 1024;
        final long totalLimit = 64L * 1024 * 1024;
        var files = new LinkedHashMap<String, String>();
        long total = 0;
        try {
            Path inputDir = Files.createDirectory(callDir.resolve("input"));
            byte[] buffer = new byte[8192];
            for (var input : inputs.entrySet()) {
                checkProcessInputDeadline(deadline);
                String fileName = String.format(Locale.ROOT, "%04d.bin", files.size() + 1);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long bytes = 0;
                // sourceRun=null 只放开源运行匹配；真实下载仍核当前 Actor、READY 与有效期。
                try (var download = artifacts.download(input.getValue(), null);
                        var target = Files.newOutputStream(
                                inputDir.resolve(fileName), StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    if (download.bytes() < 0 || download.bytes() > singleLimit
                            || download.bytes() > totalLimit - total)
                        throw new PlatformException(
                                400, "PROCESS_INPUT_TOO_LARGE", "输入文件超过单个或合计字节上限");
                    while (true) {
                        checkProcessInputDeadline(deadline);
                        int read = download.source().read(buffer);
                        checkProcessInputDeadline(deadline);
                        if (read == -1) break;
                        if (read == 0) continue;
                        bytes += read;
                        total += read;
                        if (bytes > singleLimit || total > totalLimit)
                            throw new PlatformException(
                                    400, "PROCESS_INPUT_TOO_LARGE", "输入文件超过单个或合计字节上限");
                        digest.update(buffer, 0, read);
                        target.write(buffer, 0, read);
                    }
                    if (bytes != download.bytes()
                            || !HexFormat.of().formatHex(digest.digest()).equals(download.sha256()))
                        throw new PlatformException(
                                503, "ARTIFACT_STORAGE_UNAVAILABLE", "输入文件长度或摘要与存储记录不一致");
                }
                checkProcessInputDeadline(deadline);
                files.put(input.getKey(), "input/" + fileName);
            }
            return files;
        } catch (PlatformException ex) {
            if (ex.status() == 404 && "ARTIFACT_NOT_FOUND".equals(ex.code()))
                throw new PlatformException(
                        404, "PROCESS_INPUT_ARTIFACT_NOT_FOUND", "输入产物不存在、已过期或无权访问");
            throw ex;
        } catch (IOException ex) {
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "输入文件暂不可读取或写入");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void checkProcessInputDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0)
            throw new PlatformException(504, "PROCESS_TIMEOUT", "输入文件准备超过执行时限或被中断");
    }

    /**
     * 兼容既有本地取消测试；生产内部端点必须使用带精确归属的重载，不能只凭 callId 杀进程。
     */
    public boolean cancelLocalProcess(long callId) {
        return process != null && process.cancel(callId);
    }

    /** 生产取消必须同时匹配租户、主体、run、call 和 attempt/version，旧授权不能命中新代进程。 */
    public boolean cancelLocalProcess(long callId, ProcessAdapter.CancellationOwner owner) {
        return process != null && process.cancel(Long.toString(callId), owner);
    }

    // ===== PLAT-22C 内置注册单元的回调通道 =====
    // 这些包私有方法不是新语义：HTTP 同步/异步、ALGORITHM、本地进程的执行体仍在本类（T05/T06/T12/
    // T21d 行为零搬运），单元只负责"被注册表找到并路由回来"，避免单元复制任何执行分支。

    /** HTTP 同步通道：缓存只持叶结果，Observation 命中事实由本地生成。 */
    Raw http(
            Map<String, Object> contract, Map<String, Object> arguments, String key,
            long callId, String runId, long expectedAttempt) {
        return httpObserved(contract, arguments, key, callId, runId, expectedAttempt, false).raw();
    }

    Observation httpObserved(
            Map<String, Object> contract, Map<String, Object> arguments, String key,
            long callId, String runId, long expectedAttempt, boolean queryOnly) {
        requireAttempt(expectedAttempt);
        if (queryOnly || readOnlyCache == null)
            return new Observation(httpUncached(contract, arguments, key, callId, runId, expectedAttempt), Map.of());
        return readOnlyCache.execute(contract, arguments, () -> mappedIdentityHeaders(contract),
                () -> httpUncached(contract, arguments, key, callId, runId, expectedAttempt));
    }

    private Raw httpUncached(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            String runId,
            long expectedAttempt) {
        requireAttempt(expectedAttempt);
        String endpoint = contract.get("endpoint").toString();
        Object requestBody = arguments;
        boolean mapped = contract.containsKey("httpMapping");
        if (mapped) {
            var requestMapping = OpenApiHttpMapping.request(contract, arguments);
            endpoint = requestMapping.endpoint();
            requestBody = requestMapping.body();
        }
        SkillRules.endpoint(endpoint);
        String method = Objects.toString(contract.get("httpMethod"), "POST");
        if (!mapped && method.equals("GET")) {
            var uri = UriComponentsBuilder.fromUriString(endpoint);
            arguments.forEach(
                    (name, value) ->
                            uri.queryParam(
                                    name, value instanceof String ? value : Json.write(value)));
            endpoint = uri.build().encode().toUriString();
        }
        // 鉴权头由契约 authScheme 决定（缺省 BEARER，见 AuthScheme 的兼容窗口）；凭据解析失败必须
        // 在发请求之前抛出。这段刻意留在下面的 try 之外：try 内的 catch 会把任意异常折叠成
        // “外部端点连接失败”，那会把配置错误伪装成可重试的传输故障，还可能让有副作用的端点先执行一次。
        Map<String, String> authorization =
                AuthScheme.headers(
                        contract.get(AuthScheme.FIELD),
                        Objects.toString(contract.get("authRef"), ""));
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        // PG 映射前置核验在业务传输 try 外；拒绝不能被折成可重试网络错误或未知写入。
        Map<String, String> mappedIdentity = mappedIdentityHeaders(contract);
        boolean sideEffect =
                Boolean.TRUE.equals(object(contract.get("execution")).get("sideEffect"));
        // 复用既有 JDK21 transport：HttpURLConnection 的 streaming POST 在 401 时丢弃错误体，
        // 会把已知鉴权拒绝折成传输故障。保留同一超时/硬字节闸，单次调用后关闭，不新增重发。
        java.net.http.HttpClient httpClient =
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build();
        org.springframework.util.MultiValueMap<String, Object> multipart = null;
        try {
            var factory =
                    new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(
                    Duration.ofMillis(((Number) contract.get("timeoutMs")).longValue()));
            var client = RestClient.builder().requestFactory(factory).build();
            var request =
                    mapped
                            ? client.method(httpMethod).uri(java.net.URI.create(endpoint))
                            : client.method(httpMethod).uri(endpoint);
            request.header("Idempotency-Key", key).header("Accept", "application/json");
            authorization.forEach(request::header);
            // 身份头与鉴权头叠加（PLAT-12 §4.12）：声明了 identity 的技能出站追加 X-User-*，值
            // 只来自进程内已验主体快照；无可用主体（系统级调用）时跳过注入不伪造，不改变鉴权语义。
            mappedIdentity.forEach(request::header);
            // 关联值仅来自已校验的请求/运行上下文，不能由技能鉴权配置覆盖或用作身份。
            request.headers(headers -> headers.set(TraceContextFilter.HEADER, TraceContextFilter.current()));
            if (mapped && MultipartFormMapping.declared(contract)) {
                // multipart表单：文件字节经产物存储按租户/主体解析（runId传null与/admin下载语义一致，
                // 允许引用此前运行的产物）；重发同一坏引用不会有不同结果，失败按PARAM_ERROR处理。
                multipart = MultipartFormMapping.build(
                        contract, arguments, id -> artifacts.download(id, null));
                request.contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                        .body(multipart);
            }
            // JsonNode强制Jackson编码；Java String直接交给默认转换器会变为裸text/plain，破坏OpenAPI JSON标量契约。
            else if (!method.equals("GET") && requestBody != null)
                request.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(Json.tree(requestBody));
            return request.exchange(
                    (sent, response) -> {
                        int code = response.getStatusCode().value();
                        if (code >= 400) {
                            // PLAT-05：错误响应不再是黑盒。先按契约声明（或平台缺省 $.code/$.message）
                            // 读取远端业务码与原文；鉴权拒绝恒为不可重试，其余已声明 4xx 可改判。
                            // 带副作用的 5xx 仍然结果未知——任何业务码都不能把“未确认
                            // 是否已生效”洗成“已确认失败”。
                            var remote = remoteRejection(contract, response.getBody());
                            var declared = declaredMapping(contract);
                            boolean undetermined = sideEffect && code >= 500;
                            throw new Failure(
                                    code == 403 || code == 401
                                            ? "NO_PERMISSION"
                                            : !undetermined
                                                    && ErrorSemantics.retryableCode(
                                                            declared, remote)
                                            ? "RETRYABLE_FAILURE"
                                            : code == 429
                                                    ? "QUOTA"
                                                    : code < 500
                                                            ? "PARAM_ERROR"
                                                            : undetermined
                                                                    ? "RESULT_UNKNOWN"
                                                                    : "RETRYABLE_FAILURE",
                                    undetermined,
                                    "外部端点HTTP " + code,
                                    remote.code(),
                                    remote.message());
                        }
                        return read(response.getBody(), contract, callId, runId);
                    });
        } catch (Failure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Failure(
                    sideEffect ? "RESULT_UNKNOWN" : "RETRYABLE_FAILURE",
                    sideEffect,
                    "外部端点连接、超时或结果接收失败");
        } finally {
            MultipartFormMapping.closeFiles(multipart);
            httpClient.close();
        }
    }

    public Raw readAlgorithm(
            InputStream input,
            Map<String, Object> contract,
            long callId,
            String runId,
            long deadline)
            throws IOException {
        return read(input, contract, callId, runId, deadline);
    }

    /** 按字节读取max+1后立即转MinIO，普通响应仅在已知上限内构造JSON。 */
    public Raw read(InputStream input, Map<String, Object> contract, long callId)
            throws IOException {
        return read(input, contract, callId, null);
    }

    public Raw read(InputStream input, Map<String, Object> contract, long callId, String runId)
            throws IOException {
        return read(input, contract, callId, runId, 0);
    }

    private Raw read(
            InputStream input,
            Map<String, Object> contract,
            long callId,
            String runId,
            long deadline)
            throws IOException {
        int limit = ((Number) contract.get("outputMaxBytes")).intValue();
        byte[] prefix = input.readNBytes(limit + 1);
        if (prefix.length > limit) {
            Map<String, Object> ref;
            if (deadline == 0)
                ref = artifacts.offload(prefix, input, contract.get("outputSchema"), callId, runId);
            else
                try (var operation = artifacts.forAlgorithmDeadline(deadline)) {
                    ref =
                            operation.offload(
                                    prefix, input, contract.get("outputSchema"), callId, runId);
                }
            return new Raw(Json.tree(null), List.of(ref), "结果已流式转存，请使用受控文件引用查看", Json.tree(null));
        }
        JsonNode output = ArtifactStore.readJson(new ByteArrayInputStream(prefix));
        if (output == null) throw new Failure("OUTPUT_INVALID", false, "外部响应为空");
        SchemaWorkBudget.Admission admission;
        try {
            admission =
                    SkillRules.validate(
                            contract.get("outputSchema"),
                            Json.read(output.toString(), Object.class),
                            schemaMode,
                            maxExpandedWork);
        } catch (IllegalArgumentException ex) {
            throw new Failure("OUTPUT_INVALID", false, "外部响应不符合输出Schema");
        }
        JsonNode ontology = output.path("ontologyMeta");
        String summary = output.isTextual() ? output.asText() : Json.write(output);
        if (summary.length() > 2000) summary = summary.substring(0, 2000) + "…";
        var spec = object(contract.getOrDefault("returnSpec", Map.of()));
        try {
            JsonNode structured =
                    select(output, Objects.toString(spec.getOrDefault("structuredPath", "$")));
            var summarySpec = object(spec.getOrDefault("modelSummary", Map.of()));
            if ("PATH".equals(summarySpec.get("mode")))
                summary = valueText(select(output, Objects.toString(summarySpec.get("path"))));
            else if ("TEMPLATE".equals(summarySpec.get("mode")))
                summary = render(text(summarySpec, "tpl"), output);
            if (summary.length() > 2000) summary = summary.substring(0, 2000) + "…";
            var refs = new ArrayList<Map<String, Object>>();
            if (spec.get("fileRefPaths") instanceof Collection<?> paths)
                for (Object path : paths) addFiles(select(output, path.toString()), refs, runId);
            String card = "";
            if (spec.containsKey("cardTemplate")) {
                Object template = spec.get("cardTemplate");
                JsonNode json =
                        template instanceof String str ? Json.read(str) : Json.tree(template);
                if (!json.isContainerNode()) throw new IllegalArgumentException("卡片模板必须为JSON对象或数组");
                card = Json.write(renderCard(json, output));
            }
            return new Raw(structured, refs, summary, ontology, card, Validation.of(admission));
        } catch (Failure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Failure("OUTPUT_INVALID", false, "结果四份内容映射不符合returnSpec");
        }
    }

    private static JsonNode select(JsonNode root, String path) {
        if (path.equals("$")) return root;
        int wildcard = path.indexOf("[*]");
        if (wildcard >= 0) {
            var array = select(root, path.substring(0, wildcard));
            if (!array.isArray()) throw new IllegalArgumentException("通配路径源非数组");
            var result = Json.mapper().createArrayNode();
            for (JsonNode item : array)
                result.add(select(item, "$" + path.substring(wildcard + 3)));
            return result;
        }
        if (!path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+\\])+"))
            throw new IllegalArgumentException("结果路径仅支持点路径/数组下标/[*]");
        String normalized = path.substring(1).replaceAll("\\[([0-9]+)]", ".$1");
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        JsonNode value = root;
        for (String key : normalized.split("\\.")) {
            value = value.isArray() ? value.path(Integer.parseInt(key)) : value.path(key);
            if (value.isMissingNode()) throw new IllegalArgumentException("结果路径不存在: " + path);
        }
        return value;
    }

    private static String valueText(JsonNode value) {
        return value.isValueNode() ? value.asText() : Json.write(value);
    }

    private static String render(String template, JsonNode output) {
        var pattern = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");
        var matcher = pattern.matcher(template);
        var result = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            valueText(select(output, "$." + matcher.group(1)))));
        matcher.appendTail(result);
        return result.toString();
    }

    private static JsonNode renderCard(JsonNode template, JsonNode output) {
        if (template.isTextual()) return Json.tree(render(template.asText(), output));
        if (template.isObject()) {
            var result = Json.mapper().createObjectNode();
            template.fields()
                    .forEachRemaining(
                            entry ->
                                    result.set(
                                            entry.getKey(), renderCard(entry.getValue(), output)));
            return result;
        }
        if (template.isArray()) {
            var result = Json.mapper().createArrayNode();
            template.forEach(item -> result.add(renderCard(item, output)));
            return result;
        }
        return template;
    }

    private void addFiles(JsonNode value, List<Map<String, Object>> refs, String runId) {
        if (value.isArray()) {
            value.forEach(item -> addFiles(item, refs, runId));
            return;
        }
        Map<String, Object> ref =
                value.isTextual()
                        ? Map.of("artifactId", value.asText())
                        : Json.read(value.toString(), Map.class);
        refs.add(artifacts.controlledReference(ref, runId));
    }
}
