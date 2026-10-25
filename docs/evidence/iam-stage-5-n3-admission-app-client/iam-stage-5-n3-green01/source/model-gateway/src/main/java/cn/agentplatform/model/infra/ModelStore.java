package cn.agentplatform.model.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.model.domain.ModelPolicy;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class ModelStore {
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ModelStore(JdbcTemplate db) {
        this.db = db;
        this.tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                db.getDataSource()));
    }

    public List<Map<String, Object>> providers(Actor actor) {
        return db
                .queryForList(
                        "select"
                            + " id,code,name,protocol,base_url,trust_labels,status,tail_hint,updated_at,config_rev"
                            + " from model.provider where tenant_id=? and not deleted order by id",
                        actor.tenantId())
                .stream()
                .map(this::providerView)
                .toList();
    }

    private Map<String, Object> providerView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("baseUrl", result.remove("base_url"));
        result.put("trustLabels", Json.read(result.remove("trust_labels").toString()));
        result.put("tailHint", result.remove("tail_hint"));
        result.put("updatedAt", Objects.toString(result.remove("updated_at"), ""));
        return result;
    }

    public Map<String, Object> provider(long id, Actor actor) {
        var rows =
                db.queryForList(
                        "select * from model.provider where id=? and tenant_id=? and not deleted",
                        id,
                        actor.tenantId());
        if (rows.isEmpty())
            throw new PlatformException(404, "MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在");
        return rows.getFirst();
    }

    public Map<String, Object> createProvider(JsonNode body, Actor actor) {
        actor.requireRole("ADMIN");
        String code = body.path("code").asText(),
                name = body.path("name").asText(),
                base = body.path("baseUrl").asText();
        if (!code.matches("[a-zA-Z][a-zA-Z0-9_-]{1,63}") || name.isBlank())
            throw new IllegalArgumentException("请填写合法编码和名称");
        ModelPolicy.endpoint(base);
        JsonNode labels = body.path("trustLabels");
        if (!labels.isArray() || labels.isEmpty()) throw new IllegalArgumentException("必须声明端点信任标签");
        String ref = body.path("secretRef").asText("");
        String hint = "";
        String secret = body.path("credential").path("secret").asText("");
        if (!secret.isBlank() || !ref.isBlank()) actor.requireRole("ADMIN");
        if (!secret.isBlank()) {
            ref = SecretVault.put(secret, actor);
            hint = secret.substring(Math.max(0, secret.length() - 4));
        }
        if (!ref.isBlank() && !ref.startsWith("vault:") && !ref.startsWith("env:AGENT_SECRET_"))
            throw new IllegalArgumentException("凭据必须为受控secret_ref");
        if (!ref.isBlank()) SecretVault.resolve(ref, actor);
        if (!body.path("protocol").asText("OPENAI_COMPATIBLE").equals("OPENAI_COMPATIBLE"))
            throw new IllegalArgumentException("当前仅支持OPENAI_COMPATIBLE协议");
        Long id =
                db.queryForObject(
                        "insert into"
                            + " model.provider(tenant_id,code,name,base_url,trust_labels,secret_ref,tail_hint,created_by)"
                            + " values(?,?,?,?,?::jsonb,?,?,?) returning id",
                        Long.class,
                        actor.tenantId(),
                        code,
                        name,
                        base,
                        Json.write(labels),
                        ref.isBlank() ? null : ref,
                        hint,
                        actor.principalId());
        return Map.of("id", id, "code", code, "status", "DISABLED", "hint", "连接测试通过后启用");
    }

    public boolean tested(long id, Actor actor, boolean ok, long revision) {
        return db.update(
                        "update model.provider set status=?,updated_at=now() where id=? and"
                                + " tenant_id=? and config_rev=?",
                        ok ? "ENABLED" : "DISABLED",
                        id,
                        actor.tenantId(),
                        revision)
                == 1;
    }

    private Map<String, Object> providerLocked(long id, Actor actor) {
        var rows =
                db.queryForList(
                        "select * from model.provider where id=? and tenant_id=? and not deleted"
                                + " for update",
                        id,
                        actor.tenantId());
        if (rows.isEmpty())
            throw new PlatformException(404, "MODEL_PROVIDER_NOT_FOUND", "模型供应商不存在");
        return rows.getFirst();
    }

    public Map<String, Object> rotate(long id, JsonNode body, Actor actor) {
        actor.requireRole("ADMIN");
        return tx.execute(
                status -> {
                    providerLocked(id, actor);
                    String secret = body.path("secret").asText(""),
                            ref = body.path("secretRef").asText("");
                    if (!secret.isBlank()) ref = SecretVault.put(secret, actor);
                    else if (!ref.isBlank()) SecretVault.resolve(ref, actor);
                    else throw new IllegalArgumentException("请填写新凭据或受控引用");
                    String hint =
                            secret.isBlank()
                                    ? ""
                                    : secret.substring(Math.max(0, secret.length() - 4));
                    db.update(
                            "update model.provider set"
                                + " secret_ref=?,tail_hint=?,config_rev=config_rev+1,updated_at=now()"
                                + " where tenant_id=? and id=?",
                            ref,
                            hint,
                            actor.tenantId(),
                            id);
                    return Map.of("id", id, "tailHint", hint, "rotated", true);
                });
    }

    public Map<String, Object> updateProvider(long id, JsonNode body, Actor actor) {
        actor.requireRole("ADMIN");
        return tx.execute(
                status -> {
                    var old = providerLocked(id, actor);
                    if (body.has("secretRef") || body.has("credential")) actor.requireRole("ADMIN");
                    String base = body.path("baseUrl").asText(old.get("base_url").toString()),
                            name = body.path("name").asText(old.get("name").toString());
                    ModelPolicy.endpoint(base);
                    if (name.isBlank()) throw new IllegalArgumentException("供应商名称不能为空");
                    if (!base.equals(old.get("base_url"))
                            && !Objects.toString(old.get("secret_ref"), "").isBlank())
                        actor.requireRole("ADMIN");
                    JsonNode labels =
                            body.has("trustLabels")
                                    ? body.path("trustLabels")
                                    : Json.read(old.get("trust_labels").toString());
                    if (!labels.isArray() || labels.isEmpty())
                        throw new IllegalArgumentException("必须声明端点信任标签");
                    String protocol = body.path("protocol").asText(old.get("protocol").toString());
                    if (!protocol.equals("OPENAI_COMPATIBLE"))
                        throw new IllegalArgumentException("当前仅支持OPENAI_COMPATIBLE协议");
                    // 端点/信任边界修改后重新连接测试，不能用body.status任意直接启用。
                    db.update(
                            "update model.provider set"
                                + " name=?,base_url=?,trust_labels=?::jsonb,protocol=?,status='DISABLED',config_rev=config_rev+1,updated_at=now()"
                                + " where tenant_id=? and id=?",
                            name,
                            base,
                            Json.write(labels),
                            protocol,
                            actor.tenantId(),
                            id);
                    if (body.has("secretRef") || body.has("credential"))
                        rotate(
                                id,
                                Json.tree(
                                        Map.of(
                                                "secret",
                                                body.path("credential").path("secret").asText(""),
                                                "secretRef",
                                                body.path("secretRef").asText(""))),
                                actor);
                    return providerView(
                            db.queryForMap(
                                    "select"
                                        + " id,code,name,protocol,base_url,trust_labels,status,tail_hint,updated_at"
                                        + " from model.provider where tenant_id=? and id=?",
                                    actor.tenantId(),
                                    id));
                });
    }

    public Map<String, Object> disableProvider(long id, Actor actor) {
        actor.requireRole("ADMIN");
        tx.executeWithoutResult(
                s -> {
                    providerLocked(id, actor);
                    db.update(
                            "update model.provider set"
                                    + " status='DISABLED',config_rev=config_rev+1,updated_at=now()"
                                    + " where tenant_id=? and id=?",
                            actor.tenantId(),
                            id);
                });
        return Map.of("id", id, "status", "DISABLED");
    }

    public Map<String, Object> updateEntry(long id, JsonNode body, Actor actor) {
        actor.requireRole("ADMIN");
        var old = entry(id, actor, true);
        String name = body.path("modelName").asText(old.get("modelName").toString()),
                display = body.path("displayName").asText(old.get("displayName").toString()),
                tokenizer = body.path("tokenizerUrl").asText(old.get("tokenizerUrl").toString());
        int
                window =
                        body.path("contextWindow")
                                .asInt(((Number) old.get("contextWindow")).intValue()),
                max =
                        body.path("maxOutputTokens")
                                .asInt(((Number) old.get("maxOutputTokens")).intValue());
        if (name.isBlank() || max < 1 || window <= max)
            throw new IllegalArgumentException("模型窗口与输出上限无效");
        if (!tokenizer.isBlank()) ModelPolicy.endpoint(tokenizer);
        JsonNode caps =
                body.has("capabilities")
                        ? body.path("capabilities")
                        : Json.tree(old.get("capabilities"));
        if (!caps.isArray()) throw new IllegalArgumentException("capabilities必须为数组");
        String status = body.path("status").asText(old.get("status").toString());
        if (!Set.of("ENABLED", "DISABLED").contains(status))
            throw new IllegalArgumentException("模型状态无效");
        db.update(
                "update model.entry set"
                    + " model_name=?,display_name=?,context_window=?,max_output_tokens=?,tokenizer_url=?,capabilities=?::jsonb,status=?"
                    + " where tenant_id=? and id=?",
                name,
                display,
                window,
                max,
                tokenizer,
                Json.write(caps),
                status,
                actor.tenantId(),
                id);
        return entry(id, actor, false);
    }

    public List<Map<String, Object>> entries(Actor actor) {
        return db
                .queryForList(
                        "select id from model.entry where tenant_id=? order by id",
                        actor.tenantId())
                .stream()
                .map(r -> entry(((Number) r.get("id")).longValue(), actor, false))
                .toList();
    }

    public Map<String, Object> createEntry(JsonNode body, Actor actor) {
        actor.requireRole("ADMIN");
        long provider = body.path("providerId").asLong();
        provider(provider, actor);
        String name = body.path("modelName").asText();
        int window = body.path("contextWindow").asInt(), max = body.path("maxOutputTokens").asInt();
        if (name.isBlank() || window <= 0 || max <= 0 || max >= window)
            throw new IllegalArgumentException("请填写模型名称、窗口与有效输出上限");
        String tokenizer = body.path("tokenizerUrl").asText("");
        if (!tokenizer.isBlank()) ModelPolicy.endpoint(tokenizer);
        Long id =
                db.queryForObject(
                        "insert into"
                            + " model.entry(tenant_id,provider_id,model_name,display_name,context_window,max_output_tokens,capabilities,tokenizer_url,created_by)"
                            + " values(?,?,?,?,?,?,?::jsonb,?,?) returning id",
                        Long.class,
                        actor.tenantId(),
                        provider,
                        name,
                        body.path("displayName").asText(name),
                        window,
                        max,
                        Json.write(body.path("capabilities")),
                        tokenizer.isBlank() ? null : tokenizer,
                        actor.principalId());
        return entry(id, actor, false);
    }

    public Map<String, Object> entry(long id, Actor actor, boolean internal) {
        var rows =
                db.queryForList(
                        "select * from model.entry where id=? and tenant_id=?",
                        id,
                        actor.tenantId());
        if (rows.isEmpty()) throw new PlatformException(404, "MODEL_ENTRY_NOT_FOUND", "模型条目不存在");
        var r = rows.getFirst();
        var p = provider(((Number) r.get("provider_id")).longValue(), actor);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("providerId", r.get("provider_id"));
        result.put("modelName", r.get("model_name"));
        result.put("displayName", r.get("display_name"));
        result.put("contextWindow", r.get("context_window"));
        result.put("maxOutputTokens", r.get("max_output_tokens"));
        result.put("capabilities", Json.read(r.get("capabilities").toString()));
        result.put("status", r.get("status"));
        result.put(
                "provider",
                Map.of(
                        "id",
                        p.get("id"),
                        "code",
                        p.get("code"),
                        "name",
                        p.get("name"),
                        "status",
                        p.get("status"),
                        "trustLabels",
                        Json.read(p.get("trust_labels").toString())));
        if (internal) {
            result.put("protocol", p.get("protocol"));
            result.put("baseUrl", p.get("base_url"));
            result.put("secretRef", Objects.toString(p.get("secret_ref"), ""));
            result.put("tokenizerUrl", Objects.toString(r.get("tokenizer_url"), ""));
        }
        return result;
    }

    /** 新绑定必须启用；已有快照与可变管理面分离，凭据引用绝不进入快照。 */
    public Map<String, Object> freeze(long id, Actor actor) {
        var snapshot = new LinkedHashMap<>(entry(id, actor, true));
        requireEnabled(snapshot);
        snapshot.remove("secretRef");
        snapshot.remove("status");
        var p = new LinkedHashMap<>((Map<String, Object>) snapshot.get("provider"));
        p.remove("status");
        snapshot.put("provider", p);
        String json = Json.canonical(snapshot), sha = Digest.sha256(json);
        Long binding =
                db.queryForObject(
                        "insert into"
                            + " model.binding_snapshot(tenant_id,model_entry_id,provider_id,sha256,snapshot)"
                            + " values(?,?,?,?,?::jsonb) on conflict(tenant_id,sha256) do update"
                            + " set sha256=excluded.sha256 returning id",
                        Long.class,
                        actor.tenantId(),
                        id,
                        snapshot.get("providerId"),
                        sha,
                        json);
        return Map.of("modelEntryId", id, "bindingId", binding, "sha256", sha, "entry", snapshot);
    }

    public void requireEnabled(Map<String, Object> entry) {
        if ("DEGRADED".equals(((Map<?, ?>) entry.get("provider")).get("status")))
            throw new PlatformException(503, "SYS-MODEL-503", "模型供应商熔断中，请稍后重试或切换供应商");
        if (!"ENABLED".equals(entry.get("status"))
                || !"ENABLED".equals(((Map<?, ?>) entry.get("provider")).get("status")))
            throw new PlatformException(409, "SYS-MODEL-4001", "模型或供应商尚未启用，请先连接测试");
    }

    public Map<String, Object> resolve(JsonNode request, Actor actor) {
        JsonNode ref = request.path("modelBindingRef");
        if (ref.isMissingNode() || ref.isNull()) {
            var entry = entry(request.path("modelEntryId").asLong(), actor, true);
            requireEnabled(entry);
            return entry;
        }
        var rows =
                db.queryForList(
                        "select snapshot::text,provider_id,model_entry_id from"
                                + " model.binding_snapshot where tenant_id=? and id=? and sha256=?",
                        actor.tenantId(),
                        ref.path("bindingId").asLong(),
                        ref.path("sha256").asText());
        if (rows.isEmpty()) throw new PlatformException(409, "SYS-MODEL-4001", "模型冻结引用无效或不属于当前租户");
        var row = rows.getFirst();
        long id = ((Number) row.get("model_entry_id")).longValue();
        if (request.has("modelEntryId") && request.path("modelEntryId").asLong() != id)
            throw new PlatformException(409, "SYS-MODEL-4001", "模型冻结引用与绑定不一致");
        var entry =
                new LinkedHashMap<String, Object>(
                        Json.read(row.get("snapshot").toString(), Map.class));
        // 仅凭据引用随轮换更新；端点、信任标签、模型名和窗口继续使用冻结值。
        var p = provider(((Number) row.get("provider_id")).longValue(), actor);
        entry.put("secretRef", Objects.toString(p.get("secret_ref"), ""));
        return entry;
    }

    public void validateBinding(JsonNode ref, Actor actor) {
        var entry = resolve(Json.tree(Map.of("modelBindingRef", ref)), actor);
        requireEnabled(entry(((Number) entry.get("id")).longValue(), actor, true));
    }

    /** Redis准入前持久保存意图；未越过发送边界的预留始终有独立的零用量恢复入口。 */
    public void reserveQuota(
            Actor actor, JsonNode request, Map<String, Object> entry, Map<String, Object> receipt) {
        db.update(
                "insert into model.quota_reservation(tenant_id,reservation_id,principal_id,"
                    + "invocation_ref,model_entry_id,provider_id,app_code,quota_receipt,lease_until)"
                    + " values(?,?,?,?,?,?,?,?::jsonb,?)",
                actor.tenantId(),
                receipt.get("id"),
                actor.principalId(),
                request.path("invocationRef").asText(receipt.get("id").toString()),
                entry.get("id"),
                entry.get("providerId"),
                request.path("appCode").asText("_unbound"),
                Json.write(receipt),
                new java.sql.Timestamp(((Number) receipt.get("admissionUntil")).longValue()));
    }

    public void quotaSending(Actor actor, Map<String, Object> receipt) {
        // 与租约恢复争用同一行；过期恢复先赢时，迟到发送者必须停在网络边界之前。
        if (db.update(
                        "update model.quota_reservation set status='SENT',updated_at=now() where"
                                + " tenant_id=? and reservation_id=? and status='RESERVING' and"
                                + " lease_until>now()",
                        actor.tenantId(),
                        receipt.get("id"))
                != 1) throw new PlatformException(503, "SYS-MODEL-503", "模型预留已过期，请重新提交");
    }

    public boolean quotaNoSend(String tenant, String reservation, String error) {
        return db.update(
                        "update model.quota_reservation set status='NO_SEND',"
                                + "usage_reconciliation='PENDING',error_code=?,updated_at=now()"
                                + " where tenant_id=? and reservation_id=? and status='RESERVING'",
                        error,
                        tenant,
                        reservation)
                == 1;
    }

    public List<Map<String, Object>> pendingNoSend() {
        db.update(
                "update model.quota_reservation set status='NO_SEND',"
                    + "usage_reconciliation='PENDING',error_code='SYS-MODEL-UNSENT',updated_at=now()"
                    + " where status='RESERVING' and lease_until<=now()");
        return db.queryForList(
                "select tenant_id,reservation_id,quota_receipt::text from model.quota_reservation"
                        + " where status='NO_SEND' and usage_reconciliation='PENDING' order by"
                        + " created_at limit 100");
    }

    public void noSendReconciled(String tenant, String reservation, String outcome) {
        if (!Set.of("RECONCILED", "PARTIAL", "EXPIRED").contains(outcome))
            throw new IllegalArgumentException("未发送账务终态无效");
        db.update(
                "update model.quota_reservation set usage_reconciliation=?,updated_at=now() where"
                        + " tenant_id=? and reservation_id=? and status='NO_SEND' and"
                        + " usage_reconciliation='PENDING'",
                outcome,
                tenant,
                reservation);
    }

    /** 最终实际请求在网络发送前提交；每个invocationRef/sendSeq独立，禁止覆盖重发材料。 */
    public long sending(
            Actor actor,
            JsonNode req,
            Map<String, Object> entry,
            String actualUrl,
            String body,
            int tokens) {
        var snapshot = new LinkedHashMap<>(entry);
        snapshot.remove("secretRef");
        JsonNode wire = Json.read(body);
        int estimate = Math.max(1, (Json.canonical(wire).length() + 3) / 4 + 64);
        String scope =
                Digest.sha256(
                        Json.canonical(
                                Map.of(
                                        "model",
                                        snapshot,
                                        "tools",
                                        wire.path("tools"),
                                        "responseFormat",
                                        wire.path("response_format"),
                                        "stream",
                                        wire.path("stream"))));
        var calibration =
                db.queryForList(
                        "select samples,actual_prompt_tokens::double"
                            + " precision/nullif(estimated_tokens,0) as factor from"
                            + " model.metering_calibration where tenant_id=? and model_entry_id=?"
                            + " and calibration_scope=?",
                        actor.tenantId(),
                        entry.get("id"),
                        scope);
        double factor =
                calibration.isEmpty()
                        ? 1.0
                        : ((Number) calibration.getFirst().get("factor")).doubleValue();
        int corrected =
                (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.ceil(estimate * factor)));
        var metering =
                Map.of(
                        "estimateMethod",
                        "CHARACTERS_DIVIDED_BY_FOUR_WITH_SCOPED_CALIBRATION_FOR_DISPLAY",
                        "baseEstimate",
                        estimate,
                        "displayFactor",
                        factor,
                        "calibrationScope",
                        scope,
                        "admissionMethod",
                        entry.get("tokenizerUrl").toString().isBlank()
                                ? "JTOKKIT_WITH_CONSERVATIVE_STRUCTURE"
                                : "MODEL_TOKENIZER",
                        "exact",
                        !entry.get("tokenizerUrl").toString().isBlank());
        return db.queryForObject(
                "insert into"
                    + " model.call_log(tenant_id,principal_id,run_id,attempt_id,invocation_ref,model_entry_id,provider_id,actual_endpoint,request_snapshot,estimated_tokens,call_kind,admission_tokens,metering_snapshot,model_snapshot,egress,calibration_scope,quota_receipt)"
                    + " values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb)"
                    + " returning id",
                Long.class,
                actor.tenantId(),
                actor.principalId(),
                req.path("runId").asText(""),
                req.hasNonNull("attemptId") ? req.path("attemptId").asLong() : null,
                req.path("invocationRef").asText(UUID.randomUUID().toString()),
                entry.get("id"),
                entry.get("providerId"),
                actualUrl,
                body,
                corrected,
                req.path("callKind").asText("FORMAL"),
                tokens,
                Json.write(metering),
                Json.write(snapshot),
                Json.write(
                        Map.of(
                                "requiredLabels",
                                req.path("requiredLabels"),
                                "provider",
                                entry.get("provider"),
                                "endpoint",
                                actualUrl)),
                scope,
                Json.write(req.path("_quotaReceipt")));
    }

    public void finished(long id, Map<String, Object> response, long ms) {
        JsonNode usage = Json.tree(response.get("usage"));
        db.update(
                "update model.call_log set"
                    + " status='SUCCEEDED',prompt_tokens=?,completion_tokens=?,total_tokens=?,duration_ms=?,response_snapshot=?::jsonb,usage_reconciliation=?,ended_at=now()"
                    + " where id=?",
                usage.path("promptTokens").isNumber() ? usage.path("promptTokens").asInt() : null,
                usage.path("completionTokens").isNumber()
                        ? usage.path("completionTokens").asInt()
                        : null,
                usage.path("totalTokens").isNumber() ? usage.path("totalTokens").asInt() : null,
                Math.toIntExact(Math.min(ms, Integer.MAX_VALUE)),
                Json.write(response),
                usage.path("totalTokens").asInt() > 0 ? "PENDING" : "UNAVAILABLE",
                id);
        if (usage.path("promptTokens").asInt() > 0)
            db.update(
                    "insert into"
                        + " model.metering_calibration(tenant_id,model_entry_id,calibration_scope,samples,estimated_tokens,actual_prompt_tokens)"
                        + " select"
                        + " tenant_id,model_entry_id,calibration_scope,1,coalesce((metering_snapshot->>'baseEstimate')::integer,estimated_tokens),prompt_tokens"
                        + " from model.call_log where id=? on"
                        + " conflict(tenant_id,model_entry_id,calibration_scope) do update set"
                        + " samples=model.metering_calibration.samples+1,estimated_tokens=model.metering_calibration.estimated_tokens+excluded.estimated_tokens,actual_prompt_tokens=model.metering_calibration.actual_prompt_tokens+excluded.actual_prompt_tokens,updated_at=now()",
                    id);
    }

    public void reconciled(long id, String outcome) {
        if (!Set.of("RECONCILED", "PARTIAL", "EXPIRED").contains(outcome))
            throw new IllegalArgumentException("用量账务终态无效");
        db.update(
                "update model.call_log set usage_reconciliation=? where id=? and"
                        + " status='SUCCEEDED' and usage_reconciliation='PENDING'",
                outcome,
                id);
    }

    public List<Map<String, Object>> pendingUsage() {
        return db.queryForList(
                "select id,total_tokens,quota_receipt::text from model.call_log where"
                        + " status='SUCCEEDED' and usage_reconciliation='PENDING' and"
                        + " quota_receipt<>'{}'::jsonb order by id limit 100");
    }

    public List<Map<String, Object>> calibrations(long id, Actor actor) {
        entry(id, actor, false);
        return db.queryForList(
                "select"
                    + " calibration_scope,samples,estimated_tokens,actual_prompt_tokens,actual_prompt_tokens::numeric/nullif(estimated_tokens,0)"
                    + " as display_factor,updated_at from model.metering_calibration where"
                    + " tenant_id=? and model_entry_id=? order by updated_at desc",
                actor.tenantId(),
                id);
    }

    public void failed(long id, String code, long ms) {
        db.update(
                "update model.call_log set"
                        + " status='FAILED',error_code=?,duration_ms=?,ended_at=now() where id=?",
                code,
                Math.toIntExact(Math.min(ms, Integer.MAX_VALUE)),
                id);
    }

    public Map<String, Object> statistics(Actor actor) {
        actor.requireRole("ADMIN");
        return db.queryForMap(
                "select count(*) as calls,count(*) filter(where status='SUCCEEDED') as success,case"
                    + " when count(*) filter(where total_tokens is null)=0 then"
                    + " coalesce(sum(total_tokens),0) end as tokens,coalesce(sum(total_tokens),0)"
                    + " as known_tokens,count(*) filter(where total_tokens is null) as"
                    + " usage_missing,avg(duration_ms) as mean_duration_ms,count(*) filter(where"
                    + " usage_reconciliation='PENDING') as usage_pending,count(*) filter(where"
                    + " usage_reconciliation='PARTIAL') as usage_partial,count(*) filter(where"
                    + " usage_reconciliation='EXPIRED') as usage_expired from model.call_log l"
                    + " where tenant_id=? and "
                        + ModelStatistics.SEND_FACT_FILTER,
                actor.tenantId());
    }

    public Map<String, Object> statistics(Object runIds, Actor actor) {
        return new ModelStatistics(db).query(runIds, actor);
    }

    public Map<String, Object> configureQuota(long provider, JsonNode body, Actor actor) {
        actor.requireRole("ADMIN");
        provider(provider, actor);
        String app = body.path("appCode").asText(""), user = body.path("userPrincipal").asText("");
        int tpm = body.path("tpm").asInt(60000),
                rpm = body.path("rpm").asInt(60),
                daily = body.path("dailyTokens").asInt(1000000),
                uc = body.path("userConcurrency").asInt(3),
                ac = body.path("appConcurrency").asInt(32);
        if (tpm < 1
                || rpm < 1
                || daily < 1
                || uc < 1
                || ac < 1
                || app.length() > 64
                || user.length() > 128) throw new IllegalArgumentException("模型配额字段无效");
        db.update(
                "insert into"
                    + " model.quota_config(tenant_id,provider_id,app_code,user_principal,tpm,rpm,daily_tokens,user_concurrency,app_concurrency)"
                    + " values(?,?,?,?,?,?,?,?,?) on"
                    + " conflict(tenant_id,provider_id,app_code,user_principal) do update set"
                    + " tpm=excluded.tpm,rpm=excluded.rpm,daily_tokens=excluded.daily_tokens,user_concurrency=excluded.user_concurrency,app_concurrency=excluded.app_concurrency,updated_at=now()",
                actor.tenantId(),
                provider,
                app,
                user,
                tpm,
                rpm,
                daily,
                uc,
                ac);
        return quota(provider, app, actor);
    }

    public Map<String, Object> quota(long provider, String app, Actor actor) {
        // provider桶仅读其默认配置。用户日额按tenant+principal共享，应用日额按tenant+app共享；
        // 同一共享维度有多条历史配置时取最严值，防止切换应用/供应商绕过限额。
        var rows =
                db.queryForList(
                        "select tpm,rpm,daily_tokens,user_concurrency,app_concurrency from"
                                + " model.quota_config where tenant_id=? and provider_id=?"
                                + " and app_code='' and user_principal=''",
                        actor.tenantId(),
                        provider);
        Map<String, Object> result =
                new LinkedHashMap<>(
                        rows.isEmpty()
                                ? Map.of(
                                        "tpm",
                                        60000,
                                        "rpm",
                                        60,
                                        "daily_tokens",
                                        1000000,
                                        "user_concurrency",
                                        3,
                                        "app_concurrency",
                                        32)
                                : rows.getFirst());
        var application =
                db.queryForMap(
                        "select coalesce(min(daily_tokens),1000000) as daily_tokens,"
                                + " coalesce(min(app_concurrency),32) as app_concurrency"
                                + " from model.quota_config where tenant_id=? and user_principal=''"
                                + " and app_code in ('',?)",
                        actor.tenantId(),
                        app);
        var user =
                db.queryForMap(
                        "select coalesce(min(daily_tokens),1000000) as user_daily_tokens,"
                                + " coalesce(min(user_concurrency),?) as user_concurrency"
                                + " from model.quota_config where tenant_id=? and user_principal=?",
                        result.get("user_concurrency"),
                        actor.tenantId(),
                        actor.principalId());
        result.putAll(application);
        result.putAll(user);
        return result;
    }

    public void circuitState(long provider, Actor actor, String state) {
        db.update(
                "update model.provider set status=case when ?='CLOSED' then 'ENABLED' else"
                    + " 'DEGRADED'"
                    + " end,health=health||jsonb_build_object('circuit',?::text,'lastProbeAt',now()),updated_at=now()"
                    + " where tenant_id=? and id=? and status<>'DISABLED'",
                state,
                state,
                actor.tenantId(),
                provider);
    }

    public boolean probed(long provider, Actor actor, String state, long revision) {
        return db.update(
                        "update model.provider set status=case when ?='CLOSED' then 'ENABLED' else"
                            + " 'DEGRADED'"
                            + " end,health=health||jsonb_build_object('circuit',?::text,'lastProbeAt',now()),updated_at=now()"
                            + " where tenant_id=? and id=? and status<>'DISABLED' and config_rev=?",
                        state,
                        state,
                        actor.tenantId(),
                        provider,
                        revision)
                == 1;
    }

    public List<Map<String, Object>> degradedProviders() {
        return db.queryForList(
                "select tenant_id,id from model.provider where status='DEGRADED' and not deleted"
                        + " order by updated_at limit 100");
    }
}
