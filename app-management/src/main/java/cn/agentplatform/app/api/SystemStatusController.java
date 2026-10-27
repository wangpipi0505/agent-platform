package cn.agentplatform.app.api;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.ServiceTokens;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读运行环境自检：给管理台 /system 一页可读的 PG、Redis 与各服务状态。 */
@RestController
public class SystemStatusController {
    private final JdbcTemplate db;
    private final String appUrl;
    private final String engineUrl;
    private final String skillUrl;
    private final String modelUrl;
    private final String monitorUrl;
    private final String redisAddress;
    private final String redisPassword;

    public SystemStatusController(
            JdbcTemplate db,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl,
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String skillUrl,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String modelUrl,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}") String monitorUrl,
            @Value("${agent.system.redis-address:127.0.0.1:26379}") String redisAddress,
            @Value("${AGENT_REDIS_PASSWORD:}") String redisPassword) {
        this.db = db;
        this.appUrl = appUrl;
        this.engineUrl = engineUrl;
        this.skillUrl = skillUrl;
        this.modelUrl = modelUrl;
        this.monitorUrl = monitorUrl;
        this.redisAddress = redisAddress;
        this.redisPassword = redisPassword;
    }

    @GetMapping("/admin/system/status")
    public Object status() {
        var checks = new ArrayList<Map<String, Object>>();
        checks.add(database());
        checks.add(redis());
        checks.add(http("app", "应用管理", appUrl, false));
        checks.add(http("engine", "运行时", engineUrl, false));
        checks.add(http("skill", "技能注册", skillUrl, false));
        checks.add(model(Actor.current()));
        checks.add(http("monitor", "监控/审批/统计", monitorUrl, true));
        boolean ok = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", ok);
        result.put("version", "v1.2");
        result.put("checkedAt", Instant.now().toString());
        result.put("checks", checks);
        return ApiResponse.ok(result);
    }

    private Map<String, Object> database() {
        long started = System.nanoTime();
        try {
            db.queryForObject("select 1", Integer.class);
            return check("database", "PostgreSQL", true, elapsed(started), "连接正常");
        } catch (Exception failure) {
            return check("database", "PostgreSQL", false, elapsed(started), "连接失败：" + root(failure));
        }
    }

    private Map<String, Object> redis() {
        long started = System.nanoTime();
        String host = redisAddress;
        int port = 26379;
        int at = redisAddress.lastIndexOf(':');
        if (at > 0) {
            host = redisAddress.substring(0, at);
            try { port = Integer.parseInt(redisAddress.substring(at + 1)); } catch (NumberFormatException ignored) { }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 800);
            socket.setSoTimeout(800);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            // 生产/开发缓存都启用 requirepass；不带 AUTH 的 PING 会得到 -NOAUTH。
            // 只发 PING 无法区分“端口活但未授权”和“真正可用”，这里按配置补 AUTH。
            if (!redisPassword.isBlank()) {
                byte[] password = redisPassword.getBytes(StandardCharsets.UTF_8);
                String auth = "*2\r\n$4\r\nAUTH\r\n$" + password.length + "\r\n" + redisPassword + "\r\n";
                out.write(auth.getBytes(StandardCharsets.UTF_8));
            }
            out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.US_ASCII));
            if (!redisPassword.isBlank()) {
                String authReply = reader.readLine();
                if (authReply == null || !authReply.startsWith("+OK")) {
                    throw new IllegalStateException("认证失败：" + (authReply == null ? "无响应" : authReply));
                }
            }
            String reply = reader.readLine();
            if (reply == null || !reply.startsWith("+PONG")) throw new IllegalStateException("非 PONG 响应");
            return check("redis", "Redis", true, elapsed(started), "连接正常");
        } catch (Exception failure) {
            return check("redis", "Redis", false, elapsed(started), "未连通：" + root(failure) + "（发布/审批/统计/审计将不可用）");
        }
    }

    /**
     * 模型网关的进程存活不等于模型可用：鉴权、上游模型、工具能力和 tokenizer 都可能单独失败。
     * 因此系统状态必须消费深度健康结果，而不是只打 /actuator/health；探测本身在模型网关内
     * 有 TTL 缓存和刷新限流，这里只发起一次聚合读取。
     */
    private Map<String, Object> model(Actor actor) {
        long started = System.nanoTime();
        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(900)).build();
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(modelUrl + "/admin/model-health?refresh=true"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                return check(
                        "model",
                        "模型网关",
                        false,
                        elapsed(started),
                        "进程已监听，但深度检查返回 " + response.statusCode());
            JsonNode data = Json.read(response.body()).path("data");
            String status = data.path("status").asText("UNKNOWN");
            JsonNode counts = data.path("counts");
            boolean ok = "HEALTHY".equals(status);
            String reason = data.path("reason").asText("");
            String detail =
                    ok
                            ? "深度检查通过（健康 "
                                    + counts.path("healthy").asInt()
                                    + "/"
                                    + counts.path("total").asInt()
                                    + "）"
                            : "深度检查 "
                                    + status
                                    + (reason.isBlank()
                                            ? "：健康 "
                                                    + counts.path("healthy").asInt()
                                                    + "，降级 "
                                                    + counts.path("degraded").asInt()
                                                    + "，不可用 "
                                                    + counts.path("unhealthy").asInt()
                                            : "：" + reason);
            Map<String, Object> value = check("model", "模型网关", ok, elapsed(started), detail);
            value.put("modelStatus", status);
            value.put("modelCounts", Json.read(counts.toString()));
            return value;
        } catch (Exception failure) {
            return check(
                    "model",
                    "模型网关",
                    false,
                    elapsed(started),
                    "进程可达，但深度检查失败：" + root(failure));
        }
    }

    private Map<String, Object> http(String key, String label, String baseUrl, boolean dependency) {
        long started = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(900)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(Duration.ofMillis(1200)).GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() == 200;
            String detail = ok ? "运行正常" : (response.statusCode() == 503
                    ? "进程已监听，但依赖未就绪（503）"
                    : "健康检查返回 " + response.statusCode());
            // 503 表示服务在跑但依赖未就绪；依赖型服务按不健康展示，避免把发布能力误判为可用。
            if (dependency && response.statusCode() == 503) ok = false;
            else if (!dependency && response.statusCode() == 503) ok = true;
            return check(key, label, ok, elapsed(started), detail);
        } catch (Exception failure) {
            return check(key, label, false, elapsed(started), "无法连接：" + root(failure));
        }
    }

    private static Map<String, Object> check(String key, String label, boolean ok, long ms, String detail) {
        var value = new LinkedHashMap<String, Object>();
        value.put("key", key);
        value.put("label", label);
        value.put("ok", ok);
        value.put("latencyMs", ms);
        value.put("detail", detail);
        return value;
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            // 连接被拒时 JDK 的异常常常没有 message，直接把异常类名暴露给页面不可读。
            if (current instanceof java.net.ConnectException) return "连接被拒绝（端口未监听）";
            if (current instanceof java.nio.channels.ClosedChannelException) return "连接已关闭（端口未监听）";
            if (current instanceof java.net.http.HttpConnectTimeoutException) return "连接超时";
            if (current instanceof java.net.SocketTimeoutException) return "读取超时";
        }
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
