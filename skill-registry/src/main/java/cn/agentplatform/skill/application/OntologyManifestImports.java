package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * OR-S01 本体manifest导入桥：把 ontorepo 发布管道产出的正式技能manifest
 * （ContractGenerator.formalize 形状，见 ontorepo contracts/p2-supply.md、p4-release.md）
 * 转换为平台技能草稿契约并整批创建。与 OpenAPI 导入同为"导入仅生成可编辑DRAFT"语义，
 * 发布仍走四道门。
 *
 * <p>设计不变量（默认拒绝）：</p>
 * <ul>
 *   <li>只接受正式manifest：ontology_meta.preview 不得为 true——preview 的 endpoint 指向
 *       候选内部执行入口，导入会造成不可发布的草稿或指向非发布路由的调用；</li>
 *   <li>endpoint 必须是发布消费路由形态 /onto/r/{spaceCode}/releases/{releaseId}/ops/{toolCode}，
 *       防止manifest把技能指向任意URL；</li>
 *   <li>adapter_type/invocation_kind/http_method/category 逐一硬校验，与当前适配器能力严格对齐；</li>
 *   <li>guard_attrs 只声明属性名，运行时由 Actor 已验授权的非空集合供给
 *       （路线三：OR-S02 绑定的 dept_scope 等属性），roles 留空不做角色限制；</li>
 *   <li>riskLevel 不从manifest映射：平台按 readonly 旗标确定性推导（READ_ONLY/CONFIRM_REQUIRED），
 *       不让manifest主张绕过；</li>
 *   <li>ontology_meta 进 importSource（编辑不可变），保证草稿可追溯到本体release版本与内容摘要。</li>
 * </ul>
 *
 * <p>整批语义与 OpenAPI 导入一致：先全部构建并校验，再逐个创建；单个创建失败
 * （如技能编码已存在）时此前已创建的草稿保留，调用方按返回错误定位后重试余项。</p>
 */
@Service
public class OntologyManifestImports {
    private static final int MAX_MANIFESTS = 64;

    private final SkillCatalog catalog;

    public OntologyManifestImports(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    /** 本体消费面固定API_KEY；整批写入前证明当前管理员有权使用已配置的authRef。 */
    public Map<String, Object> importManifests(Map<String, Object> body) {
        if (!(body.get("manifests") instanceof List<?> raw) || raw.isEmpty())
            throw new PlatformException(400, "INVALID_MANIFEST", "manifests必须为非空数组");
        if (raw.size() > MAX_MANIFESTS)
            throw new PlatformException(400, "INVALID_MANIFEST", "单次导入至多" + MAX_MANIFESTS + "条manifest");
        String authRef = Objects.toString(body.get("authRef"), "");
        AuthScheme scheme =
                AuthScheme.parse(body.getOrDefault(AuthScheme.FIELD, AuthScheme.API_KEY.name()));
        AuthScheme.checkCombination(scheme, authRef, "HTTP");
        if (scheme != AuthScheme.API_KEY)
            throw new PlatformException(
                    400, AuthScheme.CODE_CONFLICT, "本体manifest导入只支持authScheme=API_KEY");
        // 先检查角色再解析引用；普通HTTP目录校验不证明凭据可用或归属，不能把失败留给发布后出站。
        Actor.current().requireRole("ADMIN");
        try {
            SecretVault.resolve(authRef);
        } catch (PlatformException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw invalid("authRef无效或尚未配置，请选择当前租户已登记的secret_ref");
        }
        var contracts = new ArrayList<Map<String, Object>>();
        var seenCodes = new HashSet<String>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?>))
                throw new PlatformException(400, "INVALID_MANIFEST", "manifest必须为对象");
            @SuppressWarnings("unchecked")
            var manifest = (Map<String, Object>) item;
            var contract = toSkillBody(manifest, authRef);
            if (!seenCodes.add(contract.get("code").toString()))
                throw new PlatformException(
                        400, "INVALID_MANIFEST", "manifest中技能编码重复：" + contract.get("code"));
            contracts.add(contract);
        }
        var drafts = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> contract : contracts) drafts.add(catalog.create(contract));
        return Map.of(
                "drafts",
                drafts,
                "warnings",
                List.of(
                        "导入仅生成可编辑DRAFT；真实连接测试与模型选型验收通过后才能发布",
                        "guard_attrs仅声明属性名；调用主体须持有OR-S02绑定属性（如dept_scope）的非空授权集"));
    }

    /** manifest→技能草稿契约。结构性错误抛400 INVALID_MANIFEST；语义校验交给catalog.create统一收口。 */
    Map<String, Object> toSkillBody(Map<String, Object> manifest, String authRef) {
        require("category", manifest, "ontology_service");
        require("adapter_type", manifest, "HTTP");
        require("invocation_kind", manifest, "SYNC");
        require("http_method", manifest, "POST");
        var meta = object(manifest.get("ontology_meta"));
        if (Boolean.parseBoolean(Objects.toString(meta.get("preview"), "false")) || isBlank(meta.get("releaseId")))
            throw invalid("只接受正式manifest（ontology_meta.preview必须为false且releaseId非空）");
        String toolCode = text(manifest, "tool_code");
        if (!toolCode.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw invalid("tool_code不能直接作为技能编码（须为ASCII字母开头）：" + toolCode);
        String endpoint = text(manifest, "endpoint");
        if (!endpoint.matches("^https?://.+/onto/r/[^/]+/releases/[^/]+/ops/[^/]+$"))
            throw invalid("endpoint必须是发布消费路由 /onto/r/{spaceCode}/releases/{releaseId}/ops/{toolCode}");
        var exec = object(manifest.get("exec_attrs"));
        var execution = new LinkedHashMap<String, Object>();
        for (var pair : Map.<String, String>of(
                "readonly", "readonly_flag",
                "sideEffect", "side_effect",
                "idempotent", "idempotent",
                "cancellable", "cancellable").entrySet()) {
            if (!(exec.get(pair.getValue()) instanceof Boolean flag))
                throw invalid("exec_attrs." + pair.getValue() + "必须为布尔");
            execution.put(pair.getKey(), flag);
        }
        long timeout = number(exec.get("timeout_ms"), "exec_attrs.timeout_ms");
        if (timeout < 100 || timeout > 600000)
            throw invalid("exec_attrs.timeout_ms必须为100至600000（平台上限）");
        var guardAttrs = manifest.get("guard_attrs");
        if (!(guardAttrs instanceof List<?> attrs)
                || attrs.size() > 16
                || attrs.stream().anyMatch(a -> !(a instanceof String s) || s.isBlank()))
            throw invalid("guard_attrs必须为至多16个非空字符串的数组");
        if (!(manifest.get("error_semantics") instanceof List))
            throw invalid("error_semantics必须为数组");
        if (!(manifest.get("return_spec") instanceof Map))
            throw invalid("return_spec必须为对象");
        var body = new LinkedHashMap<String, Object>();
        body.put("code", toolCode);
        body.put("name", bounded(manifest, "name", 128));
        body.put("description", bounded(manifest, "description", 2000));
        body.put("category", "ontology_service");
        body.put("type", "HTTP");
        body.put("endpoint", endpoint);
        body.put("httpMethod", "POST");
        body.put("timeoutMs", timeout);
        body.put("inputSchema", object(manifest.get("input_schema")));
        body.put("outputSchema", object(manifest.get("output_schema")));
        body.put("execution", execution);
        body.put("guard", Map.of("roles", List.of(), "guardAttrs", guardAttrs));
        body.put("errorSemantics", manifest.get("error_semantics"));
        body.put("returnSpec", manifest.get("return_spec"));
        var importSource = new LinkedHashMap<String, Object>();
        importSource.put("kind", "ONTOLOGY");
        importSource.put("toolCode", toolCode);
        for (String field : new String[] {"spaceCode", "releaseId", "version", "contentDigest"})
            importSource.put(field, Objects.toString(meta.get(field), ""));
        body.put("importSource", importSource);
        // 本体协议的鉴权声明必须显式进入冻结契约，不能依赖手工HTTP技能的存量Bearer默认。
        body.put(AuthScheme.FIELD, AuthScheme.API_KEY.name());
        body.put("authRef", authRef);
        return body;
    }

    private void require(String field, Map<String, Object> manifest, String expected) {
        if (!expected.equals(Objects.toString(manifest.get(field), "")))
            throw invalid(field + "必须为" + expected);
    }

    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>))
            throw invalid("manifest字段必须为对象");
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) value;
        return map;
    }

    private String text(Map<String, Object> manifest, String field) {
        var value = manifest.get(field);
        if (!(value instanceof String s) || s.isBlank()) throw invalid(field + "必须为非空字符串");
        return s;
    }

    private String bounded(Map<String, Object> manifest, String field, int maxLength) {
        var value = text(manifest, field);
        if (value.length() > maxLength) throw invalid(field + "长度不能超过" + maxLength);
        return value;
    }

    private long number(Object value, String field) {
        if (!(value instanceof Number n)) throw invalid(field + "必须为数值");
        return n.longValue();
    }

    private boolean isBlank(Object value) {
        return !(value instanceof String s) || s.isBlank();
    }

    private PlatformException invalid(String message) {
        return new PlatformException(400, "INVALID_MANIFEST", message);
    }
}
