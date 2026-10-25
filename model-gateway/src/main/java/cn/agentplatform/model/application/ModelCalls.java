package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.model.domain.ModelErrors;
import cn.agentplatform.model.domain.ModelPolicy;
import cn.agentplatform.model.infra.BoundedResponse;
import cn.agentplatform.model.infra.FrozenRequestConnector;
import cn.agentplatform.model.infra.ModelStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.knuddels.jtokkit.Encodings;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 对照Spring AI1.0的OpenAiChatModel/MessageAggregator：只做一次模型交互。
 * Agent受控循环、技能授权、业务重试归engine；这里绝不能自动执行工具。
 */
@Service
public class ModelCalls {
    private final ModelStore store;
    private final ModelQuota quota;
    private final ModelCircuits circuits;
    private final Semaphore capacity = new Semaphore(32);
    private final InitialModelInputVerifier initialInputs;
    private final FrozenModelBindingVerifier initialBindings;
    private final TransactionTemplate initialTransaction;

    @Autowired
    public ModelCalls(ModelStore store, ModelQuota quota) {
        this.store = store;
        this.quota = quota;
        this.circuits = new ModelCircuits(store);
        this.initialInputs = null;
        this.initialBindings = null;
        this.initialTransaction = null;
    }

    /** Explicit initial-only component assembly; all supplied dependencies are borrowed. */
    public ModelCalls(ModelStore store, ModelQuota quota, InitialModelInputVerifier inputs,
            FrozenModelBindingVerifier bindings, DataSource dataSource) {
        this.store = Objects.requireNonNull(store);
        this.quota = Objects.requireNonNull(quota);
        this.initialInputs = Objects.requireNonNull(inputs);
        this.initialBindings = Objects.requireNonNull(bindings);
        this.initialTransaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(dataSource)));
        this.initialTransaction.setTimeout(3);
        this.circuits = new ModelCircuits(store);
    }

    public void requireLegacyStreamAllowed() {
        if (initialInputs != null)
            throw new PlatformException(403, "MODEL_INITIAL_PROFILE_REQUIRED", "此组件仅支持初始模型流");
    }

    public void requireInitialStreamEntry() {
        if (initialInputs == null) throw new PlatformException(503, "MODEL_INITIAL_COMPONENT_UNAVAILABLE", "初始模型组件尚未配置");
        var a = AuthorityContext.current();
        var root = a.call().resource();
        if (!"engine".equals(a.caller()) || !"model".equals(a.call().target())
                || !"model.chat-stream".equals(a.call().action()) || !"app-version".equals(root.kind())
                || !"DELEGATED".equals(a.operation().sourceKind()) || !"PORTAL".equals(a.operation().originKind())
                || !root.equals(a.operation().resource())
                || !new SubjectKey(a.actor().tenantId(), a.actor().principalId()).equals(a.operation().subject()))
            throw promptMissing();
        var proof = AuthorityContext.requireInitialFor(a.actor(), root);
        proof.requireOriginAuthenticationId();
        AuthorityContext.requireBound(proof);
    }

    /** 组装/裁剪复用同一模型适配计量；绝不调用LLM，保守结构开销不能标成精确计量。 */
    public Map<String, Object> measure(JsonNode req, Actor actor) {
        var entry = store.resolve(req, actor);
        checkCapabilities(req, entry);
        Set<String> labels = set(req.path("requiredLabels"));
        labels.addAll(actor.attributes().getOrDefault("dataLabels", Set.of()));
        checkRoute(labels, entry);
        boolean exact = !entry.get("tokenizerUrl").toString().isBlank();
        return Map.of(
                "inputTokens",
                countTokens(req, entry, actor),
                "contextWindow",
                entry.get("contextWindow"),
                "maxOutputTokens",
                entry.get("maxOutputTokens"),
                "tokenizer",
                exact ? "MODEL_TOKENIZER" : "JTOKKIT_WITH_CONSERVATIVE_STRUCTURE",
                "exact",
                exact);
    }

    public Map<String, Object> testProvider(long id, Actor actor) {
        actor.requireRole("ADMIN");
        return probeProvider(id, actor, false);
    }

    public void probeDegraded() {
        for (var row : store.degradedProviders()) {
            var actor =
                    new Actor(
                            row.get("tenant_id").toString(),
                            "_model_health",
                            Set.of("SERVICE"),
                            Map.of());
            probeProvider(((Number) row.get("id")).longValue(), actor, true);
        }
    }

    public void reconcilePendingUsage() {
        for (var row : store.pendingUsage())
            try {
                String outcome =
                        quota.reconcile(
                                Json.read(row.get("quota_receipt").toString(), Map.class),
                                ((Number) row.get("total_tokens")).intValue());
                store.reconciled(((Number) row.get("id")).longValue(), outcome);
            } catch (Exception ignored) {
                /* 保留PG的PENDING，下一轮只重投账务，绝不重发模型请求。 */
            }
        for (var row : store.pendingNoSend())
            try {
                String outcome =
                        quota.refundNoSend(
                                Json.read(row.get("quota_receipt").toString(), Map.class));
                store.noSendReconciled(
                        row.get("tenant_id").toString(),
                        row.get("reservation_id").toString(),
                        outcome);
            } catch (Exception ignored) {
                /* PG已保存零发送退款意图；Redis或确认写失败后仅重投账务。 */
            }
    }

    private Map<String, Object> probeProvider(long id, Actor actor, boolean scheduled) {
        var probeCircuit = scheduled ? circuits.probe(id, actor) : null;
        if (scheduled && probeCircuit == null)
            return Map.of("ok", false, "stage", "CIRCUIT", "reason", "等待半开探测窗口");
        var p = store.provider(id, actor);
        long revision = ((Number) p.get("config_rev")).longValue();
        if (scheduled && "DISABLED".equals(p.get("status"))) {
            probeCircuit.releasePermission();
            return Map.of("ok", false, "stage", "STALE", "reason", "供应商已停用");
        }
        long started = System.nanoTime();
        String base = p.get("base_url").toString().replaceAll("/+$", "");
        String url = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        String stage = "ENDPOINT";
        try {
            var headers = client(10000).get().uri(url);
            String ref = Objects.toString(p.get("secret_ref"), "");
            if (!ref.isBlank())
                headers.header("Authorization", "Bearer " + SecretVault.resolve(ref, actor));
            JsonNode answer =
                    headers.exchange(
                            (request, response) -> {
                                if (!response.getStatusCode().is2xxSuccessful())
                                    throw ModelErrors.status(response.getStatusCode().value());
                                byte[] bytes = response.getBody().readNBytes(1024 * 1024 + 1);
                                if (bytes.length > 1024 * 1024)
                                    throw new IllegalArgumentException("模型清单超出安全大小");
                                return Json.read(
                                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            });
            List<String> names = new ArrayList<>();
            answer.path("data")
                    .forEach(
                            x -> {
                                String name = x.path("id").asText();
                                if (!name.isBlank()) names.add(name);
                            });
            stage = "MODEL";
            if (names.isEmpty()) throw new IllegalArgumentException("供应商没有返回可测试模型");
            var ping =
                    client(10000)
                            .post()
                            .uri(
                                    base.endsWith("/v1")
                                            ? base + "/chat/completions"
                                            : base + "/v1/chat/completions");
            if (!ref.isBlank())
                ping.header("Authorization", "Bearer " + SecretVault.resolve(ref, actor));
            JsonNode heartbeat =
                    ping.body(
                                    Map.of(
                                            "model",
                                            names.getFirst(),
                                            "messages",
                                            List.of(Map.of("role", "user", "content", "ping")),
                                            "max_tokens",
                                            1,
                                            "stream",
                                            false))
                            .exchange(
                                    (request, response) -> {
                                        if (!response.getStatusCode().is2xxSuccessful())
                                            throw ModelErrors.status(
                                                    response.getStatusCode().value());
                                        byte[] bytes = response.getBody().readNBytes(65537);
                                        if (bytes.length > 65536)
                                            throw new IllegalStateException("心跳响应超限");
                                        return Json.read(
                                                new String(
                                                        bytes,
                                                        java.nio.charset.StandardCharsets.UTF_8));
                                    });
            if (!heartbeat.path("choices").isArray() || heartbeat.path("choices").isEmpty())
                throw new IllegalStateException("短对话未返回有效响应");
            if (!scheduled) {
                if (!store.tested(id, actor, true, revision))
                    return Map.of("ok", false, "stage", "STALE", "reason", "供应商配置已变更，请重新测试");
                circuits.recovered(id, actor);
            } else {
                if (!store.probed(id, actor, "CLOSED", revision)) {
                    probeCircuit.releasePermission();
                    return Map.of("ok", false, "stage", "STALE", "reason", "供应商配置已变更");
                }
                probeCircuit.onSuccess(
                        System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            return Map.of(
                    "ok",
                    true,
                    "stage",
                    "OK",
                    "latencyMs",
                    (System.nanoTime() - started) / 1_000_000,
                    "discoveredModels",
                    names);
        } catch (Exception e) {
            if (!scheduled) store.tested(id, actor, false, revision);
            else {
                if (store.probed(id, actor, "OPEN", revision))
                    probeCircuit.onError(
                            System.nanoTime() - started,
                            java.util.concurrent.TimeUnit.NANOSECONDS,
                            ModelErrors.status(503));
                else probeCircuit.releasePermission();
            }
            return Map.of(
                    "ok",
                    false,
                    "stage",
                    e instanceof PlatformException platform && platform.status() == 401
                            ? "AUTH"
                            : stage,
                    "reason",
                    "连接或认证未通过，请核对模型地址与凭据引用");
        }
    }

    public Map<String, Object> call(JsonNode req, Actor actor, Consumer<String> chunks) {
        if (chunks != null) requireLegacyStreamAllowed();
        if (!capacity.tryAcquire())
            throw new PlatformException(429, "SYS-MODEL-429", "模型调用繁忙，请稍后重试");
        long started = System.nanoTime();
        AtomicLong logId = new AtomicLong();
        AtomicReference<Map<String, Object>> reservation = new AtomicReference<>();
        AtomicBoolean sendStarted = new AtomicBoolean();
        String failureCode = "SYS-MODEL-UNSENT";
        ModelQuota.Permit permit = null;
        io.github.resilience4j.circuitbreaker.CircuitBreaker circuit = null;
        long provider = 0;
        try {
            var entry = store.resolve(req, actor);
            checkCapabilities(req, entry);
            Set<String> needed = set(req.path("requiredLabels"));
            needed.addAll(actor.attributes().getOrDefault("dataLabels", Set.of()));
            checkRoute(needed, entry);
            JsonNode params = req.path("params");
            int maxOutput =
                    params.path("output")
                            .path("maxTokens")
                            .asInt(
                                    params.path("maxOutputTokens")
                                            .asInt(
                                                    ((Number) entry.get("maxOutputTokens"))
                                                            .intValue()));
            if (maxOutput > ((Number) entry.get("maxOutputTokens")).intValue())
                throw new IllegalArgumentException("输出上限超过模型配置");
            int inputTokens = countTokens(req, entry, actor);
            try {
                ModelPolicy.checkBudget(
                        ((Number) entry.get("contextWindow")).intValue(),
                        inputTokens,
                        maxOutput,
                        256);
            } catch (IllegalArgumentException e) {
                throw new PlatformException(413, "SYS-MODEL-413", e.getMessage());
            }
            provider = ((Number) entry.get("providerId")).longValue();
            int timeout = params.path("timeout").path("modelMs").asInt(60000);
            if (timeout < 100 || timeout > 600000)
                throw new IllegalArgumentException("模型超时须在100到600000毫秒之间");
            String secretRef = entry.get("secretRef").toString();
            String secret =
                    secretRef.isBlank() ? "local-no-secret" : SecretVault.resolve(secretRef, actor);
            circuit = circuits.acquire(provider, actor);
            String app = req.path("appCode").asText("_unbound");
            var limits = store.quota(((Number) entry.get("providerId")).longValue(), app, actor);
            permit =
                    quota.acquire(
                            actor,
                            ((Number) entry.get("providerId")).longValue(),
                            app,
                            Math.addExact(inputTokens, maxOutput),
                            ((Number) limits.get("tpm")).intValue(),
                            ((Number) limits.get("rpm")).intValue(),
                            ((Number) limits.get("daily_tokens")).intValue(),
                            ((Number) limits.get("user_daily_tokens")).intValue(),
                            ((Number) limits.get("user_concurrency")).intValue(),
                            ((Number) limits.get("app_concurrency")).intValue(),
                            "EVALUATION".equals(req.path("callKind").asText()),
                            10000,
                            receipt -> {
                                store.reserveQuota(actor, req, entry, receipt);
                                reservation.set(receipt);
                            });
            JsonNode auditReq =
                    ((com.fasterxml.jackson.databind.node.ObjectNode) req.deepCopy())
                            .set("_quotaReceipt", Json.tree(permit.receipt()));
            ((com.fasterxml.jackson.databind.node.ObjectNode) auditReq)
                    .set("requiredLabels", Json.tree(needed));
            var prompt = toPrompt(req, entry.get("modelName").toString(), maxOutput, chunks != null);
            var options = (OpenAiChatOptions) prompt.getOptions();
            String base = entry.get("baseUrl").toString().replaceAll("/+$", "");
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofMillis(timeout));
            var restBuilder = RestClient.builder().requestFactory(factory);
            // 持久化真实的框架序列化请求体后才调用HTTP执行，凭据头绝不进入日志。
            restBuilder.requestInterceptor(
                    (request, body, execution) -> {
                        if (body.length > 2 * 1024 * 1024)
                            throw new IllegalArgumentException("模型请求超过安全大小");
                        checkActualWire(body, entry, actor, inputTokens, maxOutput);
                        long id =
                                store.sending(
                                        actor,
                                        auditReq,
                                        entry,
                                        request.getURI().toString(),
                                        new String(body, java.nio.charset.StandardCharsets.UTF_8),
                                        inputTokens);
                        logId.set(id);
                        store.quotaSending(actor, reservation.get());
                        sendStarted.set(true);
                        var response = execution.execute(request, body);
                        if (response.getStatusCode().isError()) {
                            int status = response.getStatusCode().value();
                            response.close();
                            throw ModelErrors.status(status);
                        }
                        return new BoundedResponse(response, 2 * 1024 * 1024);
                    });
            // reactive分支也必须先冻结完整请求。请求材料使用同一Prompt/options，实际网络前提交。
            var wireConnector =
                    new FrozenRequestConnector(
                            (uri, body) -> {
                                checkActualWire(body, entry, actor, inputTokens, maxOutput);
                                logId.set(
                                        store.sending(
                                                actor,
                                                auditReq,
                                                entry,
                                                uri.toString(),
                                                new String(
                                                        body,
                                                        java.nio.charset.StandardCharsets.UTF_8),
                                                inputTokens));
                                store.quotaSending(actor, reservation.get());
                                sendStarted.set(true);
                            });
            var api =
                    OpenAiApi.builder()
                            .baseUrl(base)
                            .completionsPath(
                                    base.endsWith("/v1")
                                            ? "/chat/completions"
                                            : "/v1/chat/completions")
                            .apiKey(secret)
                            .restClientBuilder(restBuilder)
                            .webClientBuilder(
                                    WebClient.builder()
                                            .clientConnector(wireConnector)
                                            .codecs(
                                                    c ->
                                                            c.defaultCodecs()
                                                                    .maxInMemorySize(
                                                                            2 * 1024 * 1024)))
                            .build();
            var model =
                    OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(options)
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
            ChatResponse response;
            if (chunks == null) {
                response = model.call(prompt);
            } else {
                // 流式实际请求由FrozenRequestConnector在发送字节前持久化；不以近似Prompt替代实际材料。
                AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
                AtomicInteger received = new AtomicInteger();
                new MessageAggregator()
                        .aggregate(model.stream(prompt), finalResponse::set)
                        .doOnNext(
                                delta -> {
                                    if (delta.getResult() != null) {
                                        String text =
                                                Objects.toString(
                                                        delta.getResult().getOutput().getText(),
                                                        "");
                                        int bytes =
                                                text.getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)
                                                        .length;
                                        for (var t : delta.getResult().getOutput().getToolCalls())
                                            bytes +=
                                                    t.arguments()
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets.UTF_8)
                                                            .length;
                                        if (received.addAndGet(bytes) > 2 * 1024 * 1024)
                                            throw new IllegalArgumentException("模型响应超过安全大小");
                                        if (!text.isEmpty()) chunks.accept(text);
                                    }
                                })
                        .blockLast(Duration.ofMillis(timeout));
                response = finalResponse.get();
                if (response == null) throw new IllegalStateException("模型流没有终态");
            }
            Map<String, Object> result = new LinkedHashMap<>(result(response, entry));
            circuits.succeeded(circuit, provider, actor, System.nanoTime() - started);
            circuit = null;
            if (logId.get() != 0)
                store.finished(logId.get(), result, (System.nanoTime() - started) / 1_000_000);
            if (Json.tree(result.get("usage")).path("totalTokens").asInt() > 0)
                try {
                    String outcome =
                            permit.reconcile(
                                    Json.tree(result.get("usage")).path("totalTokens").asInt());
                    store.reconciled(logId.get(), outcome);
                } catch (Exception ignored) {
                    /* 实际响应已持久化，Redis故障由账务恢复任务重投。 */
                }
            result.put(
                    "admission",
                    Map.of(
                            "inputTokens",
                            inputTokens,
                            "reservedTokens",
                            Math.addExact(inputTokens, maxOutput),
                            "queuedMs",
                            permit.queuedMs(),
                            "exact",
                            !entry.get("tokenizerUrl").toString().isBlank()));
            return result;
        } catch (PlatformException e) {
            failureCode = e.code();
            if (circuit != null) {
                if (!sendStarted.get()) circuit.releasePermission();
                else circuits.failed(circuit, provider, actor, System.nanoTime() - started, e);
            }
            if (logId.get() != 0)
                store.failed(logId.get(), e.code(), (System.nanoTime() - started) / 1_000_000);
            throw e;
        } catch (IllegalArgumentException e) {
            failureCode = "SYS-MODEL-4001";
            if (circuit != null) circuit.releasePermission();
            if (logId.get() != 0)
                store.failed(
                        logId.get(), "SYS-MODEL-4001", (System.nanoTime() - started) / 1_000_000);
            throw new PlatformException(400, "SYS-MODEL-4001", e.getMessage());
        } catch (Exception e) {
            var normalized = ModelErrors.normalize(e);
            failureCode = normalized.code();
            if (circuit != null) {
                if (!sendStarted.get()) circuit.releasePermission();
                else
                    circuits.failed(
                            circuit, provider, actor, System.nanoTime() - started, normalized);
            }
            if (logId.get() != 0)
                store.failed(
                        logId.get(), normalized.code(), (System.nanoTime() - started) / 1_000_000);
            throw normalized;
        } finally {
            if (reservation.get() != null && !sendStarted.get()) {
                var receipt = reservation.get();
                try {
                    if (store.quotaNoSend(
                            actor.tenantId(), receipt.get("id").toString(), failureCode)) {
                        String outcome = quota.refundNoSend(receipt);
                        store.noSendReconciled(
                                actor.tenantId(), receipt.get("id").toString(), outcome);
                    }
                } catch (Exception ignored) {
                    /* 意图在Redis预留前已落PG。NO_SEND保留PENDING；若PG暂不可用，RESERVING租约到期恢复。 */
                }
            }
            if (permit != null) permit.close();
            capacity.release();
        }
    }

    /** Same-thread, single-attempt initial stream. No network operation runs inside the send transaction. */
    public Map<String, Object> initialStream(byte[] inbound, String materialRef, Consumer<String> chunks) {
        requireInitialStreamEntry();
        Objects.requireNonNull(chunks);
        final Thread owner = Thread.currentThread();
        final long started = System.nanoTime();
        var current = AuthorityContext.current();
        var input = initialInputs.verify(inbound, materialRef,
                AuthorityContext.requireInitialFor(current.actor(), current.call().resource()));
        byte[] accepted = input.acceptedCanonicalRequestUtf8();
        String manifestSha = input.manifest().manifestSha256();
        var binding = initialBindings.verify(input);
        var identity = initialIdentity(binding);
        var request = Json.read(new String(accepted, java.nio.charset.StandardCharsets.UTF_8));
        var prompt = frozenInitialPrompt(binding, accepted);
        var options = (OpenAiChatOptions) prompt.getOptions();
        int output = options.getMaxTokens();
        int timeout = request.path("params").path("timeout").path("modelMs").asInt(60000);
        if (timeout < 100 || timeout > 600000) throw promptInvalid();
        final long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
        var actor = AuthorityContext.current().actor();
        var entry = store.resolve(request, actor);
        var resolved = new LinkedHashMap<>(entry); resolved.remove("secretRef");
        if (!Json.canonical(resolved).equals(Json.canonical(binding.frozenMetadata()))) throw promptMissing();
        String base = entry.get("baseUrl").toString().replaceAll("/+$", "");
        URI endpoint = URI.create(base + (base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions"));
        String secretRef = entry.get("secretRef").toString();
        String secret = secretRef.isBlank() ? "local-no-secret" : SecretVault.resolve(secretRef, actor);
        long provider = ((Number)entry.get("providerId")).longValue();
        var receipt = new AtomicReference<Map<String,Object>>();
        var permit = new AtomicReference<ModelQuota.Permit>();
        var circuit = new AtomicReference<io.github.resilience4j.circuitbreaker.CircuitBreaker>();
        var logId = new AtomicLong();
        var dispatched = new AtomicBoolean();
        // PREPARED/ROLLED_BACK permit proven NO_SEND; COMMITTING/COMMITTED never imply a refund.
        var commit = new AtomicReference<>("PREPARED");
        var measured = new AtomicInteger();
        Map<String,Object> completedResult;
        boolean completionPersisted = false;
        RuntimeException primaryFailure = null;
        if (!capacity.tryAcquire()) throw new PlatformException(429, "SYS-MODEL-429", "模型调用繁忙");
        var providerHttp = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        try {
            var connector = new FrozenRequestConnector((uri, body) -> {
                initialOwner(owner, deadline);
                if (!endpoint.equals(uri)) throw promptMissing();
                FrozenInitialWireGuard.require(binding, accepted, body);
                var beforeTokenizer = refreshInitial(accepted, materialRef, identity, manifestSha);
                FrozenInitialWireGuard.require(beforeTokenizer, accepted, body);
                initialOwner(owner, deadline);
                var wire = Json.read(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                String semanticSha = Digest.sha256(Json.canonical(wire));
                int tokens = initialTokenCount(wire, semanticSha, entry, AuthorityContext.current().actor(), deadline, () -> {
                    // Credential preparation can block: reacquire current material on this owner at actual egress.
                    var atTokenizerSend = refreshInitial(accepted, materialRef, identity, manifestSha);
                    FrozenInitialWireGuard.require(atTokenizerSend, accepted, body);
                    initialOwner(owner, deadline);
                });
                measured.set(tokens);
                var afterTokenizer = refreshInitial(accepted, materialRef, identity, manifestSha);
                FrozenInitialWireGuard.require(afterTokenizer, accepted, body);
                initialOwner(owner, deadline);
                try { ModelPolicy.checkBudget(((Number)entry.get("contextWindow")).intValue(), tokens, output, 256); }
                catch (IllegalArgumentException overflow) { throw new PlatformException(413,"SYS-MODEL-413",overflow.getMessage()); }
                circuit.set(circuits.acquire(provider, actor));
                String app = request.path("appCode").asText();
                var limits = store.quota(provider, app, actor);
                permit.set(quota.acquire(actor, provider, app, Math.addExact(tokens, output),
                        ((Number)limits.get("tpm")).intValue(), ((Number)limits.get("rpm")).intValue(),
                        ((Number)limits.get("daily_tokens")).intValue(), ((Number)limits.get("user_daily_tokens")).intValue(),
                        ((Number)limits.get("user_concurrency")).intValue(), ((Number)limits.get("app_concurrency")).intValue(),
                        false, 10000, prepared -> { store.reserveQuota(actor, request, entry, prepared); receipt.set(prepared); }));
                var finalBinding = refreshInitial(accepted, materialRef, identity, manifestSha);
                FrozenInitialWireGuard.require(finalBinding, accepted, body);
                initialOwner(owner, deadline);
                var audit = (com.fasterxml.jackson.databind.node.ObjectNode)request.deepCopy();
                audit.set("_quotaReceipt", Json.tree(permit.get().receipt()));
                var metadata = new LinkedHashMap<String,Object>(identity);
                metadata.put("profile", "initial-chat-main-v1"); metadata.put("materialRef", materialRef);
                metadata.put("manifestSha256", manifestSha); metadata.put("actualEndpoint", uri.toString());
                metadata.put("rawWireSha256", Digest.sha256(new String(body, java.nio.charset.StandardCharsets.UTF_8)));
                metadata.put("semanticWireSha256", semanticSha);
                // JSONB stores a JSON STRING; re-encoding this string reconstructs actual SDK bytes.
                metadata.put("wireUtf8", new String(body, java.nio.charset.StandardCharsets.UTF_8));
                metadata.put("tenantId", actor.tenantId()); metadata.put("invocationRef", request.path("invocationRef").asText());
                metadata.put("sendSeq", 1); metadata.put("reservationId", receipt.get().get("id"));
                metadata.put("tokenizer", Map.of("method", "MODEL_TOKENIZER_FULL_REQUEST", "model", entry.get("modelName"),
                        "endpoint", entry.get("tokenizerUrl"), "count", tokens, "request_sha256", semanticSha));
                Long id = initialTransaction.execute(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            initialOwner(owner, deadline); finalBinding.requireVerified(); commit.set("COMMITTING");
                        }
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) commit.set("ROLLED_BACK");
                        }
                    });
                    long inserted = store.sending(actor, audit, entry, uri.toString(),
                            new String(body, java.nio.charset.StandardCharsets.UTF_8), tokens, metadata);
                    store.quotaSending(actor, receipt.get());
                    return inserted;
                });
                commit.set("COMMITTED"); logId.set(Objects.requireNonNull(id));
                // No refresh here: only this same live proof may cross the confirmed commit boundary.
                initialOwner(owner, deadline); finalBinding.requireVerified();
                dispatched.set(true);
            }, new org.springframework.http.client.reactive.JdkClientHttpConnector(providerHttp));
            var api = OpenAiApi.builder().baseUrl(base).completionsPath(base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions")
                    .apiKey(secret).webClientBuilder(WebClient.builder().clientConnector(connector)
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(2097152))).build();
            var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var finalResponse = new AtomicReference<ChatResponse>();
            var flux = new MessageAggregator().aggregate(model.stream(prompt), finalResponse::set)
                    .timeout(reactor.core.publisher.Mono.delay(remaining(deadline)), ignored -> reactor.core.publisher.Mono.delay(remaining(deadline)));
            try (var response = flux.toStream(1)) {
                var iterator = response.iterator();
                while (iterator.hasNext()) {
                    initialOwner(owner, deadline);
                    var delta = iterator.next();
                    refreshInitial(accepted, materialRef, identity, manifestSha);
                    initialOwner(owner, deadline);
                    if (delta.getResult() != null) {
                        String text = Objects.toString(delta.getResult().getOutput().getText(), "");
                        if (!text.isEmpty()) chunks.accept(text);
                    }
                }
            }
            if (finalResponse.get() == null || finalResponse.get().getResult() == null
                    || !"STOP".equals(Objects.toString(
                            finalResponse.get().getResult().getMetadata().getFinishReason(), "").toUpperCase(Locale.ROOT)))
                throw new IllegalStateException("模型流没有终态");
            refreshInitial(accepted, materialRef, identity, manifestSha); initialOwner(owner, deadline);
            var result = new LinkedHashMap<String,Object>(result(finalResponse.get(), entry));
            store.finished(logId.get(), result, (System.nanoTime()-started)/1_000_000);
            completionPersisted = true;
            if (Json.tree(result.get("usage")).path("totalTokens").asInt() > 0) {
                try { store.reconciled(logId.get(), permit.get().reconcile(Json.tree(result.get("usage")).path("totalTokens").asInt())); }
                catch (RuntimeException pending) { /* Durable real usage remains PENDING for accounting-only repair. */ }
            }
            circuits.succeeded(circuit.get(), provider, actor, System.nanoTime()-started); circuit.set(null);
            result.put("admission", Map.of("inputTokens", measured.get(), "reservedTokens", Math.addExact(measured.get(),output), "exact", true));
            completedResult = result;
        } catch (Exception failure) {
            var error = ModelErrors.normalize(failure);
            primaryFailure = error;
            if (!completionPersisted && dispatched.get() && logId.get()!=0) store.failed(logId.get(),error.code(),(System.nanoTime()-started)/1_000_000);
            if (circuit.get()!=null) {
                if (dispatched.get()) circuits.failed(circuit.get(),provider,actor,System.nanoTime()-started,error);
                else circuit.get().releasePermission();
            }
            throw error;
        } finally {
            if (receipt.get()!=null && Set.of("PREPARED","ROLLED_BACK").contains(commit.get()) && !dispatched.get()) {
                try { if (store.quotaNoSend(actor.tenantId(),receipt.get().get("id").toString(),"SYS-MODEL-UNSENT"))
                    store.noSendReconciled(actor.tenantId(),receipt.get().get("id").toString(),quota.refundNoSend(receipt.get())); }
                catch (RuntimeException pending) { /* Only proven rollback enters existing NO_SEND recovery. */ }
            }
            RuntimeException cleanupFailure = null;
            try { if (permit.get()!=null) permit.get().close(); }
            catch (RuntimeException failure) { cleanupFailure = failure; }
            finally {
                capacity.release();
                try { closeInitialHttp(providerHttp); }
                catch (RuntimeException failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
        // Provider completion is durable; output denial must not rewrite facts or refund SENT.
        // Persistence, accounting and transport cleanup waits precede this owner-thread fence.
        refreshInitial(accepted, materialRef, identity, manifestSha);
        initialOwner(owner, deadline);
        return completedResult;
    }

    private FrozenModelBindingVerifier.VerifiedFrozenModelBinding refreshInitial(byte[] accepted, String ref,
            Map<String,Object> identity, String manifestSha) {
        requireInitialStreamEntry();
        var current = AuthorityContext.current();
        var input = initialInputs.verify(accepted, ref, AuthorityContext.requireInitialFor(current.actor(),current.call().resource()));
        if (!manifestSha.equals(input.manifest().manifestSha256())) throw promptMissing();
        var binding = initialBindings.verify(input);
        if (!identity.equals(initialIdentity(binding))) throw promptMissing();
        return binding;
    }

    private static Map<String,Object> initialIdentity(FrozenModelBindingVerifier.VerifiedFrozenModelBinding binding) {
        return Map.of("acceptedCanonicalSha256",binding.requestSha256(),"modelEntryId",binding.modelEntryId(),
                "bindingId",binding.bindingId(),"bindingSha256",binding.bindingSha256(),
                "frozenMetadataSha256",Digest.sha256(Json.canonical(binding.frozenMetadata())));
    }
    private static void initialOwner(Thread owner, long deadline) {
        if (Thread.currentThread()!=owner) throw promptMissing();
        if (Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline)
            throw new PlatformException(504,"SYS-MODEL-504","模型调用超时");
    }
    private static Duration remaining(long deadline) { return Duration.ofNanos(Math.max(1,deadline-System.nanoTime())); }

    private static void closeInitialHttp(java.net.http.HttpClient http) {
        http.shutdownNow();
        try { if (!http.awaitTermination(Duration.ofSeconds(2))) throw new IllegalStateException("MODEL_HTTP_CLOSE_TIMEOUT"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("MODEL_HTTP_CLOSE_INTERRUPTED",interrupted);
        }
        http.close(); // Already terminated; this is not relied upon as a timeout mechanism.
    }

    private int initialTokenCount(JsonNode request, String semanticSha, Map<String,Object> entry, Actor actor, long deadline, Runnable beforeSend) {
        String configured=entry.get("tokenizerUrl").toString();
        if (configured.isBlank()) throw new PlatformException(409,"MODEL_TOKENIZER_REQUIRED","初始模型必须配置专用计量端点");
        URI target=ModelPolicy.endpoint(configured),provider=ModelPolicy.endpoint(entry.get("baseUrl").toString());
        if (!target.getScheme().equals(provider.getScheme()) || !target.getAuthority().equalsIgnoreCase(provider.getAuthority()))
            throw promptInvalid();
        var body=Map.of("model",entry.get("modelName"),"messages",request.path("messages"),"tools",request.path("tools"),
                "request",request,"request_sha256",semanticSha,"add_generation_prompt",true);
        var http=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        try {
            var outgoing=java.net.http.HttpRequest.newBuilder(target).timeout(Duration.ofMillis(Math.max(1,Math.min(10000,remaining(deadline).toMillis()))))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(body)));
            String ref=entry.get("secretRef").toString();
            if (!ref.isBlank()) outgoing.header("Authorization","Bearer "+SecretVault.resolve(ref,actor));
            beforeSend.run();
            outgoing.timeout(Duration.ofMillis(Math.max(1,Math.min(10000,remaining(deadline).toMillis()))));
            // The future deadline covers the entire bounded body, including a peer that drips after headers.
            var future=http.sendAsync(outgoing.build(), info -> new java.net.http.HttpResponse.BodySubscriber<byte[]>() {
                final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                final java.util.concurrent.CompletableFuture<byte[]> done=new java.util.concurrent.CompletableFuture<>();
                java.util.concurrent.Flow.Subscription subscription;
                public java.util.concurrent.CompletionStage<byte[]> getBody(){return done;}
                public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
                public void onNext(List<java.nio.ByteBuffer> buffers){
                    for(var buffer:buffers){if(buffer.remaining()>65536-bytes.size()){subscription.cancel();done.completeExceptionally(promptInvalid());return;}
                        byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
                    subscription.request(1);
                }
                public void onError(Throwable e){done.completeExceptionally(e);}
                public void onComplete(){done.complete(bytes.toByteArray());}
            });
            java.net.http.HttpResponse<byte[]> response;
            try { response=future.get(Math.max(1,Math.min(10000,remaining(deadline).toMillis())),java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) {
                future.cancel(true); throw new PlatformException(504,"SYS-MODEL-504","模型计量超时");
            } catch (java.util.concurrent.ExecutionException failure) {
                throw ModelErrors.normalize(failure.getCause());
            } finally { if (!future.isDone()) future.cancel(true); }
            if(response.statusCode()!=200)throw ModelErrors.status(response.statusCode());
            var count=Json.read(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));
            if(!count.path("count").isIntegralNumber() || !count.path("count").canConvertToInt() || count.path("count").intValue()<1
                    || !semanticSha.equals(count.path("request_sha256").asText())) throw promptInvalid();
            return count.path("count").intValue();
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new PlatformException(503,"SYS-MODEL-503","模型计量暂不可用");}
        finally { closeInitialHttp(http); }
    }

    private int countTokens(JsonNode request, Map<String, Object> entry, Actor actor) {
        String model = entry.get("modelName").toString(),
                tokenizer = entry.get("tokenizerUrl").toString();
        // jtokkit的模型表只适用于其明确支持的模型；未知模型不能用固定字符系数放行。
        if (!tokenizer.isBlank()) {
            URI target = ModelPolicy.endpoint(tokenizer),
                    provider = ModelPolicy.endpoint(entry.get("baseUrl").toString());
            if (!target.getAuthority().equalsIgnoreCase(provider.getAuthority())
                    || !target.getScheme().equals(provider.getScheme()))
                throw new IllegalArgumentException("专用tokenizer须与供应商端点处于同一信任源");
            var outgoing = client(10000).post().uri(tokenizer);
            String ref = entry.get("secretRef").toString();
            if (!ref.isBlank())
                outgoing.header("Authorization", "Bearer " + SecretVault.resolve(ref, actor));
            String materialSha = Digest.sha256(Json.canonical(request));
            JsonNode count =
                    outgoing.body(
                                    Map.of(
                                            "model",
                                            model,
                                            "messages",
                                            request.path("messages"),
                                            "tools",
                                            request.has("toolDefinitions")
                                                    ? request.path("toolDefinitions")
                                                    : request.path("tools"),
                                            "request",
                                            request,
                                            "request_sha256",
                                            materialSha,
                                            "add_generation_prompt",
                                            true))
                            .exchange(
                                    (req, res) -> {
                                        if (res.getStatusCode().isError())
                                            throw ModelErrors.status(res.getStatusCode().value());
                                        byte[] bytes = res.getBody().readNBytes(65537);
                                        if (bytes.length > 65536)
                                            throw new IllegalArgumentException("tokenizer响应超出安全大小");
                                        return Json.read(
                                                new String(
                                                        bytes,
                                                        java.nio.charset.StandardCharsets.UTF_8));
                                    });
            if (count == null
                    || !count.path("count").canConvertToInt()
                    || count.path("count").asInt() < 1)
                throw new IllegalArgumentException("模型tokenizer未返回有效count");
            if ((request.has("response_format")
                            || request.path("params").path("output").has("format"))
                    && !materialSha.equals(count.path("request_sha256").asText()))
                throw new IllegalArgumentException("专用tokenizer未确认包含输出格式的完整请求计量");
            return count.path("count").asInt();
        }
        return countLocalTokens(model, Json.canonical(request));
    }

    static int countLocalTokens(String model, String canonicalInput) {
        var encoding = Encodings.newDefaultEncodingRegistry().getEncodingForModel(model);
        if (encoding.isEmpty()) throw new IllegalArgumentException("该模型尚未配置专用tokenizer，请先完成模型计量校准");
        return encoding.get().countTokens(canonicalInput) + 64;
    }

    private void checkActualWire(
            byte[] wire, Map<String, Object> entry, Actor actor, int reservedInput, int output) {
        int actual =
                countTokens(
                        Json.read(new String(wire, java.nio.charset.StandardCharsets.UTF_8)),
                        entry,
                        actor);
        if (actual > reservedInput)
            throw new PlatformException(413, "SYS-MODEL-413", "完整模型请求计量超过预留，请缩减上下文或输出格式");
        try {
            ModelPolicy.checkBudget(
                    ((Number) entry.get("contextWindow")).intValue(), actual, output, 256);
        } catch (IllegalArgumentException e) {
            throw new PlatformException(413, "SYS-MODEL-413", e.getMessage());
        }
    }

    /** One provider conversion for legacy calls and private frozen initial preparation. */
    static Prompt toPrompt(JsonNode req, String modelName, int maxOutput, boolean streaming) {
        JsonNode params = req.path("params");
        var options =
                OpenAiChatOptions.builder()
                        .model(modelName)
                        .internalToolExecutionEnabled(false)
                        .maxTokens(maxOutput);
        if (streaming) options.streamUsage(true);
        if (params.has("temperature"))
            options.temperature(nativeNumber(params.get("temperature"), 0, 2));
        if (params.has("topP")) options.topP(nativeNumber(params.get("topP"), 0, 1));
        if (params.has("frequencyPenalty"))
            options.frequencyPenalty(nativeNumber(params.get("frequencyPenalty"), -2, 2));
        if (params.has("presencePenalty"))
            options.presencePenalty(nativeNumber(params.get("presencePenalty"), -2, 2));
        if (params.has("seed")) {
            JsonNode seed = params.get("seed");
            if (!seed.isIntegralNumber()) throw promptInvalid();
            // The pinned Spring AI 1.0 native API takes Integer; never truncate accepted int64.
            if (!seed.canConvertToInt()) throw promptUnsupported();
            options.seed(seed.intValue());
        }
        if (params.has("stop")) {
            JsonNode stop = params.get("stop");
            List<String> sequences = new ArrayList<>();
            if (stop.isTextual()) sequences.add(stop.textValue());
            else {
                if (!stop.isArray() || stop.size() > 4) throw promptInvalid();
                for (JsonNode sequence : stop) {
                    if (!sequence.isTextual()) throw promptInvalid();
                    sequences.add(sequence.textValue());
                }
            }
            options.stop(sequences);
        }
        if (params.path("output").path("format").path("type").asText().equals("json")) {
            JsonNode schema = params.path("output").path("format").path("schema");
            options.responseFormat(
                    new ResponseFormat(
                            schema.isObject() && !schema.isEmpty()
                                    ? ResponseFormat.Type.JSON_SCHEMA
                                    : ResponseFormat.Type.JSON_OBJECT,
                            schema.isObject() ? schema.toString() : null));
        }
        List<OpenAiApi.FunctionTool> tools = new ArrayList<>();
        for (JsonNode t : req.path("toolDefinitions")) {
            JsonNode fn = t.path("function");
            tools.add(
                    new OpenAiApi.FunctionTool(
                            new OpenAiApi.FunctionTool.Function(
                                    fn.path("description").asText(),
                                    fn.path("name").asText(),
                                    fn.path("parameters").toString())));
        }
        options.tools(tools);
        return new Prompt(messages(req.path("messages")), options.build());
    }

    /** Ordinary prepared data, not authority to send; every future send needs fresh guards. */
    static Prompt frozenInitialPrompt(
            FrozenModelBindingVerifier.VerifiedFrozenModelBinding binding, byte[] capturedCanonicalUtf8) {
        long started = System.nanoTime();
        checkPromptTime(started);
        if (binding == null) throw promptMissing();
        binding.requireVerified();
        if (capturedCanonicalUtf8 == null || capturedCanonicalUtf8.length < 1 || capturedCanonicalUtf8.length > 2097152)
            throw promptInvalid();
        byte[] bytes = capturedCanonicalUtf8.clone();
        // Identity belongs to accepted RAW bytes, before decoding or framework numeric spelling.
        try {
            String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!sha.equals(binding.requestSha256())) throw promptMissing();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        checkPromptTime(started); binding.requireVerified();
        var metadata = binding.frozenMetadata();
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { throw promptInvalid(); }
        JsonNode request;
        try { request = Json.read(text); }
        catch (RuntimeException e) { throw promptMissing(); }
        var params = request.path("params");
        if (params.has("topK") || params.has("sampling") && !params.path("sampling").isEmpty())
            throw promptUnsupported();
        var output = params.path("output");
        var limit = output.has("maxTokens") ? output.get("maxTokens")
                : params.has("maxOutputTokens") ? params.get("maxOutputTokens") : metadata.get("maxOutputTokens");
        if (limit == null || !limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue() < 1
                || limit.intValue() > metadata.path("maxOutputTokens").intValue())
            throw new PlatformException(409, "SYS-MODEL-4002", "模型能力或输出上限不满足请求");
        checkPromptTime(started); binding.requireVerified();
        Prompt prompt = toPrompt(request, metadata.path("modelName").asText(), limit.intValue(), true);
        checkPromptTime(started); binding.requireVerified();
        return prompt;
    }

    private static void checkPromptTime(long started) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= 2_000_000_000L)
            throw new PlatformException(503, "SYS-MODEL-503", "模型请求准备暂不可用");
    }
    private static PlatformException promptMissing() {
        return new PlatformException(403, "MATERIAL_SCOPE_MISSING_OR_CHANGED", "材料范围缺失或已改变");
    }
    private static PlatformException promptInvalid() {
        return new PlatformException(400, "MODEL_INPUT_INVALID", "模型输入无效");
    }
    private static PlatformException promptUnsupported() {
        return new PlatformException(409, "MODEL_REQUEST_OPTION_UNSUPPORTED", "模型请求参数没有受支持的原生映射");
    }
    private static double nativeNumber(JsonNode value, double min, double max) {
        if (!value.isNumber()) throw promptInvalid();
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) throw promptInvalid();
        return number;
    }

    static List<Message> messages(JsonNode source) {
        List<Message> result = new ArrayList<>();
        Map<String, String> callNames = new HashMap<>();
        for (JsonNode node : source) {
            String text = node.path("content").asText("");
            switch (node.path("role").asText()) {
                case "system" -> result.add(new SystemMessage(text));
                case "user" -> result.add(new UserMessage(text));
                case "assistant" -> {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (JsonNode c : node.path("tool_calls")) {
                        JsonNode fn = c.path("function");
                        String name = fn.path("name").asText(), id = c.path("id").asText();
                        callNames.put(id, name);
                        calls.add(
                                new AssistantMessage.ToolCall(
                                        id,
                                        "function",
                                        name,
                                        fn.path("arguments").isTextual()
                                                ? fn.path("arguments").asText()
                                                : fn.path("arguments").toString()));
                    }
                    result.add(new AssistantMessage(text, Map.of(), calls));
                }
                case "tool" -> {
                    String id = node.path("tool_call_id").asText();
                    if (!callNames.containsKey(id))
                        throw new IllegalArgumentException("工具结果没有对应的模型调用");
                    result.add(
                            new ToolResponseMessage(
                                    List.of(
                                            new ToolResponseMessage.ToolResponse(
                                                    id, callNames.get(id), text))));
                }
                default -> throw new IllegalArgumentException("不支持的消息角色");
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("模型消息不能为空");
        return result;
    }

    private Map<String, Object> result(ChatResponse response, Map<String, Object> entry) {
        var output = response.getResult().getOutput();
        List<Object> calls = new ArrayList<>();
        for (var c : output.getToolCalls())
            calls.add(
                    Map.of("id", c.id(), "name", c.name(), "arguments", Json.read(c.arguments())));
        var usage = response.getMetadata().getUsage();
        Map<String, Object> use = new LinkedHashMap<>();
        if (usage != null && usage.getTotalTokens() > 0) {
            use.put("promptTokens", usage.getPromptTokens());
            use.put("completionTokens", usage.getCompletionTokens());
            use.put("totalTokens", usage.getTotalTokens());
        }
        return Map.of(
                "text",
                Objects.toString(output.getText(), ""),
                "toolCalls",
                calls,
                "usage",
                use,
                "actualModel",
                Map.of(
                        "modelEntryId",
                        entry.get("id"),
                        "modelName",
                        entry.get("modelName"),
                        "providerId",
                        entry.get("providerId"),
                        "endpoint",
                        entry.get("baseUrl")));
    }

    private static Set<String> set(JsonNode source) {
        Set<String> set = new HashSet<>();
        if (source.isArray()) source.forEach(n -> set.add(n.asText()));
        return set;
    }

    private static void checkRoute(Set<String> labels, Map<String, Object> entry) {
        try {
            ModelPolicy.checkLabels(
                    labels, set(Json.tree(((Map<?, ?>) entry.get("provider")).get("trustLabels"))));
        } catch (IllegalArgumentException e) {
            throw new PlatformException(403, "SYS-EGRESS-4031", e.getMessage());
        }
    }

    private static void checkCapabilities(JsonNode req, Map<String, Object> entry) {
        var caps = set(Json.tree(entry.get("capabilities")));
        if (!caps.contains("chat")
                || !req.path("toolDefinitions").isEmpty() && !caps.contains("tools")
                || "json"
                                .equals(
                                        req.path("params")
                                                .path("output")
                                                .path("format")
                                                .path("type")
                                                .asText())
                        && !caps.contains("json_mode"))
            throw new PlatformException(409, "SYS-MODEL-4002", "模型能力不足，请调整绑定或调用参数");
    }

    private static RestClient client(int timeout) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(f).build();
    }
}
