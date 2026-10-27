package cn.agentplatform.skill.infra.localspike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IN-09 spike：LOCAL 技能串行执行入口。
 *
 * <p>设计不变量：LOCAL 技能实例级串行队列（并发=1）。visibility-trajectory 算法存在
 * 全局可变状态并发债，任何并发调用都会互相污染结果，因此单实例内必须严格 FIFO 串行。
 * 实现方式：单线程 Executor——调用方线程可并发提交，任务按提交顺序排队执行；
 * 每次执行记录 [开始, 结束] 区间供测试断言"区间不重叠"。
 * AP-S05 正式实现需决定：队列上限/背压策略、排队超时、多实例时的分片策略。
 */
public class LocalSpikeInvoker implements AutoCloseable {

    /** 一次真实执行的时间区间（用于断言串行性：任意两区间不得重叠）。 */
    public record Interval(int seq, Instant start, Instant end, long millis) {}

    private final LocalSpikeConfig config;
    private final ExecutorService serial = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "local-spike-serial");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Interval> executionLog = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();

    public LocalSpikeInvoker(LocalSpikeConfig config) {
        this.config = config;
    }

    /** 提交真实业务调用并阻塞等待结果（FIFO 排到队首才真正发 HTTP）。返回响应原文。 */
    public String call(String jsonPayload) {
        try {
            Future<String> future = serial.submit(() -> execute(jsonPayload));
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LocalSpikeException("串行调用失败", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalSpikeException("等待串行队列被中断", e);
        }
    }

    private String execute(String payload) throws Exception {
        Instant start = Instant.now();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.searchUrl()))
                .timeout(config.callTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new LocalSpikeException.InvocationException(
                    "search 返回 HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode tree = mapper.readTree(response.body());
        if (tree == null || !tree.hasNonNull("referenceUtc") || !tree.has("allResults")) {
            throw new LocalSpikeException.InvocationException(
                    "search 响应缺契约字段(referenceUtc/allResults): " + truncate(response.body()));
        }
        long millis = Duration.between(start, Instant.now()).toMillis();
        executionLog.add(new Interval(seq.incrementAndGet(), start, Instant.now(), millis));
        return response.body();
    }

    /** 已完成调用的执行区间（按完成顺序）。 */
    public List<Interval> executionLog() {
        return List.copyOf(executionLog);
    }

    @Override
    public void close() {
        serial.shutdownNow();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "(空响应)";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
