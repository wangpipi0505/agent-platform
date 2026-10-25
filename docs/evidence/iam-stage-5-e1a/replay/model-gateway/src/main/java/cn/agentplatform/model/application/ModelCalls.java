package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
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

    public ModelCalls(ModelStore store, ModelQuota quota) {
        this.store = store;
        this.quota = quota;
        this.circuits = new ModelCircuits(store);
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
            var options =
                    OpenAiChatOptions.builder()
                            .model(entry.get("modelName").toString())
                            .internalToolExecutionEnabled(false)
                            .maxTokens(maxOutput);
            if (chunks != null) options.streamUsage(true);
            if (params.has("temperature"))
                options.temperature(params.path("temperature").asDouble());
            if (params.has("topP")) options.topP(params.path("topP").asDouble());
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
                            .defaultOptions(options.build())
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
            var prompt = new Prompt(messages(req.path("messages")), options.build());
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
        var encoding = Encodings.newDefaultEncodingRegistry().getEncodingForModel(model);
        if (encoding.isEmpty()) throw new IllegalArgumentException("该模型尚未配置专用tokenizer，请先完成模型计量校准");
        return encoding.get().countTokens(Json.canonical(request)) + 64;
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
