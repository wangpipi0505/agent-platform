package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import java.util.*;

/** EX25：复用 action_plan 的授权事实；调用方必须已在原 run/node/call 事务锁内。 */
final class BusinessIdempotency {
    record Binding(String key, String payloadSha, String intentInput, String intent, long version, String contractDigest) {
        Map<String, Object> material() {
            return Map.of("schemaVersion", 1, "key", key, "payloadSha256", payloadSha, "intentInput", intentInput,
                    "intent", intent, "skillVersionId", version, "contractDigest", contractDigest);
        }
    }

    private BusinessIdempotency() {}

    static Binding prepare(TraceStore store, long callId, long runId, String intentInput) {
        if (intentInput == null) return null;
        if (!intentInput.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw error("BUSINESS_INTENT_INPUT_INVALID", "业务意图必须声明为冻结 run.inputs 的顶层字段");
        var row = store.db().queryForMap("select r.tenant_id,r.inputs::text as business_inputs,t.skill_version_id,t.skill_contract_sha256,t.args_digest"
                + " from engine.tool_call t join engine.run r on r.id=t.run_id where t.id=? and r.id=?", callId, runId);
        var value = Json.read(row.get("business_inputs").toString()).path(intentInput);
        if (!value.isTextual() || !value.asText().matches("[A-Za-z0-9._:-]{1,128}"))
            throw error("BUSINESS_INTENT_REQUIRED", "缺少显式业务意图输入；不同业务必须明确使用不同意图标识");
        long version = TraceStore.number(row, "skill_version_id");
        String contract = Objects.toString(row.get("skill_contract_sha256"), "");
        String payload = Objects.toString(row.get("args_digest"), "");
        if (!contract.matches("[a-f0-9]{64}") || !payload.matches("[a-f0-9]{64}"))
            throw error("BUSINESS_INTENT_SCOPE_INVALID", "固定工具合同或参数摘要缺失，不能降级为普通调用幂等");
        String key = Digest.sha256(Json.canonical(List.of("BUSINESS_EXECUTE_V1", row.get("tenant_id"), version, contract, value.asText())));
        lock(store, key);
        // 不反锁其它 run/plan；事务业务锁串行所有新协议 prepare/claim，唯一索引再兜底。
        if (!store.db().queryForList("select 1 from engine.action_plan where business_key=? and business_payload_sha is distinct from ? limit 1", key, payload).isEmpty())
            throw error("BUSINESS_INTENT_PAYLOAD_CONFLICT", "同一业务意图已绑定不同参数，不能复用该意图");
        return new Binding(key, payload, intentInput, value.asText(), version, contract);
    }

    static void attach(TraceStore store, String executeKey, Binding binding) {
        if (binding == null) return;
        int changed = store.db().update("update engine.action_plan set business_key=?,business_payload_sha=? where execute_key=?"
                        + " and (business_key is null or (business_key=? and business_payload_sha=?))",
                binding.key(), binding.payloadSha(), executeKey, binding.key(), binding.payloadSha());
        if (changed != 1) throw error("BUSINESS_INTENT_CONTEXT_CHANGED", "计划业务作用域已变化，执行未获授权");
    }

    static void requireAvailable(TraceStore store, Map<String, Object> call, Map<String, Object> plan) {
        if (plan.get("business_key") == null) return;
        var metadata = TraceStore.json(plan.get("plan")).path("businessIdempotency");
        String input = metadata.path("intentInput").asText("");
        var binding = prepare(store, TraceStore.number(call, "id"), TraceStore.number(call, "run_id"), input);
        if (!binding.key().equals(plan.get("business_key")) || !binding.payloadSha().equals(plan.get("business_payload_sha"))
                || !Json.canonical(binding.material()).equals(Json.canonical(metadata)))
            throw error("BUSINESS_INTENT_CONTEXT_CHANGED", "计划与冻结输入/版本/参数的业务绑定不一致");
        var grants = store.db().queryForList("select id,status,executed_status from engine.action_plan where business_key=? and execute_granted_at is not null", binding.key());
        for (var granted : grants) {
            if (Objects.equals(((Number) granted.get("id")).longValue(), ((Number) plan.get("id")).longValue())) continue;
            boolean succeeded = "EXECUTED".equals(granted.get("status")) && "SUCCEEDED".equals(granted.get("executed_status"));
            throw error(succeeded ? "BUSINESS_INTENT_ALREADY_EXECUTED" : "BUSINESS_INTENT_RESULT_UNKNOWN",
                    succeeded ? "该业务意图已有已确认执行，不再授予副作用" : "该业务意图已有执行授权且结果尚未安全排除副作用，禁止重复执行");
        }
    }

    private static void lock(TraceStore store, String key) {
        // 哈希碰撞仅导致额外串行，不放宽业务唯一约束；不持业务锁进行 HTTP。
        store.db().queryForObject("select pg_advisory_xact_lock(?)", Object.class, Long.parseUnsignedLong(key.substring(0, 16), 16));
    }

    private static PlatformException error(String code, String message) { return new PlatformException(409, code, message); }
}
