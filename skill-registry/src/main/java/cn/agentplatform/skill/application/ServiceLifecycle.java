package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ServiceManifest;
import cn.agentplatform.skill.infra.ProcessTree;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AP-S05 通用外部服务生命周期。
 *
 * <p>边界与不变量：
 * <ul>
 *   <li>manifest 在 PG，管理面只能登记显式参数数组；执行时不经过 shell。</li>
 *   <li>平台只终止自己记录的进程树。重启后遗留的进程先按 PID + 启动时间核对，再清理；
 *       不认识的进程只报端口占用，绝不误杀。</li>
 *   <li>所有生命周期动作要求 ADMIN；stop/restart/delete 额外要求 confirm=code，
 *       让人工确认成为服务端事实，而不是前端按钮的装饰。</li>
 *   <li>日志只从配置目录下的 code.log 读取，不接受路径参数；运行态是易失的，
 *       PG 只保存最后的可审计状态，不假装进程能跨重启存活。</li>
 * </ul>
 */
@Service
public class ServiceLifecycle {
    private static final System.Logger LOG = System.getLogger(ServiceLifecycle.class.getName());
    private static final Set<String> RUNNING_STATES =
            Set.of("STARTING", "READY", "UNHEALTHY");
    private static final long HEARTBEAT_STALE_SECONDS = 90;
    private static final long MAX_LOG_BYTES = 512 * 1024;

    private final JdbcTemplate db;
    private final Path logDir;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String instanceId;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile boolean hookInstalled;

    public ServiceLifecycle(
            JdbcTemplate db,
            @Value("${agent.services.log-dir:${user.dir}/.local/service-logs}") String logDir) {
        this.db = db;
        this.logDir = Path.of(logDir).toAbsolutePath().normalize();
        this.instanceId =
                host() + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
    }

    public List<Map<String, Object>> list() {
        admin();
        String tenant = tenant();
        Map<String, Map<String, Object>> persisted = runtimeRows(tenant);
        var result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : manifestRows(tenant)) {
            ServiceManifest manifest = ServiceManifest.fromRow(row);
            result.add(view(tenant, manifest, persisted.get(manifest.code())));
        }
        return result;
    }

    public Map<String, Object> get(String code) {
        admin();
        String tenant = tenant();
        ServiceManifest manifest = load(tenant, code);
        return view(tenant, manifest, runtimeRow(tenant, code));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        admin();
        String tenant = tenant();
        String actor = actor();
        ServiceManifest manifest = ServiceManifest.parse(body);
        synchronized (lock(manifest.code())) {
            try {
                db.update(
                        "insert into skill.service_manifest(tenant_id,code,name,command,work_dir,"
                                + "port,health_path,startup_timeout_ms,stop_timeout_ms,enabled,"
                                + "created_by,updated_by) values(?,?,?,?::jsonb,?,?,?,?,?,?,?,?)",
                        tenant,
                        manifest.code(),
                        manifest.name(),
                        Json.write(manifest.command()),
                        manifest.workDir(),
                        manifest.port(),
                        manifest.healthPath(),
                        manifest.startupTimeoutMs(),
                        manifest.stopTimeoutMs(),
                        manifest.enabled(),
                        actor,
                        actor);
            } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                throw error(409, "SERVICE_EXISTS", "服务编码已存在: " + manifest.code());
            }
            audit(tenant, actor, manifest.code(), "CREATE", "SUCCESS",
                    Map.of("port", manifest.port()));
            return view(tenant, manifest, null);
        }
    }

    public Map<String, Object> update(String code, Map<String, Object> body) {
        admin();
        String tenant = tenant();
        String actor = actor();
        synchronized (lock(code)) {
            load(tenant, code);
            if (alive(code)) throw error(409, "SERVICE_RUNNING", "服务运行中，请先停止再修改");
            var copy = new LinkedHashMap<String, Object>(body);
            copy.put("code", code);
            ServiceManifest manifest = ServiceManifest.parse(copy);
            int changed =
                    db.update(
                            "update skill.service_manifest set name=?,command=?::jsonb,work_dir=?,"
                                    + "port=?,health_path=?,startup_timeout_ms=?,stop_timeout_ms=?,"
                                    + "enabled=?,updated_by=?,updated_at=now() where tenant_id=? and code=?",
                            manifest.name(),
                            Json.write(manifest.command()),
                            manifest.workDir(),
                            manifest.port(),
                            manifest.healthPath(),
                            manifest.startupTimeoutMs(),
                            manifest.stopTimeoutMs(),
                            manifest.enabled(),
                            actor,
                            tenant,
                            code);
            if (changed == 0) throw notFound(code);
            audit(tenant, actor, code, "UPDATE", "SUCCESS",
                    Map.of("port", manifest.port(), "enabled", manifest.enabled()));
            return view(tenant, manifest, runtimeRow(tenant, code));
        }
    }

    public Map<String, Object> delete(String code, boolean confirmed) {
        admin();
        String tenant = tenant();
        String actor = actor();
        synchronized (lock(code)) {
            load(tenant, code);
            requireConfirm(code, confirmed);
            if (alive(code)) throw error(409, "SERVICE_RUNNING", "服务运行中，请先停止再删除");
            Map<String, Object> row = runtimeRow(tenant, code);
            if (row != null && RUNNING_STATES.contains(Objects.toString(row.get("state"), "")))
                throw error(409, "SERVICE_NOT_MANAGED",
                        "服务状态不是 STOPPED，不能删除；请先确认进程已停止");
            db.update("delete from skill.service_manifest where tenant_id=? and code=?", tenant, code);
            audit(tenant, actor, code, "DELETE", "SUCCESS", Map.of());
            return Map.of("deleted", true, "code", code);
        }
    }

    public Map<String, Object> start(String code) {
        admin();
        String tenant = tenant();
        String actor = actor();
        synchronized (lock(code)) {
            return startInternal(tenant, actor, load(tenant, code));
        }
    }

    public Map<String, Object> stop(String code, boolean confirmed) {
        admin();
        String tenant = tenant();
        String actor = actor();
        synchronized (lock(code)) {
            requireConfirm(code, confirmed);
            ServiceManifest manifest = load(tenant, code);
            Running current = running.get(code);
            if (current == null) {
                Map<String, Object> row = runtimeRow(tenant, code);
                if (row == null || "STOPPED".equals(Objects.toString(row.get("state"), "")))
                    return view(tenant, manifest, row);
                throw error(409, "SERVICE_NOT_MANAGED",
                        "服务不在本实例托管；只允许本实例终止自己拉起的进程");
            }
            stopInternal(tenant, actor, manifest, current, "STOP");
            return view(tenant, manifest, runtimeRow(tenant, code));
        }
    }

    public Map<String, Object> restart(String code, boolean confirmed) {
        admin();
        String tenant = tenant();
        String actor = actor();
        synchronized (lock(code)) {
            requireConfirm(code, confirmed);
            ServiceManifest manifest = load(tenant, code);
            Running current = running.get(code);
            if (current != null) stopInternal(tenant, actor, manifest, current, "RESTART_STOP");
            Map<String, Object> result = startInternal(tenant, actor, manifest);
            audit(tenant, actor, code, "RESTART", "SUCCESS",
                    Map.of("pid", result.getOrDefault("pid", -1)));
            return result;
        }
    }

    public Map<String, Object> health(String code) {
        admin();
        String tenant = tenant();
        ServiceManifest manifest = load(tenant, code);
        Running current = running.get(code);
        Map<String, Object> probe = probe(manifest);
        boolean healthy = Boolean.TRUE.equals(probe.get("healthy"));
        if (current != null) {
            if (healthy && current.state != State.READY) {
                current.state = State.READY;
                current.lastError = "";
                updateRuntime(tenant, code, current, Instant.now());
            } else if (!healthy && current.state == State.READY) {
                current.state = State.UNHEALTHY;
                current.lastError = Objects.toString(probe.get("message"), "健康检查失败");
                updateRuntime(tenant, code, current, Instant.now());
            }
        }
        var result = status(tenant, manifest, current, runtimeRow(tenant, code));
        result.putAll(probe);
        result.put("code", code);
        result.put("managed", current != null);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    public Map<String, Object> logs(String code, int lines) {
        admin();
        String tenant = tenant();
        ServiceManifest manifest = load(tenant, code);
        int limit = Math.max(1, Math.min(lines, 1000));
        Path file = manifest.logFile(logDir);
        if (!Files.isRegularFile(file))
            return Map.of("code", code, "available", false, "text", "", "lines", 0);
        try {
            long size = Files.size(file);
            byte[] bytes = readTail(file, Math.min(size, MAX_LOG_BYTES));
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> all = text.lines().toList();
            int from = Math.max(0, all.size() - limit);
            String tail = String.join("\n", all.subList(from, all.size()));
            return Map.of(
                    "code", code,
                    "available", true,
                    "text", tail,
                    "lines", all.size() - from,
                    "bytes", size,
                    "truncated", size > MAX_LOG_BYTES || all.size() > limit);
        } catch (IOException ex) {
            throw error(500, "SERVICE_LOG_UNAVAILABLE", "读取服务日志失败");
        }
    }

    /**
     * 启动前会清理本平台记录的陈旧运行态。只核对 PID 与启动时间，避免 PID 复用误杀；
     * 没通过核对的活动进程留给端口冲突报错，绝不使用“看到端口就杀”的危险策略。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupStaleOnStartup() {
        try {
            cleanupStale(Instant.now().minusSeconds(HEARTBEAT_STALE_SECONDS));
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "AP-S05 启动孤儿清理失败", ex);
        }
    }

    @Scheduled(fixedDelayString = "${agent.services.heartbeat-ms:15000}")
    public void heartbeat() {
        for (var entry : List.copyOf(running.entrySet())) {
            Running current = entry.getValue();
            if (current.process.isAlive()) {
                try {
                    updateRuntime(current.tenant, entry.getKey(), current, Instant.now());
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "AP-S05 心跳写入失败: " + entry.getKey(), ex);
                }
            } else {
                current.state = State.FAILED;
                current.exitCode = exitCode(current.process);
                current.lastError = "进程已退出";
                running.remove(entry.getKey(), current);
                try {
                    updateRuntime(current.tenant, entry.getKey(), current, Instant.now());
                    audit(current.tenant, "system", entry.getKey(), "EXIT", "FAILED",
                            Map.of("pid", current.pid, "exitCode", current.exitCode));
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "AP-S05 退出状态写入失败", ex);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        shutdownQuietly();
    }

    private Map<String, Object> startInternal(
            String tenant, String actor, ServiceManifest manifest) {
        if (!manifest.enabled()) throw error(409, "SERVICE_DISABLED", "服务已禁用，不能启动");
        Running existing = running.get(manifest.code());
        if (existing != null && existing.process.isAlive())
            throw error(409, "SERVICE_ALREADY_RUNNING", "服务已在运行，pid=" + existing.pid);
        if (existing != null) {
            existing.state = State.FAILED;
            running.remove(manifest.code(), existing);
            updateRuntime(tenant, manifest.code(), existing, Instant.now());
        }

        Map<String, Object> persisted = runtimeRow(tenant, manifest.code());
        if (persisted != null && RUNNING_STATES.contains(Objects.toString(persisted.get("state"), ""))) {
            if (!instanceId.equals(Objects.toString(persisted.get("owner"), ""))
                    && freshHeartbeat(persisted))
                throw error(409, "SERVICE_OWNED_ELSEWHERE", "服务由其它实例托管，请在该实例操作");
            cleanupStaleRow(tenant, manifest, persisted);
        }
        if (!portFree(manifest.port()))
            throw error(409, "SERVICE_PORT_IN_USE",
                    "端口 " + manifest.port() + " 已被占用；请先确认该端口上的服务");

        try {
            Files.createDirectories(logDir);
            Files.createDirectories(Path.of(manifest.workDir()));
            ProcessBuilder builder = ProcessTree.builder(manifest.command(), Path.of(manifest.workDir()));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(manifest.logFile(logDir).toFile()));
            Process process = ProcessTree.start(builder);
            ensureShutdownHook();
            Running current = new Running(tenant, manifest, process, Instant.now());
            running.put(manifest.code(), current);
            updateRuntime(tenant, manifest.code(), current, Instant.now());
            audit(tenant, actor, manifest.code(), "START", "SUCCESS",
                    Map.of("pid", current.pid, "port", manifest.port(), "phase", "STARTING"));

            Map<String, Object> health = awaitReady(manifest, current);
            current.state = State.READY;
            current.lastError = "";
            updateRuntime(tenant, manifest.code(), current, Instant.now());
            audit(tenant, actor, manifest.code(), "START", "SUCCESS",
                    Map.of("pid", current.pid, "port", manifest.port(),
                            "latencyMs", health.get("latencyMs")));
            var result = view(tenant, manifest, runtimeRow(tenant, manifest.code()));
            result.putAll(health);
            return result;
        } catch (PlatformException ex) {
            markFailed(tenant, actor, manifest, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            markFailed(tenant, actor, manifest, message);
            throw error(500, "SERVICE_START_FAILED", "启动服务失败: " + message);
        }
    }

    private Map<String, Object> awaitReady(ServiceManifest manifest, Running current) {
        long deadline = System.nanoTime() + Duration.ofMillis(manifest.startupTimeoutMs()).toNanos();
        long started = System.nanoTime();
        while (true) {
            if (!current.process.isAlive()) {
                int exit = exitCode(current.process);
                throw error(502, "SERVICE_EXITED",
                        "服务在就绪前退出，exitCode=" + exit + "，日志尾部: " + logTail(manifest));
            }
            Map<String, Object> health = probe(manifest);
            if (Boolean.TRUE.equals(health.get("healthy"))) {
                health.put("latencyMs", Math.max(0, (System.nanoTime() - started) / 1_000_000));
                return health;
            }
            if (System.nanoTime() > deadline) {
                ProcessTree.killTree(current.process);
                throw error(504, "SERVICE_START_TIMEOUT",
                        "等待健康检查超时(" + manifest.startupTimeoutMs() + "ms)，日志尾部: "
                                + logTail(manifest));
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                ProcessTree.killTree(current.process);
                throw error(500, "SERVICE_START_INTERRUPTED", "启动等待被中断");
            }
        }
    }

    private void stopInternal(
            String tenant, String actor, ServiceManifest manifest, Running current, String action) {
        ProcessTree.killTree(current.process);
        boolean exited = true;
        try {
            exited = current.process.waitFor(manifest.stopTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exited = false;
        }
        if (!exited) {
            current.process.destroyForcibly();
            try {
                exited = current.process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        current.exitCode = exitCode(current.process);
        current.state = exited ? State.STOPPED : State.FAILED;
        current.lastError = exited ? "" : "进程在停止超时后仍存活";
        running.remove(manifest.code(), current);
        updateRuntime(tenant, manifest.code(), current, Instant.now());
        audit(tenant, actor, manifest.code(), action, exited ? "SUCCESS" : "FAILED",
                Map.of("pid", current.pid, "exitCode", current.exitCode, "stopped", exited));
        if (!exited)
            throw error(504, "SERVICE_STOP_TIMEOUT", "停止服务超时，进程可能仍存活，请检查端口");
    }

    private void markFailed(
            String tenant, String actor, ServiceManifest manifest, String message) {
        Running current = running.remove(manifest.code());
        if (current == null) {
            current = new Running(tenant, manifest, null, Instant.now());
            current.pid = -1;
        }
        if (current.process != null) ProcessTree.killTree(current.process);
        current.state = State.FAILED;
        current.lastError = truncate(message, 2000);
        current.exitCode = current.process == null ? -1 : exitCode(current.process);
        updateRuntime(tenant, manifest.code(), current, Instant.now());
        audit(tenant, actor, manifest.code(), "START", "FAILED",
                Map.of("error", current.lastError));
    }

    private void cleanupStaleRow(
            String tenant, ServiceManifest manifest, Map<String, Object> row) {
        String code = manifest.code();
        long pid = number(row.get("pid"));
        boolean killed = false;
        if (pid > 0) {
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent() && handle.get().isAlive() && sameStart(row, handle.get())) {
                ProcessTree.killTree(handle.get());
                killed = true;
            }
        }
        var current = new Running(tenant, manifest, null, Instant.now());
        current.pid = pid;
        current.state = State.STOPPED;
        current.exitCode = -1;
        current.lastError = killed ? "已清理上次运行遗留进程" : "已清理陈旧运行态";
        updateRuntime(tenant, code, current, Instant.now());
        audit(tenant, "system", code, killed ? "ORPHAN_CLEANUP" : "STALE_CLEANUP", "SUCCESS",
                Map.of("pid", pid, "killed", killed));
    }

    private void cleanupStale(Instant cutoff) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select * from skill.service_runtime where state in ('STARTING','READY','UNHEALTHY')"
                                + " and (heartbeat_at is null or heartbeat_at < ?)",
                        Timestamp.from(cutoff));
        for (Map<String, Object> row : rows) {
            String tenant = Objects.toString(row.get("tenant_id"), "");
            String code = Objects.toString(row.get("code"), "");
            List<Map<String, Object>> manifests =
                    db.queryForList(
                            "select * from skill.service_manifest where tenant_id=? and code=?",
                            tenant,
                            code);
            if (manifests.isEmpty()) continue;
            cleanupStaleRow(tenant, ServiceManifest.fromRow(manifests.getFirst()), row);
        }
    }

    private Map<String, Object> view(
            String tenant, ServiceManifest manifest, Map<String, Object> persisted) {
        Running current = running.get(manifest.code());
        if (current != null && !current.process.isAlive()) {
            current.state = State.FAILED;
            current.exitCode = exitCode(current.process);
            current.lastError = "进程已退出";
            running.remove(manifest.code(), current);
            current = null;
        }
        var result = status(tenant, manifest, current, persisted);
        result.put("code", manifest.code());
        result.put("name", manifest.name());
        result.put("command", manifest.command());
        result.put("workDir", manifest.workDir());
        result.put("port", manifest.port());
        result.put("healthPath", manifest.healthPath());
        result.put("startupTimeoutMs", manifest.startupTimeoutMs());
        result.put("stopTimeoutMs", manifest.stopTimeoutMs());
        result.put("enabled", manifest.enabled());
        result.put("healthUrl", manifest.healthUri().toString());
        result.put("logFile", manifest.logFile(logDir).getFileName().toString());
        return result;
    }

    private Map<String, Object> status(
            String tenant,
            ServiceManifest manifest,
            Running current,
            Map<String, Object> persisted) {
        var result = new LinkedHashMap<String, Object>();
        if (current != null) {
            result.put("state", current.state.name());
            result.put("pid", current.pid);
            result.put("startedAt", current.startedAt.toString());
            result.put("managed", true);
            result.put("stale", false);
            result.put("lastError", current.lastError);
            return result;
        }
        String state = persisted == null ? "STOPPED" : Objects.toString(persisted.get("state"), "STOPPED");
        boolean stale = RUNNING_STATES.contains(state);
        result.put("state", stale ? "UNKNOWN" : state);
        result.put("rawState", state);
        result.put("pid", persisted == null ? -1 : number(persisted.get("pid")));
        result.put("startedAt", persisted == null || persisted.get("started_at") == null
                ? null
                : ((Timestamp) persisted.get("started_at")).toInstant().toString());
        result.put("managed", false);
        result.put("stale", stale);
        result.put("lastError", persisted == null
                ? ""
                : Objects.toString(persisted.get("last_error"), ""));
        if (stale) result.put("message", "运行态属于其它实例或上次进程，尚未被本实例接管");
        return result;
    }

    private Map<String, Object> probe(ServiceManifest manifest) {
        long start = System.nanoTime();
        var result = new LinkedHashMap<String, Object>();
        result.put("url", manifest.healthUri().toString());
        try {
            HttpRequest request = HttpRequest.newBuilder(manifest.healthUri())
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            result.put("healthy", response.statusCode() >= 200 && response.statusCode() < 300);
            result.put("httpStatus", response.statusCode());
            result.put("latencyMs", Math.max(0, (System.nanoTime() - start) / 1_000_000));
            result.put("message", "健康端点 HTTP " + response.statusCode());
            result.put("bodyPreview", truncate(response.body(), 240));
        } catch (Exception ex) {
            result.put("healthy", false);
            result.put("httpStatus", null);
            result.put("latencyMs", Math.max(0, (System.nanoTime() - start) / 1_000_000));
            result.put("message", "健康检查不可达: " + ex.getClass().getSimpleName());
        }
        return result;
    }

    private ServiceManifest load(String tenant, String code) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select * from skill.service_manifest where tenant_id=? and code=?",
                        tenant,
                        code);
        if (rows.isEmpty()) throw notFound(code);
        return ServiceManifest.fromRow(rows.getFirst());
    }

    private List<Map<String, Object>> manifestRows(String tenant) {
        return db.queryForList(
                "select * from skill.service_manifest where tenant_id=? order by code", tenant);
    }

    private Map<String, Map<String, Object>> runtimeRows(String tenant) {
        var result = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> row :
                db.queryForList("select * from skill.service_runtime where tenant_id=?", tenant))
            result.put(Objects.toString(row.get("code"), ""), row);
        return result;
    }

    private Map<String, Object> runtimeRow(String tenant, String code) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select * from skill.service_runtime where tenant_id=? and code=?",
                        tenant,
                        code);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void updateRuntime(
            String tenant, String code, Running current, Instant heartbeat) {
        db.update(
                "insert into skill.service_runtime(tenant_id,code,state,pid,owner,started_at,"
                        + "heartbeat_at,last_error,exit_code,updated_at) values(?,?,?,?,?,?,?,?,?,now())"
                        + " on conflict(tenant_id,code) do update set state=excluded.state,"
                        + "pid=excluded.pid,owner=excluded.owner,started_at=excluded.started_at,"
                        + "heartbeat_at=excluded.heartbeat_at,last_error=excluded.last_error,"
                        + "exit_code=excluded.exit_code,updated_at=now()",
                tenant,
                code,
                current.state.name(),
                current.pid > 0 ? current.pid : null,
                current.pid > 0 ? instanceId : null,
                current.startedAt == null ? null : Timestamp.from(current.startedAt),
                Timestamp.from(heartbeat),
                truncate(current.lastError, 2000),
                current.exitCode >= 0 ? current.exitCode : null);
    }

    private void audit(
            String tenant, String actor, String code, String action, String result,
            Map<String, Object> detail) {
        db.update(
                "insert into skill.service_audit(tenant_id,actor,service_code,action,result,detail)"
                        + " values(?,?,?,?,?,?::jsonb)",
                tenant,
                actor,
                code,
                action,
                result,
                Json.write(detail));
    }

    private void ensureShutdownHook() {
        if (hookInstalled) return;
        synchronized (this) {
            if (hookInstalled) return;
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(this::shutdownQuietly, "service-lifecycle-shutdown"));
            hookInstalled = true;
        }
    }

    private void shutdownQuietly() {
        for (var entry : List.copyOf(running.entrySet())) {
            Running current = entry.getValue();
            try {
                ProcessTree.killTree(current.process);
                current.state = State.STOPPED;
                current.exitCode = exitCode(current.process);
                current.lastError = "平台退出时停止";
                updateRuntime(current.tenant, entry.getKey(), current, Instant.now());
                audit(current.tenant, "system", entry.getKey(), "SHUTDOWN", "SUCCESS",
                        Map.of("pid", current.pid));
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "AP-S05 退出清理失败: " + entry.getKey(), ex);
            } finally {
                running.remove(entry.getKey(), current);
            }
        }
    }

    private boolean alive(String code) {
        Running current = running.get(code);
        return current != null && current.process.isAlive();
    }

    private boolean freshHeartbeat(Map<String, Object> row) {
        Object raw = row.get("heartbeat_at");
        if (!(raw instanceof Timestamp timestamp)) return false;
        return timestamp.toInstant().isAfter(Instant.now().minusSeconds(HEARTBEAT_STALE_SECONDS));
    }

    private boolean sameStart(Map<String, Object> row, ProcessHandle handle) {
        Object raw = row.get("started_at");
        if (!(raw instanceof Timestamp timestamp)) return false;
        Instant expected = timestamp.toInstant();
        Instant actual = handle.info().startInstant().orElse(null);
        if (actual == null) return false;
        return Math.abs(Duration.between(expected, actual).toMillis()) < 10_000;
    }

    private boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private String logTail(ServiceManifest manifest) {
        Path file = manifest.logFile(logDir);
        try {
            if (!Files.isRegularFile(file)) return "(无日志)";
            long size = Files.size(file);
            byte[] bytes = readTail(file, Math.min(size, 2048));
            return truncate(new String(bytes, StandardCharsets.UTF_8).strip(), 2000);
        } catch (IOException ex) {
            return "(日志不可读)";
        }
    }

    private static byte[] readTail(Path file, long length) throws IOException {
        if (length <= 0) return new byte[0];
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = Math.max(0, size - length);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(length, size - start));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // 读到 EOF 即停止；日志可能在读取期间增长，只取开始时的大小。
            }
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private void requireConfirm(String code, boolean confirmed) {
        if (!confirmed)
            throw error(409, "SERVICE_CONFIRM_REQUIRED",
                    "该操作需要人工确认：请提交 confirm=" + code);
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    private String actor() {
        return Actor.current().principalId();
    }

    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    private Object lock(String code) {
        return locks.computeIfAbsent(code, ignored -> new Object());
    }

    private static int exitCode(Process process) {
        if (process == null) return -1;
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException ex) {
            return -1;
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String host() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "localhost";
        }
    }

    private static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    private static PlatformException notFound(String code) {
        return error(404, "SERVICE_NOT_FOUND", "服务不存在: " + code);
    }

    private enum State { STARTING, READY, UNHEALTHY, STOPPED, FAILED }

    private static final class Running {
        final String tenant;
        final ServiceManifest manifest;
        final Process process;
        volatile long pid;
        final Instant startedAt;
        volatile State state = State.STARTING;
        volatile String lastError = "";
        volatile int exitCode = -1;

        Running(String tenant, ServiceManifest manifest, Process process, Instant startedAt) {
            this.tenant = tenant;
            this.manifest = manifest;
            this.process = process;
            this.pid = process == null ? -1 : process.pid();
            this.startedAt = startedAt;
        }
    }
}
