package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.TraceStore;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * SEM-05.1：语义回执的受控读口。
 *
 * <p>只返回本次运行自己的调用回执：行归属由 {@link TraceStore#semanticResults} 用「运行公开 id +
 * 租户 + 主体」解析，再受 {@code engine.run_authority} 保护——被保护的运行一律拒绝，撤权优先于
 * 历史快照。回执本身只做脱敏投影：版本四元组、事实、证据与时效，以及 {@code valid}；不含
 * attempt/节点主键、凭据或管理路径。
 *
 * <p>被拒的投影单独放在 {@code rejected} 里，与 {@code results} 分开呈现——把 {@code valid=false}
 * 的标记混进成功语义结果，等于让"没取到证据"看起来像"证据齐全"。
 */
@RestController
public final class SemanticResultController {
    private final TraceStore store;

    public SemanticResultController(TraceStore store) {
        this.store = store;
    }

    @GetMapping("/v1/runs/{id}/semantic-results")
    public Object semanticResults(@PathVariable String id) {
        var items = store.semanticResults(id, Actor.current());
        var results = new ArrayList<Map<String, Object>>();
        var rejected = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            if (Boolean.TRUE.equals(item.get("valid"))) results.add(item);
            else rejected.add(item);
        }
        var body = new LinkedHashMap<String, Object>();
        // 这是读口包装，不是回执本体：版本口径以 results[].record.schemaVersion 为准。
        body.put("runId", id);
        body.put("count", results.size());
        body.put("results", results);
        body.put("rejected", rejected);
        return ApiResponse.ok(body);
    }
}
