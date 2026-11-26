package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.InitialChatRules;
import cn.agentplatform.engine.domain.InputDeclarations;
import cn.agentplatform.engine.domain.RunAdmissionRejected;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatSessions {
    private final TraceStore store;
    private final RuntimeClients clients;
    private final RunService runs;
    // H1-2：会话事件日志写入口（复用 TraceStore 事务边界，轮次预留事务内同事务落 turn/start+user/message）。
    private final cn.agentplatform.engine.harness.session.SessionStore turnEvents;
    // H1-9：活动 SSE 观察流（每连接一个泵；closeSessionStreams/@PreDestroy 收口）。
    private final Set<StreamPump> streams = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** :ping 心跳间隔（接口稿 §7：15s）；独立部署可调，测试经反射改短。 */
    @org.springframework.beans.factory.annotation.Value("${agent.session.sse.heartbeat-millis:15000}")
    long heartbeatMillis = 15000;
    /**
     * R-16（台账 §7.6b）：会话 SSE 背压配置——单连接未读积压上限。有界输出口径：PG（事实源）
     * 即缓冲，进程内不设无界队列（每批 ≤200 事件）；未读积压超限判慢消费者<b>主动断开并记
     * 审计行</b>（AUDIT SESSION_SSE_SLOW_CONSUMER_DISCONNECT），客户端以 Last-Event-ID 重连
     * 续读（seq 幂等去重，不重不漏）。默认 2000（T2-11 任务 SSE 同构先例原值），配置键
     * {@code agent.sse.max-buffered-events}（application.yml 默认值在案）。
     */
    @org.springframework.beans.factory.annotation.Value("${agent.sse.max-buffered-events:2000}")
    int maxBufferedEvents = 2000;
    /**
     * R-16：单主体（tenant+principal）会话 SSE 连接上限——已达上限时拒绝新连接（429
     * {@code SESSION_SSE_CONNECTION_LIMIT}）并记审计行，防止单主体无界占用观察流（连接数
     * 是独立于单连接缓冲的第二道限流面）。默认 50，配置键
     * {@code agent.sse.max-connections-per-principal}（application.yml 默认值在案）。
     */
    @org.springframework.beans.factory.annotation.Value("${agent.sse.max-connections-per-principal:50}")
    int maxConnectionsPerPrincipal = 50;

    public ChatSessions(TraceStore store, RuntimeClients clients, RunService runs) {
        this.store = store;
        this.clients = clients;
        this.runs = runs;
        this.turnEvents = new cn.agentplatform.engine.harness.session.SessionStore(store);
    }

    public Map<String, Object> create(String code, Actor actor) {
        return create(code, null, actor);
    }

    /**
     * INT-06：会话创建时可提议 configReleaseId，引用由引擎向 app 核验后固定；
     * 未提议时保持旧行为（配置列保持 null，轮次不绑定配置版本）。
     */
    public Map<String, Object> create(String code, JsonNode body, Actor actor) {
        JsonNode snapshot = clients.publishedForRuntime(code, actor);
        if (!snapshot.path("type").asText().equals("CHAT"))
            throw new PlatformException(409, "CHAT_REQUIRED", "只有已发布对话应用可创建会话");
        maxTurns(snapshot);
        RunService.ConfigReference config =
                runs == null ? null : runs.resolveConfig(code, body, actor);
        String id = "s_" + UUID.randomUUID().toString().replace("-", "");
        if (config == null) {
            store.db()
                    .update(
                            "insert into"
                                + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                                + " values(?,?,?,?,?::jsonb)",
                            id,
                            actor.tenantId(),
                            actor.principalId(),
                            code,
                            Json.write(snapshot));
        } else {
            store.db()
                    .update(
                            "insert into"
                                + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,"
                                + "config_release_id,config_digest,environment_binding_revs,config_environment)"
                                + " values(?,?,?,?,?::jsonb,?,?,?::jsonb,?)",
                            id,
                            actor.tenantId(),
                            actor.principalId(),
                            code,
                            Json.write(snapshot),
                            config.releaseId(),
                            config.digest(),
                            Json.write(config.environmentBindingRevs()),
                            config.environment());
        }
        return get(code, id, actor);
    }

    /**
     * H1-9（接口稿 §5.1）：只读快照——保留既有元数据与 turns，新增 eventWatermark 与 messages 投影。
     * 消息与水位取同一已提交事件前缀，客户端以 {@code seq > eventWatermark} 续接事件增量。
     * messages 为 deriveMessages 全量投影（system/user/assistant 消息类事件）——角色字段白名单
     * 投影（USER 不读 system 等）按台账切分随 F3-6 交付，本卡如实输出全量投影。
     */
    public Map<String, Object> get(String code, String id, Actor actor) {
        var session = owned(code, id, actor, false);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", id);
        view.put("appCode", code);
        view.put("versionNo", TraceStore.json(session.get("snapshot")).path("versionNo"));
        view.put("nextTurn", session.get("next_turn"));
        view.put("maxTurns", maxTurns(TraceStore.json(session.get("snapshot"))));
        view.put(
                "turns",
                store.db()
                        .queryForList(
                                "select turn_no,request_id,run_public_id,created_at from"
                                        + " engine.chat_turn where session_id=? order by turn_no",
                                id));
        // deriveMessages 端点内无状态、全量 fold（进程内增量缓存属调用方优化，契约见
        // contracts/session-events.md §2 Surface 投影）；content 为事件 data 原文。
        var projection = turnEvents.deriveMessages(id, null);
        var messages = new ArrayList<Map<String, Object>>();
        for (var message : projection.messages()) {
            if (clients != null && clients.iamEnabled() && "system".equals(message.role())) continue;
            messages.add(
                    Map.of(
                            "seq", message.sourceSeq(),
                            "role", message.role(),
                            "content", message.content()));
        }
        view.put("messages", messages);
        view.put("eventWatermark", projection.lastSeq());
        if (session.get("config_release_id") != null)
            view.put("configReleaseId", session.get("config_release_id"));
        return view;
    }

    public Map<String, Object> turn(String code, String id, JsonNode req, Actor actor) {
        String requestId = req.path("requestId").asText();
        if (requestId.isBlank() || requestId.length() > 48 || !req.path("inputs").isObject())
            throw new PlatformException(400, "TURN_REQUEST_INVALID", "会话轮次必须提供requestId与inputs");
        var pinnedSession = owned(code, id, actor, false);
        if (TraceStore.json(pinnedSession.get("snapshot")).has("evaluationConversation"))
            throw new PlatformException(403,"EVALUATION_SESSION_ROUTE_REQUIRED","候选评测会话只能经受控EVALUATION入口续接");
        // INT-06：轮次只能沿用会话创建时固定的配置版本；客户端在轮次里换版本一律拒绝。
        RunService.ConfigReference pinnedConfig = pinnedConfig(pinnedSession, req);
        boolean portal = clients != null && clients.iamEnabled();
        if (portal) InitialChatRules.requireSources(TraceStore.json(pinnedSession.get("snapshot")), req.path("inputs"));
        boolean known =
                !store.db()
                        .queryForList(
                                "select turn_no from engine.chat_turn where session_id=? and"
                                        + " request_id=?",
                                id,
                                requestId)
                        .isEmpty();
        // Keep the published version pinned, but never let an old session bypass current app
        // disablement.
        // Existing reservations are accepted work and must remain idempotently recoverable.
        if (!known) {
            requireTurnLimit(pinnedSession);
            ChatHistory.checkScope(store, id, code, actor);
            clients.publishedForRuntime(code, actor);
        }
        RunScheduler.Admission ticket = null;
        if (!known) {
            InputDeclarations.validate(
                    TraceStore.json(pinnedSession.get("snapshot")), req.path("inputs"));
            try {
                ticket = runs.reserveSessionTurn();
            } catch (RunAdmissionRejected rejected) {
                // Another request may have accepted this exact turn since the first lookup.
                // A durable reservation is accepted work; its normal locked branch below
                // verifies input equality and can reconstruct a missing run without re-admission.
                if (store.db()
                        .queryForList(
                                "select turn_no from engine.chat_turn where session_id=? and"
                                        + " request_id=?",
                                id,
                                requestId)
                        .isEmpty()) throw rejected;
            }
        }
        try {
            java.util.function.Supplier<Map<String, Object>> reserve =
                    () ->
                            store.atomic(
                                    () -> {
                                        var session = owned(code, id, actor, true);
                                        var existing =
                                                store.db()
                                                        .queryForList(
                                                                "select * from engine.chat_turn"
                                                                        + " where session_id=? and"
                                                                        + " request_id=?",
                                                                id,
                                                                requestId);
                                        if (!existing.isEmpty()) {
                                            if (!TraceStore.json(existing.getFirst().get("inputs"))
                                                    .equals(req.path("inputs")))
                                                throw new PlatformException(
                                                        409, "REQUEST_CONFLICT", "轮次请求号已绑定其他输入");
                                            return existing.getFirst();
                                        }
                                        // 在既有会话行锁内复核固定版本的上限；已受理请求走上方重放分支，不再占用轮次。
                                        requireTurnLimit(session);
                                        var pending =
                                                store.db()
                                                        .queryForList(
                                                                "select t.run_public_id,r.status"
                                                                    + " from engine.chat_turn t"
                                                                    + " left join engine.run r on"
                                                                    + " r.public_id=t.run_public_id"
                                                                    + " where t.session_id=? order"
                                                                    + " by t.turn_no desc limit 1",
                                                                id);
                                        if (!pending.isEmpty()
                                                && (pending.getFirst().get("status") == null
                                                        || !Set.of(
                                                                        "SUCCEEDED",
                                                                        "FAILED",
                                                                        "CANCELLED")
                                                                .contains(
                                                                        pending.getFirst()
                                                                                .get("status"))))
                                            throw new PlatformException(
                                                    409, "CHAT_TURN_ACTIVE", "上一轮仍在执行或恢复");
                                        // Validate locally under the session lock.
                                        // Accepted requestIds retain their original inputs.
                                        // Schema loading cannot perform HTTP here.
                                        InputDeclarations.validate(
                                                TraceStore.json(session.get("snapshot")),
                                                req.path("inputs"));
                                        int turn = ((Number) session.get("next_turn")).intValue();
                                        store.db()
                                                .update(
                                                        "insert into"
                                                            + " engine.chat_turn(session_id,turn_no,request_id,inputs)"
                                                            + " values(?,?,?,?::jsonb)",
                                                        id,
                                                        turn,
                                                        requestId,
                                                        Json.write(req.path("inputs")));
                                        store.db()
                                                .update(
                                                        "update engine.chat_session set"
                                                                + " next_turn=next_turn+1 where"
                                                                + " public_id=?",
                                                        id);
                                        // H1-2（主方案 §5.1）：chat_turn 创建与 turn/start+user/message
                                        // 同事务——重放/恢复以事务为界，日志重建必回到原轮次。
                                        // turnEvents 复用本 reserve 的 TraceStore 事务（SessionStore(TraceStore)），
                                        // 此处已在 store.atomic 内，append 直接加入当前事务。
                                        turnEvents.append(
                                                id, null,
                                                cn.agentplatform.engine.harness.session.SessionEvents.TURN_START,
                                                Json.tree(Map.of("turn", turn)), null);
                                        turnEvents.append(
                                                id, null,
                                                cn.agentplatform.engine.harness.session.SessionEvents.USER_MESSAGE,
                                                Json.tree(Map.of("content", req.path("inputs"))), null);
                                        return store.db()
                                                .queryForMap(
                                                        "select * from engine.chat_turn where"
                                                                + " session_id=? and turn_no=?",
                                                        id,
                                                        turn);
                                    });
            // The first durable reservation is the admission point, before consuming next_turn.
            // Subsequent run preparation may perform HTTP outside this short transaction. Once
            // accepted, drain cannot reject the same ticket's completion and orphan its turn.
            var reservation = ticket == null ? reserve.get() : ticket.persist(reserve);
            if (reservation.get("run_public_id") != null)
                return runs.status(reservation.get("run_public_id").toString(), actor);
            var session = owned(code, id, actor, false);
            var source =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            TraceStore.json(session.get("snapshot")).deepCopy();
            source.set(
                    "chatSession",
                    Json.tree(Map.of("sessionId", id, "turnNo", reservation.get("turn_no"))));
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) req.deepCopy();
            input.remove("sessionId");
            input.remove("configReleaseId");
            input.put("requestId", "session:" + id + ":" + requestId);
            if (portal) {
                var resource = cn.agentplatform.common.authority.PortalRuntimeRoutes.sessionResource(code, id);
                var proof = cn.agentplatform.common.authority.AuthorityContext.requireInitialFor(actor, resource);
                source.set("portalHistory", store.conversationHistory(
                        id, ((Number) reservation.get("turn_no")).intValue(), source, proof));
            }
            var result =
                    pinnedConfig == null
                            ? runs.createReservedSessionTurn(code, input, source, actor, ticket)
                            : runs.createReservedSessionTurn(
                                    code, input, source, actor, ticket, pinnedConfig);
            store.db()
                    .update(
                            "update engine.chat_turn set run_public_id=? where session_id=? and"
                                    + " turn_no=? and run_public_id is null",
                            result.get("runId"),
                            id,
                            reservation.get("turn_no"));
            return result;
        } finally {
            if (ticket != null) ticket.close();
        }
    }

    /**
     * S2-1（接口稿 §5.1 第一段）：本人会话列表——webapp 会话列表数据源。
     *
     * <p>可见性：仅 tenant+principal=当前用户的会话，且排除 origin='TASK_INTERNAL'
     *（内部根会话，接口稿：不出现在本人会话列表）；本端点只做本人过滤——mine=true 首期固定，
     * 缺省或其他值一律 400，跨用户查询不开放。重命名/删除不在本期契约。
     *
     * <p>lastActiveAt=最近 chat_turn.created_at（无轮次→chat_session.created_at），按其倒序；
     * before 为排他游标（返回 lastActiveAt 早于该值的行）；limit 默认 50、显式 1..200。
     * nextBefore 仅在还有更多行时返回（=本页最后一条的 lastActiveAt），末页省略该键。
     * state 为"活跃/终态轮次摘要"：最近轮次的 run 到达终态（SUCCEEDED/FAILED/CANCELLED）时
     * 返回该终态，否则（无轮次、run 缺失或仍在执行/恢复）返回 ACTIVE。
     */
    public Map<String, Object> list(
            String code, String mine, String limit, String before, Actor actor) {
        if (!"true".equals(mine))
            throw new PlatformException(
                    400, "SESSION_LIST_MINE_REQUIRED", "会话列表仅支持 mine=true（首期固定，跨用户查询不开放）");
        int size = 50;
        if (limit != null && !limit.isBlank()) {
            if (!limit.matches("[0-9]{1,4}"))
                throw new PlatformException(400, "SESSION_LIST_LIMIT_INVALID", "limit 必须为 1 至 200 的整数");
            size = Integer.parseInt(limit);
            if (size < 1 || size > 200)
                throw new PlatformException(400, "SESSION_LIST_LIMIT_INVALID", "limit 必须为 1 至 200 的整数");
        }
        java.time.Instant cursor = null;
        if (before != null && !before.isBlank()) {
            try {
                cursor = java.time.Instant.parse(before);
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new PlatformException(400, "SESSION_LIST_CURSOR_INVALID", "before 必须为 ISO-8601 时间");
            }
        }
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.principalId(), code));
        StringBuilder sql =
                new StringBuilder(
                        "select s.public_id,"
                            + " coalesce(max(t.created_at), s.created_at) as last_active_at,"
                            + " s.created_at as created_at"
                            + " from engine.chat_session s"
                            + " left join engine.chat_turn t on t.session_id=s.public_id"
                            + " where s.tenant_id=? and s.principal_id=? and s.app_code=?"
                            + " and s.origin<>'TASK_INTERNAL'"
                            + " group by s.public_id");
        if (cursor != null) {
            sql.append(" having coalesce(max(t.created_at), s.created_at)<?");
            args.add(java.sql.Timestamp.from(cursor));
        }
        // 同 lastActiveAt 时按会话号稳定排序；多取一行探测"还有更多"。
        sql.append(" order by last_active_at desc, s.public_id desc limit ?");
        args.add(size + 1);
        var rows = store.db().queryForList(sql.toString(), args.toArray());
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        var ids = rows.stream().map(row -> row.get("public_id").toString()).toList();
        var titles = firstTurnInputs(ids);
        var lastStatus = lastTurnRunStatus(ids);
        var sessions = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var item = new LinkedHashMap<String, Object>();
            item.put("sessionId", row.get("public_id").toString());
            item.put("appCode", code);
            item.put("title", title(TraceStore.json(titles.get(row.get("public_id").toString()))));
            item.put("lastActiveAt", text(row.get("last_active_at")));
            item.put("createdAt", text(row.get("created_at")));
            String status = lastStatus.get(row.get("public_id").toString());
            item.put(
                    "state",
                    status != null && Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)
                            ? status
                            : "ACTIVE");
            sessions.add(item);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("sessions", sessions);
        if (more) data.put("nextBefore", text(rows.getLast().get("last_active_at")));
        return data;
    }

    /**
     * title 稳定规则（接口稿 §5.1：首条用户消息截断 ≤48 字）：取 turn_no 最小的 chat_turn.inputs——
     * 文本标量直接采用；对象时优先取其中 message 字段（文本时），其次 text 字段（文本时），
     * 两者都不可用时回退为该对象的整体规范 JSON 文本；其余类型取其 JSON 文本。
     * 无轮次或 inputs 为空对象→空串。截断按 Java 字符（UTF-16 单元）计，超长截到 48。
     */
    static String title(JsonNode inputs) {
        if (inputs == null || inputs.isMissingNode() || inputs.isNull()
                || (inputs.isObject() && inputs.isEmpty()))
            return "";
        String text;
        if (inputs.isTextual()) text = inputs.asText();
        else if (inputs.isObject()) {
            JsonNode message = inputs.get("message");
            JsonNode chosen =
                    message != null && message.isTextual()
                            ? message
                            : inputs.get("text");
            text = chosen != null && chosen.isTextual() ? chosen.asText() : Json.canonical(inputs);
        } else text = Json.write(inputs);
        return text.length() <= 48 ? text : text.substring(0, 48);
    }

    /** 页内首条轮次（turn_no 最小）的 inputs，键为会话号；页有界（≤200），一次批查。 */
    private Map<String, Object> firstTurnInputs(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var rows =
                store.db()
                        .queryForList(
                                "select distinct on (t.session_id) t.session_id,t.inputs"
                                    + " from engine.chat_turn t where t.session_id in ("
                                        + placeholders
                                        + ") order by t.session_id,t.turn_no",
                                ids.toArray());
        var result = new LinkedHashMap<String, Object>();
        for (var row : rows) result.put(row.get("session_id").toString(), row.get("inputs"));
        return result;
    }

    /** 页内最近轮次（turn_no 最大）关联 run 的状态，键为会话号；无轮次或 run 缺失时无该键。 */
    private Map<String, String> lastTurnRunStatus(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var rows =
                store.db()
                        .queryForList(
                                "select distinct on (t.session_id) t.session_id,r.status"
                                    + " from engine.chat_turn t"
                                    + " left join engine.run r on r.public_id=t.run_public_id"
                                    + " where t.session_id in ("
                                        + placeholders
                                        + ") order by t.session_id,t.turn_no desc",
                                ids.toArray());
        var result = new LinkedHashMap<String, String>();
        for (var row : rows) {
            if (row.get("status") != null)
                result.put(row.get("session_id").toString(), row.get("status").toString());
        }
        return result;
    }

    /** timestamptz 读回可能是 Timestamp/OffsetDateTime/Instant；统一为 ISO-8601 UTC 文本。 */
    private static String text(Object value) {
        java.time.Instant instant;
        if (value instanceof java.time.Instant direct) instant = direct;
        else if (value instanceof java.sql.Timestamp stamp) instant = stamp.toInstant();
        else if (value instanceof java.time.OffsetDateTime dateTime) instant = dateTime.toInstant();
        else instant = java.time.Instant.parse(value.toString());
        return instant.toString();
    }

    /**
     * H1-9（接口稿 §5.1）：事件增量——{@code afterSeq} 排他（{@code seq > afterSeq}），只返回
     * 连续合法前缀（SessionStore.read 契约①已保证，撕裂尾不返回）；limit 默认 200、上限 1000；
     * surfaceOnly 默认 true=普通视图，显式 false=诊断视图仅 OPERATOR/ADMIN（无权 403 并记一条
     * 拒绝审计）。普通视图类型级白名单按接口稿 §5.1 字段白名单（USER 行）实现为诊断类排除集
     * {@link #SURFACE_HIDDEN}——字段级展示投影（tool 摘要、compaction 摘要正文裁剪）随 F3-6。
     *
     * <p>游标语义（评审三轮 07）：{@code nextAfterSeq = 本页最后扫描到的原始 seq}（含被普通视图
     * 过滤跳过的事件，<b>不 +1</b>）；整页全被过滤时 events 为空但游标照常推进；truncated=
     * 水位之外还有未扫描事件。客户端恢复顺序=先 GET 快照，再以 eventWatermark 作 afterSeq 续接。
     */
    public Map<String, Object> events(
            String code, String id, String afterSeq, String limit, String surfaceOnly, Actor actor) {
        owned(code, id, actor, false);
        boolean surface = true;
        if (surfaceOnly != null && !surfaceOnly.isBlank()) {
            if (!surfaceOnly.equals("true") && !surfaceOnly.equals("false"))
                throw new PlatformException(
                        400, "SESSION_EVENTS_SURFACE_INVALID", "surfaceOnly 仅支持 true 或 false");
            surface = surfaceOnly.equals("true");
        }
        if (!surface) requireDiagnosticRole(code, id, actor);
        long after = parseSeq(afterSeq);
        int size = 200;
        if (limit != null && !limit.isBlank()) {
            if (!limit.matches("[0-9]{1,5}"))
                throw new PlatformException(
                        400, "SESSION_EVENTS_LIMIT_INVALID", "limit 必须为 1 至 1000 的整数");
            size = Integer.parseInt(limit);
            if (size < 1 || size > 1000)
                throw new PlatformException(
                        400, "SESSION_EVENTS_LIMIT_INVALID", "limit 必须为 1 至 1000 的整数");
        }
        var scanned = turnEvents.read(id, after, size);
        long nextAfterSeq = after;
        var rows = new ArrayList<Map<String, Object>>();
        for (var event : scanned) {
            nextAfterSeq = event.seq();
            if (surface && SURFACE_HIDDEN.contains(event.type())) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("seq", event.seq());
            item.put("type", event.type());
            item.put("occurredAt", text(event.occurredAt()));
            item.put("data", event.data());
            if (event.surfaceOp() != null) item.put("surfaceOp", event.surfaceOp());
            rows.add(item);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("sessionId", id);
        data.put("events", rows);
        data.put("nextAfterSeq", nextAfterSeq);
        data.put("truncated", turnEvents.watermark(id) > nextAfterSeq);
        return data;
    }

    /**
     * H1-9（接口稿 §5.1）：会话控制——CANCEL_TURN/STEER/SET_PLAN_MODE/COMPACT。requestId 必填；
     * 控制沿用会话归属校验（与快照/轮次同一 owned 口径，OPERATOR 代运维随工作区授权模型另卡）。
     * 响应 {@code {accepted, pending?}}：STEER/SET_PLAN_MODE 在活动轮次内 pending=true
     * （下一 accepted pre-step 生效），轮次外立即提交；COMPACT 本卡占位（压缩引擎随 T2-9 交付，
     * 只回受理+pending，不写压缩事件）。
     */
    public Map<String, Object> control(String code, String id, JsonNode req, Actor actor) {
        String requestId = req == null ? null : req.path("requestId").asText(null);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            throw new PlatformException(
                    400, "SESSION_CONTROL_REQUEST_INVALID", "控制请求必须提供 requestId（≤128 字）");
        String action = req.path("action").asText(null);
        if (action == null || action.isBlank())
            throw new PlatformException(400, "SESSION_CONTROL_ACTION_INVALID", "控制动作必填");
        owned(code, id, actor, false);
        return switch (action) {
            case "CANCEL_TURN" -> cancelTurn(id);
            case "STEER" -> steer(id, req.path("payload"));
            case "SET_PLAN_MODE" -> setPlanMode(id, req.path("payload"));
            case "COMPACT" -> Map.of("accepted", true, "pending", true);
            default ->
                    throw new PlatformException(
                            400, "SESSION_CONTROL_ACTION_INVALID", "未知控制动作 " + action);
        };
    }

    /**
     * H1-9（接口稿 §7）：会话事件 SSE——text/event-stream，帧 {@code id=<seq> event=<type>
     * data=<json>}。Last-Event-ID（头优先、缺省取同名 query 参数）排他续读（{@code seq > id}，
     * 与 §5.1 游标同一语义）；普通视图投影（{@link #SURFACE_HIDDEN}）；15s 无事件补 {@code :ping}
     * 注释帧。生命周期（接口稿 §7）：SSE 只是观察通道——客户端断开（send 失败）只停泵，
     * <b>不取消 turn</b>（取消必须显式 control）。有界输出（T2-11 任务 SSE 同构）：PG（事实源）
     * 即缓冲，进程内不设无界队列——每批 ≤200 事件、未读积压超 {@link #maxBufferedEvents}
     * 判慢消费者主动断开并记审计行（客户端以 Last-Event-ID 重连续读）。
     *
     * <p>R-16 连接面限流：单主体（tenant+principal）活动会话 SSE 连接达
     * {@link #maxConnectionsPerPrincipal} 时拒绝新连接（429 SESSION_SSE_CONNECTION_LIMIT）
     * 并记审计行——限流在归属校验之后执行，超限不占用泵线程；既有连接不受影响。
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter eventStream(
            String code, String id, String headerLastEventId, String paramLastEventId, Actor actor) {
        owned(code, id, actor, false);
        int connectionLimit = Math.max(1, maxConnectionsPerPrincipal);
        int active = 0;
        for (var pump : streams)
            if (pump.tenantId.equals(actor.tenantId()) && pump.principalId.equals(actor.principalId()))
                active++;
        if (active >= connectionLimit) {
            org.slf4j.LoggerFactory.getLogger(ChatSessions.class)
                    .warn(
                            "AUDIT SESSION_SSE_CONNECTION_LIMIT tenant={} principal={} appCode={} sessionId={} active={} limit={}",
                            actor.tenantId(),
                            actor.principalId(),
                            code,
                            id,
                            active,
                            connectionLimit);
            throw new PlatformException(
                    429, "SESSION_SSE_CONNECTION_LIMIT",
                    "会话事件流连接数已达单主体上限（" + connectionLimit + "），请关闭空闲连接后重试");
        }
        long after = parseStreamCursor(headerLastEventId, paramLastEventId);
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        var guard = clients != null && clients.iamEnabled() ? clients.portalSessionStream(code, id, actor) : null;
        var pump = new StreamPump(code, id, after, emitter, actor.tenantId(), actor.principalId(), guard);
        streams.add(pump);
        emitter.onCompletion(pump::close);
        emitter.onTimeout(pump::close);
        emitter.onError(failure -> pump.close());
        var thread = new Thread(pump, "session-events-stream-" + id);
        thread.setDaemon(true);
        thread.start();
        return emitter;
    }

    /** 关闭指定会话的全部事件观察流（会话收口/测试收口用）。返回关闭的流数量。 */
    public int closeSessionStreams(String sessionId) {
        int closed = 0;
        for (var pump : streams)
            if (pump.sessionId.equals(sessionId)) {
                pump.close();
                closed++;
            }
        return closed;
    }

    @jakarta.annotation.PreDestroy
    void shutdownStreams() {
        for (var pump : new ArrayList<>(streams)) pump.close();
    }

    /**
     * 普通视图排除集（接口稿 §5.1 字段白名单 USER 行"不可读"清单 + 不入模型历史的尝试记录）：
     * system/message、request/header、request/context、assistant/attempt。字段级裁剪随 F3-6。
     */
    private static final Set<String> SURFACE_HIDDEN =
            Set.of(
                    cn.agentplatform.engine.harness.session.SessionEvents.SYSTEM_MESSAGE,
                    cn.agentplatform.engine.harness.session.SessionEvents.REQUEST_HEADER,
                    cn.agentplatform.engine.harness.session.SessionEvents.REQUEST_CONTEXT,
                    cn.agentplatform.engine.harness.session.SessionEvents.ASSISTANT_ATTEMPT);

    /** 诊断视图角色门：仅 OPERATOR/ADMIN（R-17：角色判定引 §3.1 授权目录常量）；无权 403 并记一条拒绝审计（拒绝原因入日志）。 */
    private void requireDiagnosticRole(String code, String id, Actor actor) {
        if (Roles.hasAny(actor, Roles.OPERATOR, Roles.ADMIN)) return;
        // 引擎无独立审计表（审计归口 monitor，§5.7 卡接线）；先落结构化审计日志行保证可追溯。
        org.slf4j.LoggerFactory.getLogger(ChatSessions.class)
                .warn(
                        "AUDIT SESSION_DIAGNOSTIC_VIEW_FORBIDDEN tenant={} principal={} appCode={} sessionId={} reason=surfaceOnly=false requires OPERATOR/ADMIN",
                        actor.tenantId(),
                        actor.principalId(),
                        code,
                        id);
        throw new PlatformException(403, "SESSION_DIAGNOSTIC_VIEW_FORBIDDEN", "诊断视图仅 OPERATOR/ADMIN 可用");
    }

    private static long parseSeq(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        if (!raw.matches("[0-9]{1,10}"))
            throw new PlatformException(400, "SESSION_EVENTS_CURSOR_INVALID", "afterSeq 必须为非负整数");
        return Long.parseLong(raw);
    }

    private static long parseStreamCursor(String header, String param) {
        String selected = header != null && !header.isBlank() ? header : param;
        if (selected == null || selected.isBlank()) return 0;
        if (!selected.matches("[0-9]{1,19}"))
            throw new PlatformException(400, "EVENT_CURSOR", "Last-Event-ID 必须为非负整数");
        return Long.parseLong(selected);
    }

    /** 最近轮次及其关联 run 状态；无轮次返回 null。 */
    private record LatestTurn(long turnNo, String runPublicId, String runStatus) {}

    /** 活动口径与轮次预留一致（ChatSessions.turn 的 CHAT_TURN_ACTIVE）：run 缺失或未达终态=活跃。 */
    private static boolean turnActive(LatestTurn turn) {
        return turn != null
                && (turn.runStatus() == null
                        || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(turn.runStatus()));
    }

    private LatestTurn latestTurn(String id) {
        var rows =
                store.db()
                        .queryForList(
                                "select t.turn_no,t.run_public_id,r.status from engine.chat_turn t"
                                        + " left join engine.run r on r.public_id=t.run_public_id"
                                        + " where t.session_id=? order by t.turn_no desc limit 1",
                                id);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return new LatestTurn(
                ((Number) row.get("turn_no")).longValue(),
                row.get("run_public_id") == null ? null : row.get("run_public_id").toString(),
                row.get("status") == null ? null : row.get("status").toString());
    }

    /**
     * CANCEL_TURN：置活动 run 取消（照既有取消机制 RunService.cancel 的核心 SQL——终态守卫由
     * WHERE 承担，与进行中轮次并发的取消只有一方生效），同事务补 turn/end{reason=aborted}。
     * 无活动轮次（含并发下已终态）409 SESSION_TURN_ACTIVE。
     */
    private Map<String, Object> cancelTurn(String id) {
        var latest = latestTurn(id);
        if (!turnActive(latest) || latest.runPublicId() == null)
            throw new PlatformException(409, "SESSION_TURN_ACTIVE", "无活动轮次可取消");
        boolean cancelled =
                store.atomic(
                        () -> {
                            int n =
                                    store.db()
                                            .update(
                                                    "update engine.run set status='CANCELLED',"
                                                            + "stop_reason='USER_CANCEL',ended_at=now()"
                                                            + " where public_id=? and status not in"
                                                            + " ('SUCCEEDED','FAILED','CANCELLED')",
                                                    latest.runPublicId());
                            if (n > 0)
                                turnEvents.append(
                                        id,
                                        null,
                                        cn.agentplatform.engine.harness.session.SessionEvents.TURN_END,
                                        Json.tree(java.util.Map.of(
                                                "turn",
                                                latest.turnNo(),
                                                "reason",
                                                cn.agentplatform.engine.harness.session
                                                        .SessionEvents.TURN_REASON_ABORTED)),
                                        null);
                            return n > 0;
                        });
        if (!cancelled)
            throw new PlatformException(409, "SESSION_TURN_ACTIVE", "轮次已在此前完成或取消");
        return Map.of("accepted", true);
    }

    /** STEER：写 user/message{message} 事件；活动轮次内 pending=true（下一步注入），轮次外立即生效。 */
    private Map<String, Object> steer(String id, JsonNode payload) {
        JsonNode message = payload.path("message");
        if (!message.isTextual() || message.asText().isBlank() || message.asText().length() > 32768)
            throw new PlatformException(
                    400, "SESSION_CONTROL_PAYLOAD_INVALID", "STEER 必须提供 message 文本（≤32768 字）");
        boolean pending = turnActive(latestTurn(id));
        store.atomic(
                () ->
                        turnEvents.append(
                                id,
                                null,
                                cn.agentplatform.engine.harness.session.SessionEvents.USER_MESSAGE,
                                Json.tree(java.util.Map.of("message", message.asText())),
                                null));
        return pending
                ? Map.of("accepted", true, "pending", true)
                : Map.of("accepted", true);
    }

    /** SET_PLAN_MODE：写 plan/mode{active} 事件；turn 内 pending（下一 accepted pre-step 生效），turn 外立即提交。 */
    private Map<String, Object> setPlanMode(String id, JsonNode payload) {
        JsonNode active = payload.path("active");
        if (!active.isBoolean())
            throw new PlatformException(
                    400, "SESSION_CONTROL_PAYLOAD_INVALID", "SET_PLAN_MODE 必须提供布尔 active");
        boolean pending = turnActive(latestTurn(id));
        store.atomic(
                () ->
                        turnEvents.append(
                                id,
                                null,
                                cn.agentplatform.engine.harness.session.SessionEvents.PLAN_MODE,
                                Json.tree(java.util.Map.of("active", active.asBoolean())),
                                null));
        return pending
                ? Map.of("accepted", true, "pending", true)
                : Map.of("accepted", true);
    }

    /**
     * 单连接泵：批量读 PG→同步发送。断开（send 失败）只停泵不取消 turn；积压超限断开走重连
     * 恢复。R-16：连接时固化调用者身份快照（泵线程无请求上下文）供积压断开审计与连接数上限
     * 统计；未读积压超 {@code maxBufferedEvents} 主动断开并记审计行（AUDIT
     * SESSION_SSE_SLOW_CONSUMER_DISCONNECT）。
     */
    final class StreamPump implements Runnable {
        private final String appCode;
        private final String sessionId;
        private final long after;
        private final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter;
        private final String tenantId;
        private final String principalId;
        private final Runnable guard;
        private final java.util.concurrent.atomic.AtomicBoolean open =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        StreamPump(
                String appCode,
                String sessionId,
                long after,
                org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                String tenantId,
                String principalId,
                Runnable guard) {
            this.appCode = appCode;
            this.sessionId = sessionId;
            this.after = after;
            this.emitter = emitter;
            this.tenantId = tenantId;
            this.principalId = principalId;
            this.guard = guard;
        }

        @Override
        public void run() {
            try {
                long cursor = after;
                long lastEmit = System.currentTimeMillis();
                while (open.get()) {
                    if (guard != null) guard.run();
                    var scanned = turnEvents.read(sessionId, cursor, 200);
                    if (scanned.isEmpty()) {
                        if (System.currentTimeMillis() - lastEmit >= heartbeatMillis) {
                            send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                    .event()
                                    .comment("ping"));
                            lastEmit = System.currentTimeMillis();
                        }
                        Thread.sleep(200);
                        continue;
                    }
                    for (var event : scanned) {
                        cursor = event.seq();
                        if (SURFACE_HIDDEN.contains(event.type())) continue;
                        if (guard != null) guard.run();
                        send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                .event()
                                .id(Long.toString(event.seq()))
                                .name(event.type())
                                .data(
                                        Json.write(event.data()),
                                        org.springframework.http.MediaType.APPLICATION_JSON));
                        lastEmit = System.currentTimeMillis();
                    }
                    if (turnEvents.watermark(sessionId) - cursor > Math.max(1, maxBufferedEvents)) {
                        // R-16：慢消费者超限断开——记审计行（未读积压与上限可追溯），重连以
                        // Last-Event-ID 续读（seq 幂等去重，不重不漏）；停泵不触碰 run/turn。
                        org.slf4j.LoggerFactory.getLogger(ChatSessions.class)
                                .warn(
                                        "AUDIT SESSION_SSE_SLOW_CONSUMER_DISCONNECT tenant={} principal={} appCode={} sessionId={} unreadBacklog={} limit={} resume=Last-Event-ID",
                                        tenantId,
                                        principalId,
                                        appCode,
                                        sessionId,
                                        turnEvents.watermark(sessionId) - cursor,
                                        Math.max(1, maxBufferedEvents));
                        close();
                        return;
                    }
                }
            } catch (java.io.IOException | IllegalStateException | PlatformException disconnected) {
                // 客户端断开/容器回收：SSE 只是观察通道，停泵即止，不触碰 run/turn。
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        private void send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder frame)
                throws java.io.IOException {
            if (!open.get()) return;
            synchronized (emitter) {
                emitter.send(frame);
            }
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            streams.remove(this);
            try {
                emitter.complete();
            } catch (Exception alreadyGone) {
                // 客户端已断开或响应已完成：仅收口本泵。
            }
        }
    }

    /** 仅已验内部评测主体；固定候选会话不读取published，也不走TASK/FULL受理。 */
    public Map<String,Object> evaluationTurn(JsonNode req,Actor actor) {
        boolean iam = runs.iamEnabled();
        if (!iam && !actor.roles().contains("SERVICE")) throw new PlatformException(403,"EVALUATION_SERVICE_REQUIRED","仅内部评测调用可受理候选轮次");
        String code=req.path("appCode").asText(), report=req.path("evalReportId").asText(), caseId=req.path("caseId").asText();
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || report.isBlank() || report.length()>128 || caseId.isBlank() || caseId.length()>128
                || !req.path("turnNo").isIntegralNumber() || !req.path("turnNo").canConvertToInt() || req.path("turnNo").intValue()<1
                || !req.path("inputs").isObject() || !req.path("frozenCandidate").isObject())
            throw new PlatformException(400,"EVALUATION_TURN_INVALID","评测轮次参数无效");
        int turn=req.path("turnNo").intValue();
        var candidate=req.path("frozenCandidate");
        var authorization = iam ? runs.requireEvaluationSessionTurn(code, req, actor) : null;
        if (!"CHAT".equals(candidate.path("type").asText()) || candidate.has("chatSession") || candidate.has("evaluationConversation"))
            throw new PlatformException(400,"EVALUATION_CHAT_REQUIRED","候选会话仅支持显式CHAT，历史指针由服务器创建");
        if (iam) {
            if (candidate.has("evaluationHistory")) throw RunAuthorityStore.denied();
            InitialChatRules.requireEvaluationSources(candidate, req.path("inputs"));
        }
        Map<String,Object> content=new LinkedHashMap<>();
        for(String key:List.of("appCode","type","name","description","domain","bizCategory","tags","config","definition")) content.put(key,candidate.path(key));
        String sha=Digest.sha256(Json.canonical(content));
        if (!code.equals(candidate.path("appCode").asText()) || !sha.equals(req.path("candidateSha256").asText())
                || !sha.equals(candidate.path("candidateSha256").asText()) || !req.path("draftRev").isIntegralNumber()
                || req.path("draftRev").asLong()<1 || req.path("draftRev").asLong()!=candidate.path("draftRev").asLong()
                || req.path("datasetVersion").asText().isBlank())
            throw new PlatformException(409,"CANDIDATE_MISMATCH","评测候选身份或正文不匹配");
        if(turn>maxTurns(candidate)) throw new PlatformException(429,"CHAT_TURN_LIMIT","评测轮数超过固定候选上限");
        String id="es_"+Digest.sha256(Json.canonical(Map.of("tenant",actor.tenantId(),"report",report,"case",caseId))).substring(0,48);
        if(req.hasNonNull("conversationId") && !id.equals(req.path("conversationId").asText()))
            throw new PlatformException(409,"EVALUATION_CONVERSATION_CONFLICT","轮次不能切换会话");
        var binding=new LinkedHashMap<String,Object>();
        binding.put("schemaVersion",1); binding.put("evalReportId",report); binding.put("caseId",caseId);
        binding.put("appCode",code); binding.put("draftRev",req.path("draftRev").asLong()); binding.put("candidateSha256",sha);
        binding.put("datasetVersion",req.path("datasetVersion").asText());
        var fixed=(com.fasterxml.jackson.databind.node.ObjectNode)candidate.deepCopy();
        fixed.set("evaluationConversation",Json.tree(binding));
        var reservation=store.atomic(()->{
            store.db().update("insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot) values(?,?,?,?,?::jsonb) on conflict do nothing",id,actor.tenantId(),actor.principalId(),code,Json.write(fixed));
            var session=owned(code,id,actor,true);
            // JSONB读回可能把同值LongNode归为IntNode；比较完整canonical材料，仍核全部候选与来源字段。
            if(!Json.canonical(TraceStore.json(session.get("snapshot"))).equals(Json.canonical(fixed))) throw new PlatformException(409,"EVALUATION_CONVERSATION_CONFLICT","原会话已固定其他候选或评测来源");
            var prior=store.db().queryForList("select * from engine.chat_turn where session_id=? and turn_no=?",id,turn);
            if(!prior.isEmpty()){
                if(!TraceStore.json(prior.getFirst().get("inputs")).equals(req.path("inputs")))
                    throw new PlatformException(409,"REQUEST_CONFLICT","原轮次已绑定其他输入");
                return prior.getFirst();
            }
            requireTurnLimit(session);
            if(((Number)session.get("next_turn")).intValue()!=turn) throw new PlatformException(409,"EVALUATION_TURN_ORDER","必须按明确顺序续接轮次");
            if(turn>1){
                var previous=store.db().queryForList("select r.status from engine.chat_turn t left join engine.run r on r.public_id=t.run_public_id where t.session_id=? and t.turn_no=?",id,turn-1);
                if(previous.isEmpty() || !"SUCCEEDED".equals(previous.getFirst().get("status")))
                    throw new PlatformException(409,"CHAT_TURN_ACTIVE","前轮尚未成功完成，不能发起下一轮");
                if (!iam) ChatHistory.checkScope(store,id,code,actor);
            }
            InputDeclarations.validate(candidate,req.path("inputs"));
            store.db().update("insert into engine.chat_turn(session_id,turn_no,request_id,inputs) values(?,?,?,?::jsonb)",id,turn,"turn:"+turn,Json.write(req.path("inputs")));
            store.db().update("update engine.chat_session set next_turn=next_turn+1 where public_id=?",id);
            return store.db().queryForMap("select * from engine.chat_turn where session_id=? and turn_no=?",id,turn);
        });
        Map<String,Object> result;
        if(!iam && reservation.get("run_public_id")!=null) result=runs.status(reservation.get("run_public_id").toString(),actor);
        else{
            var source=(com.fasterxml.jackson.databind.node.ObjectNode)fixed.deepCopy();
            source.set("chatSession",Json.tree(Map.of("sessionId",id,"turnNo",turn)));
            if (iam && reservation.get("run_public_id") == null)
                source.set("evaluationHistory", store.conversationHistory(id, turn, source, authorization));
            result=runs.createEvaluationSessionTurn(code,req,source,actor);
            if (!iam) store.db().update("update engine.chat_turn set run_public_id=? where session_id=? and turn_no=? and run_public_id is null",result.get("runId"),id,turn);
        }
        var response=new LinkedHashMap<String,Object>(result);
        response.putAll(binding); response.put("conversationId",id); response.put("turnNo",turn);
        return response;
    }

    /** 会话行里固定的配置版本；会话未固定时拒绝轮次临时指定，避免同一会话先后挂到两套配置。 */
    RunService.ConfigReference pinnedConfig(Map<String, Object> session, JsonNode req) {
        Object releaseId = session.get("config_release_id");
        String requested =
                req == null ? "" : req.path("configReleaseId").asText("").trim();
        if (releaseId == null) {
            if (!requested.isEmpty())
                throw new PlatformException(
                        409, "CONFIG_REV_CONFLICT", "会话创建时未固定配置版本，轮次不能指定");
            return null;
        }
        if (!requested.isEmpty() && !requested.equals(releaseId.toString()))
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "轮次配置版本与会话固定版本不一致");
        Object digest = session.get("config_digest");
        Object environment = session.get("config_environment");
        if (digest == null || environment == null)
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "会话固定配置版本缺少完整性字段");
        return new RunService.ConfigReference(
                releaseId.toString(),
                digest.toString(),
                environment.toString(),
                TraceStore.json(session.get("environment_binding_revs")));
    }

    private Map<String, Object> owned(String code, String id, Actor actor, boolean lock) {
        var rows =
                store.db()
                        .queryForList(
                                "select * from engine.chat_session where public_id=? and app_code=?"
                                        + " and tenant_id=? and principal_id=?"
                                        + (lock ? " for update" : ""),
                                id,
                                code,
                                actor.tenantId(),
                                actor.principalId());
        if (rows.isEmpty()) {
            if (clients != null && clients.iamEnabled()) {
                org.slf4j.LoggerFactory.getLogger(ChatSessions.class).warn(
                        "AUDIT PORTAL_SESSION_DENIED tenant={} principal={} appCode={} sessionId={}",
                        actor.tenantId(), actor.principalId(), code, id);
                throw new PlatformException(403, "SYS-AUTH-403", "无权读取或操作该会话");
            }
            throw new PlatformException(404, "SESSION_NOT_FOUND", "会话不存在");
        }
        return rows.getFirst();
    }

    /** 上限随会话创建时的发布快照固定；后续应用改稿不会改变旧会话，缺省40轮。 */
    private static int maxTurns(JsonNode snapshot) {
        var value = snapshot.path("config").path("context").path("maxTurns");
        if (value.isMissingNode()) return 40;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 200)
            throw new PlatformException(400, "CHAT_CONTEXT_INVALID", "会话最大轮数须为1至200的整数");
        return value.intValue();
    }

    private static void requireTurnLimit(Map<String, Object> session) {
        int limit = maxTurns(TraceStore.json(session.get("snapshot")));
        if (((Number) session.get("next_turn")).longValue() > limit)
            throw new PlatformException(429, "CHAT_TURN_LIMIT", "会话已达到固定版本的 " + limit + " 轮上限，请新建会话后继续");
    }
}
