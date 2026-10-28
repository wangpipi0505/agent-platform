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
import java.time.Duration;
import java.util.*;

@Component
public class SkillAdapters {
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
        List<Map<String, Object>> refs = new ArrayList<>();
        for (JsonNode ref : source.path("fileRefs"))
            refs.add(
                    artifacts.copyToRun(
                            ref.path("artifactId").asText(),
                            sourceRunId,
                            runId,
                            callId,
                            contract.get("outputSchema")));
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
            return http(dry, arguments, key, callId, runId, expectedAttempt);
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
     * 400（信封超限）、502（启动失败/退出码/协议违规/输出 schema/取消）、504（超时）；这里刻意不 catch 任何
     * 异常——包成通用的依赖不可用会把“配置错误”与“上游抖动”混为一谈，调用方据此重试只会再执行一次本地
     * 进程（Y-3 的教训）。租户 ID 来自已验签上下文，仅用于每租户并发配额；解析不出（系统级调用）就跳过该层。
     */
    Raw executeLocalProcess(Map<String, Object> contract, Map<String, Object> arguments, long callId) {
        if (process == null)
            throw new Failure("PARAM_ERROR", false, "当前进程未装配本地进程执行器，拒绝执行PROCESS技能");
        ProcessContract parsed = ProcessContract.parse(Json.tree(contract.get("process")));
        String operation = String.valueOf(contract.get("code"));
        String tenantId;
        try {
            tenantId = Actor.current().tenantId();
        } catch (PlatformException unresolvable) {
            tenantId = null;
        }
        ProcessAdapter.Output executed =
                process.execute(
                        parsed,
                        new ProcessAdapter.Request(
                                Long.toString(callId), operation, Json.tree(arguments)),
                        tenantId);
        return new Raw(
                executed.result(),
                List.of(),
                "本地进程返回成功，耗时 " + executed.durationMs() + "ms",
                Json.tree(null),
                "",
                null,
                executed.fact());
    }

    /**
     * T21d 取消句柄：按 callId 终止进行中的本地进程树。引擎的 run/node 取消目前只在 engine-runtime
     * 落库，没有出站事件到达 skill-registry（接线缺口已登记），因此本方法暂时没有生产调用方；
     * 接通方案与缺口说明见 {@link ProcessAdapter#cancel(String)} 与证据 README。
     */
    public boolean cancelLocalProcess(long callId) {
        return process != null && process.cancel(callId);
    }

    // ===== PLAT-22C 内置注册单元的回调通道 =====
    // 这些包私有方法不是新语义：HTTP 同步/异步、ALGORITHM、本地进程的执行体仍在本类（T05/T06/T12/
    // T21d 行为零搬运），单元只负责"被注册表找到并路由回来"，避免单元复制任何执行分支。

    /** HTTP 同步通道（{@link HttpSyncExecutionAdapter} 路由目标）。 */
    Raw http(
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
        boolean sideEffect =
                Boolean.TRUE.equals(object(contract.get("execution")).get("sideEffect"));
        // HttpURLConnection不支持PATCH。仅该方法使用JDK21 transport，保持相同的超时/硬字节闸并在单次调用后关闭。
        java.net.http.HttpClient patchClient =
                method.equals("PATCH")
                        ? java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                                .build()
                        : null;
        try {
            org.springframework.http.client.ClientHttpRequestFactory factory;
            if (patchClient != null) {
                var jdk =
                        new org.springframework.http.client.JdkClientHttpRequestFactory(
                                patchClient);
                jdk.setReadTimeout(
                        Duration.ofMillis(((Number) contract.get("timeoutMs")).longValue()));
                factory = jdk;
            } else {
                var simple = new SimpleClientHttpRequestFactory();
                simple.setConnectTimeout(3000);
                simple.setReadTimeout(((Number) contract.get("timeoutMs")).intValue());
                factory = simple;
            }
            var client = RestClient.builder().requestFactory(factory).build();
            var request =
                    mapped
                            ? client.method(httpMethod).uri(java.net.URI.create(endpoint))
                            : client.method(httpMethod).uri(endpoint);
            request.header("Idempotency-Key", key).header("Accept", "application/json");
            authorization.forEach(request::header);
            // 身份头与鉴权头叠加（PLAT-12 §4.12）：声明了 identity 的技能出站追加 X-User-*，值
            // 只来自进程内已验主体快照；无可用主体（系统级调用）时跳过注入不伪造，不改变鉴权语义。
            identityHeaders(contract).forEach(request::header);
            if (mapped && MultipartFormMapping.declared(contract)) {
                // multipart表单：文件字节经产物存储按租户/主体解析（runId传null与/admin下载语义一致，
                // 允许引用此前运行的产物）；重发同一坏引用不会有不同结果，失败按PARAM_ERROR处理。
                request.contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                        .body(
                                MultipartFormMapping.build(
                                        contract, arguments, id -> artifacts.download(id, null)));
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
                            // 读取远端业务码与原文；声明的 retryableCodes 命中时把 4xx 的确定性失败
                            // 改判为可重试。带副作用的 5xx 仍然结果未知——任何业务码都不能把“未确认
                            // 是否已生效”洗成“已确认失败”。
                            var remote = remoteRejection(contract, response.getBody());
                            var declared = declaredMapping(contract);
                            boolean undetermined = sideEffect && code >= 500;
                            throw new Failure(
                                    !undetermined
                                                    && ErrorSemantics.retryableCode(
                                                            declared, remote)
                                            ? "RETRYABLE_FAILURE"
                                            : code == 403 || code == 401
                                                    ? "NO_PERMISSION"
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
            if (patchClient != null) patchClient.close();
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
