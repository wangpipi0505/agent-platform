package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.AppDigest;
import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.app.domain.PublishGate;
import cn.agentplatform.app.domain.PublishWaiver;
import cn.agentplatform.app.infra.AppPublicationStore;
import cn.agentplatform.app.infra.ReleasePins;
import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.SchemaDialect;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 发布预检与提交（MNT-06 发布子步，2026-11-03）：从 {@link AppService} 原样搬出的发布簇。
 *
 * <p>搬迁只改依赖方向，不改语义：发布请求形状、幂等号、候选/评测摘要、环境确认、版本只读、
 * 本地事务边界与"提交后远端 pin"的边界逐字保持；{@link AppService} 保留同名方法作为兼容门面。
 *
 * <p>它不持有 HTTP 客户端、不自己建事务模板：远端调用与写事务都经 {@link Port} 取用调用方的
 * 那一份（同一个 {@link JdbcTemplate}、同一个 {@link TransactionTemplate}），因此提交仍在原来那个
 * 事务里，不会因为方法搬家出现自调用失效或双重提交；pin 仍在事务提交之后调用一次。
 */
public class AppPublicationService {

    private static final Logger log = LoggerFactory.getLogger(AppPublicationService.class);

    private static final String WAIVER_BY_ID =
            "select * from app.publish_waiver where tenant_id=? and app_code=? and id=?";

    private static final String WAIVER_ACTIVE =
            "select * from app.publish_waiver where tenant_id=? and app_code=? and draft_rev=?"
                    + " and candidate_sha256=? and revoked_at is null and expires_at>now()"
                    + " order by expires_at desc limit 1";

    /**
     * 发布簇对应用服务的窄依赖：只在调用时读取，因此调用方后续用 setter 改的发布目标/门禁模式/
     * pin 开关都对这里的下一次调用生效，与搬迁前读字段的行为一致。
     */
    public interface Port {
        JdbcTemplate db();

        TransactionTemplate tx();

        String tenantId();

        String engineUrl();

        String modelUrl();

        String monitorUrl();

        String skillUrl();

        String gateMode();

        boolean waiverEnabled();

        String publicationTarget();

        boolean pinRecordEnabled();

        String pinEnvironment();

        ReleasePins pins();

        AppPublicationStore store();

        Map<String, Object> load(String code);

        Map<String, Object> loadForUpdate(String code);

        Map<String, Object> templateConfig(Map<String, Object> config);

        List<Map<String, Object>> frozenModels(Map<String, Object> app, long rev, String hash);

        void attachModels(Map<String, Object> snapshot, List<Map<String, Object>> refs);

        void collectModelIds(Object value, Set<Long> ids);

        void assertCapabilitySource(Map<String, Object> app);

        Map<String, Object> remote(String base, String path);

        Map<String, Object> remotePost(String base, String path, Map<String, Object> body);

        void log(String code, String action, String from, String to, Object detail);
    }

    private final Port port;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public AppPublicationService(Port port) {
        this.port = port;
        this.db = port.db();
        this.tx = port.tx();
    }

    public Map<String, Object> precheck(String code) {
        var app = port.load(code);
        port.assertCapabilitySource(app);
        var config = AppService.object(app.get("config"));
        var definition = AppService.object(app.get("definition"));
        String hash = AppService.sha(app);
        long rev = ((Number) app.get("draftRev")).longValue();
        var checks = new ArrayList<Map<String, Object>>();
        var models = new ArrayList<Map<String, Object>>();
        check(
                checks,
                "结构",
                () -> {
                    if (app.get("type").equals("FLOW")) {
                        var errors = AppRules.graphErrors(definition);
                        if (!errors.isEmpty())
                            throw new IllegalArgumentException(String.join("; ", errors));
                        var engineCheck =
                                port.remotePost(
                                        port.engineUrl(),
                                        "/internal/graphs/validate",
                                        Map.of("definition", definition));
                        if (!Boolean.TRUE.equals(engineCheck.get("valid")))
                            throw new IllegalArgumentException(
                                    Json.write(engineCheck.get("errors")));
                    }
                });
        check(checks, "参数", () -> parameterChecks(app, config, definition));
        check(checks, "类型与无效引用", () -> typeChecks(config, definition));
        // A3-1（app V122）发布预检扩展：manifest 引用核验（接口稿 §3.5）。结果进既有 checks 结构，
        // 失效引用在报告落库后以 409 MANIFEST_REF_BLOCKED 阻断并指明对象。
        var manifestBlock = manifestChecks(checks, code);
        check(
                checks,
                "引用有效性",
                () -> {
                    models.addAll(port.frozenModels(app, rev, hash));
                    for (var ref : models) {
                        port.remotePost(
                                port.modelUrl(),
                                "/internal/model-bindings/validate",
                                AppService.object(ref.get("modelBindingRef")));
                        var entry = AppService.object(ref.get("entry"));
                        if (!skillRefs(config, definition).isEmpty()
                                && !((Collection<?>) entry.getOrDefault("capabilities", List.of()))
                                        .contains("tools"))
                            throw new IllegalArgumentException("模型不支持工具调用");
                    }
                    referenceChecks(config, definition, false);
                });
        check(checks, "守卫声明", () -> referenceChecks(config, definition, true));
        // 门禁在其余检查之后评估：即使阻断，也先落一份完整检查快照，供 ADMIN 判断是否值得豁免。
        PublishGate gate = publishGate();
        Map<String, Object> eval = null;
        var evalFailure = new String[1];
        // OFF 仅关闭质量阈值；所有目标仍需同报告的可信环境证据，故始终读取 monitor。
        {
            try {
                eval =
                        port.remote(
                                port.monitorUrl(),
                                "/internal/evaluations/accepted?appCode="
                                        + code
                                        + "&draftRev="
                                        + rev
                                        + "&candidateSha256="
                                        + hash
                                        + "&semanticDigest="
                                        + URLEncoder.encode(AppDigest.semantic(AppService.candidate(app)), StandardCharsets.UTF_8));
            } catch (PlatformException ex) {
                evalFailure[0] = Objects.toString(ex.getMessage(), "评测依赖不可用");
            }
        }
        String semanticDigest = AppDigest.semantic(AppService.candidate(app));
        var sourceEvidence = eval != null && eval.get("publicationEvidence") instanceof Map<?, ?>
                ? AppService.object(eval.get("publicationEvidence")) : Map.<String, Object>of();
        boolean evidenceValid = validPublicationEvidence(sourceEvidence, code, rev, hash, semanticDigest);
        String environment = evidenceValid ? Objects.toString(sourceEvidence.get("environment"), "UNKNOWN") : "UNKNOWN";
        boolean environmentAllowed = evidenceValid && ("REAL".equals(environment)
                || "NON_PRODUCTION".equals(port.publicationTarget()) && "DEMO".equals(environment));
        if (eval != null) {
            eval = new LinkedHashMap<>(eval);
            eval.put("accepted", evidenceValid && Boolean.TRUE.equals(sourceEvidence.get("qualityAccepted")));
            eval.put("evalReportId", evidenceValid ? sourceEvidence.get("evalReportId") : "");
            eval.put("matchedBy", hash.equals(sourceEvidence.get("candidateSha256")) ? "SHA256" : "SEMANTIC_DIGEST");
        }
        boolean accepted = eval != null && Boolean.TRUE.equals(eval.get("accepted"));
        // 语义摘要命中说明候选结构没变、只是描述类字段变了：证据可复用，但必须在检查快照里留下痕迹，
        // 否则事后无法分辨"这次是重新评测通过"还是"复用了旧报告"。
        String matchedBy = eval == null ? null : Objects.toString(eval.get("matchedBy"), null);
        // 依赖故障不允许用豁免绕过：拿不到候选状态时无法确认风险范围，只能阻断或告警。
        PublishWaiver waiver = null;
        if (port.waiverEnabled() && gate != PublishGate.OFF && !accepted && evalFailure[0] == null)
            waiver = activeWaiver(code, rev, hash);
        String waiverId = waiver == null ? null : waiver.id();
        var gateCheck = new LinkedHashMap<String, Object>();
        gateCheck.put("group", "评测门禁");
        String gateResult;
        String gateDetail = null;
        if (evalFailure[0] != null) {
            gateResult = "ERROR";
            gateDetail = evalFailure[0];
        } else if (accepted) {
            gateResult = "PASS";
            if ("SEMANTIC_DIGEST".equals(matchedBy)) {
                gateDetail =
                        "语义摘要一致：候选仅展示性字段变化，复用评测报告 "
                                + Objects.toString(eval.get("evalReportId"), "")
                                + "（matchedBy=SEMANTIC_DIGEST）";
                gateCheck.put("evidence", "SEMANTIC_DIGEST");
            }
        } else if (gate == PublishGate.OFF) {
            gateResult = "SKIPPED";
            gateDetail = "门禁已关闭（gate-mode=OFF），不评估评测结论";
        } else if (waiver != null) {
            gateResult = "WAIVED";
            gateDetail = "ADMIN 显式豁免：" + waiver.reason();
            gateCheck.put("waiverId", waiver.id());
            gateCheck.put("expiresAt", waiver.expiresAt().toString());
        } else if (gate == PublishGate.ADVISORY) {
            gateResult = "ADVISORY";
            gateDetail = "缺少满足安全100%、通过率及无新增回归的候选报告";
        } else {
            gateResult = "BLOCKED";
            gateDetail = "缺少满足安全100%、通过率及无新增回归的候选报告";
        }
        gateCheck.put("result", gateResult);
        if (gateDetail != null) gateCheck.put("detail", gateDetail);
        checks.add(gateCheck);
        var environmentCheck = new LinkedHashMap<String, Object>();
        environmentCheck.put("group", "评测环境");
        environmentCheck.put("result", environmentAllowed ? "PASS" : "BLOCKED");
        environmentCheck.put("schemaVersion", 1);
        environmentCheck.put("publicationTarget", port.publicationTarget());
        environmentCheck.put("candidateSha256", hash);
        environmentCheck.put("semanticDigest", semanticDigest);
        environmentCheck.put("publicationEvidence", sourceEvidence);
        environmentCheck.put("detail", environmentAllowed ? "平台参与者自身模式及数据集身份已冻结；外部语义与业务效果不在本门范围"
                : "演示或未知来源评测不能用于生产发布；请在目标环境新建报告重新评测，普通豁免无效");
        checks.add(environmentCheck);
        var warnings = new ArrayList<String>();
        if (environmentAllowed && "DEMO".equals(environment))
            warnings.add("NON_PRODUCTION：本次仅允许演示发布，提交必须显式确认本预检与报告；不能作为生产来源证明");
        if ("ADVISORY".equals(gateResult))
            warnings.add("评测门禁：" + gateDetail + "（ADVISORY 仅告警，发布风险由发布人承担）");
        if ("WAIVED".equals(gateResult))
            warnings.add("评测门禁：本次发布使用 ADMIN 豁免 " + waiverId + "，到期 " + waiver.expiresAt());
        if ("SKIPPED".equals(gateResult)) warnings.add("评测门禁：已由 gate-mode=OFF 关闭，发布不带评测结论");
        if ("PASS".equals(gateResult) && "SEMANTIC_DIGEST".equals(matchedBy))
            warnings.add("评测门禁：本次复用旧评测报告（仅描述字段变化），报告编号 " + Objects.toString(eval.get("evalReportId"), ""));
        String id = "pc_" + UUID.randomUUID();
        // 被阻断的预检同样落审计行：事后可还原是谁、在什么模式、凭哪次豁免尝试过发布。
        boolean passed =
                checks.stream()
                        .allMatch(
                                c ->
                                        Set.of("PASS", "WAIVED", "ADVISORY", "SKIPPED", "N/A")
                                                .contains(c.get("result")));
        String evalId = eval == null ? "" : Objects.toString(eval.get("evalReportId"), "");
        db.update(
                "insert into"
                    + " app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at,model_refs,gate_mode,waiver_id)"
                    + " values(?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?)",
                port.tenantId(),
                id,
                app.get("draftId"),
                code,
                rev,
                hash,
                evalId,
                Json.write(checks),
                passed,
                java.sql.Timestamp.from(Instant.now().plusSeconds(900)),
                Json.write(models),
                gate.name(),
                waiverId);
        // 依赖故障在任何模式都拦截：不确定候选状态时的"放宽"等于没有门禁，且豁免不得代替可用性判断。
        if (!environmentAllowed)
            throw AppService.error(409, "EVALUATION_ENVIRONMENT_BLOCKED", "演示或未知来源评测不能用于当前发布目标；请重新评测，普通豁免无效");
        if ("ERROR".equals(gateResult) || (gate.blocking() && "BLOCKED".equals(gateResult)))
            throw AppService.error(409, "EVAL_GATE_BLOCKED", gateDetail);
        // manifest 引用失效在报告落库后阻断：检查快照已进 publish_report，阻断清单指明失效对象。
        if (manifestBlock != null)
            throw AppService.error(409, "MANIFEST_REF_BLOCKED",
                "manifest 引用失效，发布被阻断（manifestRevision=" + manifestBlock.manifestRevision()
                        + "）: " + String.join("; ", manifestBlock.refs()));
        var result = new LinkedHashMap<String, Object>();
        result.put("precheckId", id);
        result.put("draftRev", rev);
        result.put("candidateSha256", hash);
        result.put("evalReportId", evalId);
        // 本次预检的选择上下文单独返回；不把 matchedBy 回写到历史评测报告。
        if (accepted && eval != null && matchedBy != null && Set.of("SHA256", "SEMANTIC_DIGEST").contains(matchedBy)) {
            var binding = new LinkedHashMap<String, Object>();
            binding.put("matchedBy", matchedBy);
            binding.put("evalReportId", evalId);
            binding.put("draftRev", rev);
            binding.put("candidateSha256", hash);
            binding.put("evaluatedCandidateSha256", eval.get("candidateSha256"));
            result.put("evaluationBinding", binding);
        }
        result.put("passed", passed);
        result.put("result", passed ? "PASS" : "BLOCKED");
        result.put("gateMode", gate.name());
        result.put("waiverId", waiverId);
        result.put("publicationTarget", port.publicationTarget());
        result.put("evaluationEnvironment", environment);
        result.put("environmentConfirmationRequired", "DEMO".equals(environment));
        result.put("environmentEvidenceSha256", sourceEvidence.get("evidenceSha256"));
        result.put("report", Map.of("checks", checks, "warnings", warnings));
        return result;
    }

    /** manifest 引用失效的阻断清单（包级可见：同包单测直接覆盖，业务调用点只在 precheck）。 */
    record ManifestBlock(List<String> refs, long manifestRevision) {}

    /**
     * A3-1（app V122）发布预检扩展：manifest 引用核验（接口稿 §3.5）。应用有 manifest 头修订时，
     * 规则引用（code@version）在本服务库内核验——主档 ENABLED 且版本 PUBLISHED，失效进入阻断清单
     * 并指明对象；技能（guideRefs/toolGrants）与插件引用的目录在 skill-registry，app-management
     * 本地没有技能/插件目录，跨服务核验不在本卡范围（A3-1 卡面口径），预检记 N/A 留痕不阻断——
     * 运行端受理按钉住版本装载并逐项定位失败对象（主方案 §6.8）。应用没有 manifest 时整组
     * SKIPPED（存量发布口径不变）。检查条目照既有 checks 形状（group/result/detail）。
     *
     * <p>返回失效清单（无失效返回 null）：阻断抛出延迟到预检报告落库之后，保证被阻断的预检
     * 同样留下完整检查快照（与评测门禁同纪律）。
     */
    ManifestBlock manifestChecks(List<Map<String, Object>> checks, String code) {
        var heads = db.queryForList(
                "select revision, manifest::text from app.workspace_manifest"
                        + " where tenant_id=? and app_code=? order by revision desc limit 1",
                port.tenantId(),
                code);
        if (heads.isEmpty()) {
            var skipped = new LinkedHashMap<String, Object>();
            skipped.put("group", "manifest 引用");
            skipped.put("result", "SKIPPED");
            skipped.put("detail", "应用没有工作区 manifest，跳过 manifest 引用核验（存量发布口径不变）");
            checks.add(skipped);
            return null;
        }
        long revision = ((Number) heads.getFirst().get("revision")).longValue();
        var manifest = Json.read(String.valueOf(heads.getFirst().get("manifest")), Map.class);
        var blocked = new ArrayList<String>();
        // 规则引用（可选键）：本服务库内核验（V123 rule_asset/rule_asset_version）。
        var ruleEntry = new LinkedHashMap<String, Object>();
        ruleEntry.put("group", "manifest 规则引用");
        ruleEntry.put("manifestRevision", revision);
        if (manifest.get("rules") instanceof Collection<?> rules && !rules.isEmpty()) {
            for (Object raw : rules) {
                checkRuleRef(blocked, String.valueOf(raw));
            }
            ruleEntry.put(
                    "result", blocked.isEmpty() ? "PASS" : "BLOCKED");
            ruleEntry.put(
                    "detail",
                    blocked.isEmpty()
                            ? "已核验 " + rules.size() + " 条规则引用（主档 ENABLED 且版本 PUBLISHED）"
                            : "失效规则引用: " + String.join("; ", blocked));
        } else {
            ruleEntry.put("result", "SKIPPED");
            ruleEntry.put("detail", "manifest 未引用规则");
        }
        checks.add(ruleEntry);
        // 技能与插件引用：目录在 skill-registry（跨服务），本卡核验不了 → N/A，如实留痕不阻断。
        var skillEntry = new LinkedHashMap<String, Object>();
        skillEntry.put("group", "manifest 技能引用");
        skillEntry.put("result", "N/A");
        skillEntry.put("manifestRevision", revision);
        skillEntry.put(
                "detail",
                "guideRefs/toolGrants 的技能版本存在性属 skill-registry 跨服务核验（接口稿 §3.5），"
                        + "app-management 本地无技能目录，A3-1 卡内记 N/A 不阻断；"
                        + "运行端受理按钉住版本装载并逐项定位失败对象（主方案 §6.8）");
        checks.add(skillEntry);
        var pluginEntry = new LinkedHashMap<String, Object>();
        pluginEntry.put("group", "manifest 插件引用");
        pluginEntry.put("result", "N/A");
        pluginEntry.put("manifestRevision", revision);
        pluginEntry.put(
                "detail",
                "插件启用与授权目录在 skill-registry（跨服务），A3-1 卡内记 N/A 不阻断；"
                        + "停用拦截后续调用与受理期装载定位由插件治理链路负责（主方案 §6.2/§6.8）");
        checks.add(pluginEntry);
        return blocked.isEmpty() ? null : new ManifestBlock(List.copyOf(blocked), revision);
    }

    /** 规则引用核验：code@version → 主档存在且 ENABLED、版本存在且 PUBLISHED，失效指明对象。 */
    private void checkRuleRef(List<String> blocked, String ref) {
        int at = ref.lastIndexOf('@');
        if (at <= 0 || at == ref.length() - 1
                || !ref.substring(at + 1).matches("[0-9]+")
                || !ref.substring(0, at).matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            blocked.add("规则引用形状非法（应为 code@version）: " + ref);
            return;
        }
        String ruleCode = ref.substring(0, at);
        int versionNo = Integer.parseInt(ref.substring(at + 1));
        var asset = db.queryForList(
                "select status from app.rule_asset where tenant_id=? and code=?",
                port.tenantId(),
                ruleCode);
        if (asset.isEmpty()) {
            blocked.add("规则不存在: " + ruleCode + "（引用 " + ref + "）");
            return;
        }
        String status = String.valueOf(asset.getFirst().get("status"));
        if (!"ENABLED".equals(status)) {
            blocked.add("规则未启用（status=" + status + "）: " + ruleCode + "（引用 " + ref + "）");
        }
        var version =
                db.queryForList(
                        "select status from app.rule_asset_version"
                                + " where tenant_id=? and rule_code=? and version_no=?",
                        port.tenantId(),
                        ruleCode,
                        versionNo);
        if (version.isEmpty()) {
            blocked.add("规则版本不存在: " + ref);
        } else if (!"PUBLISHED".equals(String.valueOf(version.getFirst().get("status")))) {
            blocked.add("规则版本未发布（status=" + version.getFirst().get("status") + "）: " + ref);
        }
    }

    private boolean validPublicationEvidence(Map<String, Object> evidence, String code, long rev, String hash, String semantic) {
        var node = Json.tree(evidence);
        if (!node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asInt() != 2
                || !"PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY".equals(node.path("scope").asText())
                || !"NOT_ASSESSED".equals(node.path("externalTruth").asText())
                || !node.path("caseSourcesSha256").asText().matches("[0-9a-f]{64}")
                || !port.tenantId().equals(node.path("tenantId").asText()) || !code.equals(node.path("appCode").asText())
                || !node.path("draftRev").isIntegralNumber() || node.path("draftRev").asLong() != rev
                || node.path("evalReportId").asText().isBlank() || !node.path("qualityAccepted").isBoolean()
                || !Set.of("ACCEPTED", "REJECTED").contains(node.path("status").asText())
                || !node.path("datasetSha256").asText().matches("[0-9a-f]{64}")
                || node.path("datasetVersion").asText().isBlank()
                || !Set.of("REAL", "DEMO", "UNKNOWN").contains(node.path("environment").asText())
                || !(hash.equals(node.path("candidateSha256").asText())
                    || semantic != null && semantic.equals(node.path("candidateSemanticDigest").asText()))) return false;
        var unsigned = new LinkedHashMap<>(evidence);
        Object digest = unsigned.remove("evidenceSha256");
        return Digest.sha256(Json.canonical(unsigned)).equals(digest)
                && (Boolean.TRUE.equals(evidence.get("qualityAccepted")) == "ACCEPTED".equals(evidence.get("status")));
    }

    /** 仅消费本预检固化证据；不持发布行锁发 HTTP，也不把当前配置贴到旧报告上。 */
    private Map<String, Object> requirePublicationEnvironment(Map<String, Object> report, Map<String, Object> app,
            String code, long rev, String hash, String precheck, Map<String, Object> body) {
        var checks = Json.read(report.get("checks").toString());
        var matches = java.util.stream.StreamSupport.stream(checks.spliterator(), false)
                .filter(c -> "评测环境".equals(c.path("group").asText())).toList();
        if (matches.size() != 1) throw AppService.error(409, "EVALUATION_ENVIRONMENT_REQUIRED", "旧预检缺少冻结环境证据，请重新评测和预检");
        var check = matches.getFirst();
        var evidence = check.path("publicationEvidence").isObject()
                ? AppService.object(Json.mapper().convertValue(check.path("publicationEvidence"), Map.class)) : Map.<String, Object>of();
        String semantic = AppDigest.semantic(AppService.candidate(app));
        if (!check.path("schemaVersion").isIntegralNumber() || check.path("schemaVersion").asInt() != 1
                || !"PASS".equals(check.path("result").asText()) || !hash.equals(check.path("candidateSha256").asText())
                || !semantic.equals(check.path("semanticDigest").asText())
                || !port.publicationTarget().equals(check.path("publicationTarget").asText())
                || !validPublicationEvidence(evidence, code, rev, hash, semantic)
                || !Objects.equals(report.get("eval_report_id"), evidence.get("evalReportId")))
            throw AppService.error(409, "EVALUATION_ENVIRONMENT_BLOCKED", "环境证据与当前候选或部署目标不一致，请重新预检");
        String environment = Objects.toString(evidence.get("environment"), "UNKNOWN");
        if (!"REAL".equals(environment)) {
            if (!"NON_PRODUCTION".equals(port.publicationTarget()) || !"DEMO".equals(environment))
                throw AppService.error(409, "EVALUATION_ENVIRONMENT_BLOCKED", "演示或未知评测不能用于生产发布");
            var confirmation = Json.tree(body.get("evaluationEnvironmentConfirmation"));
            if (!confirmation.path("confirmed").isBoolean() || !confirmation.path("confirmed").asBoolean()
                    || !precheck.equals(confirmation.path("precheckId").asText())
                    || !Objects.equals(evidence.get("evalReportId"), confirmation.path("evalReportId").asText())
                    || !Objects.equals(evidence.get("evidenceSha256"), confirmation.path("evidenceSha256").asText()))
                throw AppService.error(409, "EVALUATION_ENVIRONMENT_CONFIRMATION_REQUIRED", "非生产演示发布需显式确认当前预检、报告与来源摘要");
        }
        return evidence;
    }

    /** 回滚开关关闭时放宽模式一律降级为 BLOCKING：保留历史数据，但不再接受新的放行。 */
    private PublishGate publishGate() {
        return port.waiverEnabled() ? PublishGate.parse(port.gateMode()) : PublishGate.BLOCKING;
    }

    /**
     * 授予一次显式豁免。豁免不是"跳过检查"，而是把"缺评测报告"这一条从阻断改记为 WAIVED 并绑定候选指纹：
     * 草稿再保存一次（draft_rev 或 candidate_sha256 变化）豁免即自动失效，避免旧豁免被挪用到新候选上。
     */
    public Map<String, Object> createWaiver(String code, Map<String, Object> body) {
        requireWaiverEnabled();
        var app = port.load(code);
        var waiver =
                PublishWaiver.grant(
                        "pw_" + UUID.randomUUID(),
                        port.tenantId(),
                        code,
                        AppService.object(body),
                        Actor.current().principalId(),
                        Instant.now());
        db.update(
                "insert into"
                    + " app.publish_waiver(id,tenant_id,app_code,draft_rev,candidate_sha256,reason,approved_by,approved_at,expires_at)"
                    + " values(?,?,?,?,?,?,?,?,?)",
                waiver.id(),
                waiver.tenantId(),
                waiver.appCode(),
                waiver.draftRev(),
                waiver.candidateSha256(),
                waiver.reason(),
                waiver.approvedBy(),
                java.sql.Timestamp.from(waiver.approvedAt()),
                java.sql.Timestamp.from(waiver.expiresAt()));
        // 豁免是高风险放行：授予人、理由、候选与窗口同时写生命周期日志，审计不必回查业务表。
        port.log(
                code,
                "PUBLISH_WAIVER_GRANT",
                Objects.toString(app.get("status"), ""),
                Objects.toString(app.get("status"), ""),
                Map.of(
                        "waiverId", waiver.id(),
                        "draftRev", waiver.draftRev(),
                        "candidateSha256", waiver.candidateSha256(),
                        "reason", waiver.reason(),
                        "expiresAt", waiver.expiresAt().toString()));
        return waiver.view(Instant.now());
    }

    /** 撤销幂等：重复撤销保留首次 revoked_at/revoked_by，不改写既有审计事实。 */
    public Map<String, Object> revokeWaiver(String code, String waiverId) {
        requireWaiverEnabled();
        var app = port.load(code);
        if (waiverId == null || !waiverId.matches("[A-Za-z0-9_-]{1,64}"))
            throw AppService.error(400, "PUBLISH_WAIVER_INVALID", "豁免标识无效");
        if (waivers(WAIVER_BY_ID, port.tenantId(), code, waiverId).isEmpty())
            throw AppService.error(404, "PUBLISH_WAIVER_NOT_FOUND", "发布豁免不存在");
        db.update(
                "update app.publish_waiver set revoked_at=coalesce(revoked_at,now()),"
                        + " revoked_by=coalesce(revoked_by,?) where tenant_id=? and app_code=? and id=?",
                Actor.current().principalId(),
                port.tenantId(),
                code,
                waiverId);
        var revoked = waivers(WAIVER_BY_ID, port.tenantId(), code, waiverId).getFirst();
        port.log(
                code,
                "PUBLISH_WAIVER_REVOKE",
                Objects.toString(app.get("status"), ""),
                Objects.toString(app.get("status"), ""),
                Map.of("waiverId", waiverId, "reason", revoked.reason()));
        return revoked.view(Instant.now());
    }

    private void requireWaiverEnabled() {
        if (!port.waiverEnabled())
            throw AppService.error(
                    403,
                    "PUBLISH_WAIVER_DISABLED",
                    "发布豁免已被回滚开关 agent.publish.waiver-enabled=false 关闭");
    }

    /** 只认同时命中租户、应用、草稿版本与候选指纹，且未撤销、未过期的豁免。 */
    private PublishWaiver activeWaiver(String code, long rev, String hash) {
        var rows = waivers(WAIVER_ACTIVE, port.tenantId(), code, rev, hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 提交时复核：报告引用的豁免必须仍然有效，防止 15 分钟报告窗口掩盖更短的豁免窗口。 */
    private boolean waiverActive(String waiverId) {
        return !db.queryForList(
                        "select 1 from app.publish_waiver where tenant_id=? and id=?"
                                + " and revoked_at is null and expires_at>now()",
                        port.tenantId(),
                        waiverId)
                .isEmpty();
    }

    private List<PublishWaiver> waivers(String sql, Object... args) {
        return db.query(
                sql,
                (r, n) ->
                        new PublishWaiver(
                                r.getString("id"),
                                r.getString("tenant_id"),
                                r.getString("app_code"),
                                r.getLong("draft_rev"),
                                // char(64) 会右补空格，比较前必须裁剪，否则指纹永远匹配不上。
                                r.getString("candidate_sha256").trim(),
                                r.getString("reason"),
                                r.getString("approved_by"),
                                r.getTimestamp("approved_at").toInstant(),
                                r.getTimestamp("expires_at").toInstant(),
                                r.getTimestamp("revoked_at") == null
                                        ? null
                                        : r.getTimestamp("revoked_at").toInstant()),
                args);
    }

    private void check(List<Map<String, Object>> checks, String group, Runnable check) {
        try {
            check.run();
            checks.add(Map.of("group", group, "result", "PASS"));
        } catch (Exception ex) {
            checks.add(
                    Map.of(
                            "group",
                            group,
                            "result",
                            "ERROR",
                            "detail",
                            Objects.toString(ex.getMessage(), "校验失败")));
        }
    }

    void parameterChecks(
            Map<String, Object> app, Map<String, Object> config, Map<String, Object> definition) {
        if (app.get("type").equals("CHAT") && !config.containsKey("modelBinding"))
            throw new IllegalArgumentException("对话应用缺少modelBinding");
        for (var node : AppService.objects(definition.get("nodes"))) {
            String type = Objects.toString(node.get("type"), "");
            if (type.isBlank()) throw new IllegalArgumentException("节点缺少type");
            if (Set.of("SKILL", "TOOL").contains(type)) {
                if (!node.containsKey("skillRef"))
                    throw new IllegalArgumentException("技能节点缺少skillRef");
                var skill = resolveSkill(AppService.object(node.get("skillRef")));
                AppRules.validateValue(
                        AppService.object(skill.get("inputSchema")),
                        node.getOrDefault("args", Map.of()),
                        Objects.toString(node.get("id")) + ".args",
                        true);
            }
        }
    }

    void typeChecks(Map<String, Object> config, Map<String, Object> definition) {
        Set<String> allowed =
                Set.of(
                        "START",
                        "END",
                        "LLM",
                        "SKILL",
                        "CONDITION",
                        "CODE",
                        "RETRIEVE",
                        "CONFIRM",
                        "AGENT",
                        "ONTOLOGY");
        var ids = new HashSet<String>();
        for (var n : AppService.objects(definition.get("nodes"))) ids.add(Objects.toString(n.get("id"), ""));
        for (var n : AppService.objects(definition.get("nodes"))) {
            if (!allowed.contains(Objects.toString(n.get("type"), "")))
                throw new IllegalArgumentException("未知节点类型: " + n.get("type"));
            for (var mapping : AppService.objects(n.get("inputMappings"))) {
                String source = Objects.toString(mapping.get("sourceNodeId"), "");
                if (!source.isEmpty() && !ids.contains(source))
                    throw new IllegalArgumentException("参数引用不存在节点: " + source);
                if (mapping.containsKey("sourceType")
                        && mapping.containsKey("targetType")
                        && !mapping.get("sourceType").equals(mapping.get("targetType")))
                    throw new IllegalArgumentException("参数映射类型不兼容");
            }
        }
        var referencePattern = java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\.");
        for (var node : AppService.objects(definition.get("nodes"))) {
            var matcher = referencePattern.matcher(Json.write(node));
            while (matcher.find()) {
                String scope = matcher.group(1);
                if (!Set.of("input", "context", "error").contains(scope) && !ids.contains(scope))
                    throw new IllegalArgumentException("变量引用不存在节点或作用域: " + scope);
            }
        }
        cn.agentplatform.common.goal.GoalContract.requireBindings(Json.tree(config));
        validateSchema(config.get("inputSchema"));
        validateSchema(config.get("outputSchema"));
    }

    /**
     * 应用预检的 Schema 方言：先归一再统一判定（{@link SchemaDialect}），聚合全部错误而不是只报首个。
     * 错误保留旧前缀 {@code Schema缺少有效type} 并追加 JSON Pointer，既有调用方/前端按前缀识别，不需要新协议。
     *
     * <p>有意保留的旧约束：根 Schema 仍必须声明 type，且根必须是对象（旧实现要求每个 properties/items
     * 子 Schema 都显式声明 type，本实现只要求根，嵌套缺 type 是合法 JSON Schema）。放宽原因与兼容性证据见
     * docs/evidence/2026-10-27-PLAT-08a-dialect/README.md。
     *
     * <p>包级可见：仅供同包单测直接覆盖预检规则，业务调用点（typeChecks/referenceChecks）不变。
     */
    void validateSchema(Object raw) {
        if (raw == null) return;
        JsonNode schema;
        try {
            schema = Json.tree(raw);
        } catch (RuntimeException ex) {
            // 保留序列化失败的原因而不是静默丢弃；数组/标量根能转换，会在下方 validate 中带 pointer 拒绝。
            throw new IllegalArgumentException("Schema缺少有效type: /: Schema必须为JSON对象", ex);
        }
        var normalized = SchemaDialect.normalize(schema);
        var errors = new ArrayList<>(SchemaDialect.validate(normalized));
        if (!normalized.isObject() || !normalized.has("type"))
            errors.add(
                    "/type: 缺少有效type（object/array/string/integer/number/boolean/null 或 [T,\"null\"]）");
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Schema缺少有效type: " + String.join("; ", errors));
    }

    public List<Map<String, Object>> skillRefs(
            Map<String, Object> config, Map<String, Object> definition) {
        var refs = new ArrayList<Map<String, Object>>(AppService.objects(config.get("skillRefs")));
        for (var node : AppService.objects(definition.get("nodes")))
            if (node.get("skillRef") instanceof Map) refs.add(AppService.object(node.get("skillRef")));
        return refs;
    }

    private Map<String, Object> resolveSkill(Map<String, Object> ref) {
        String code = AppService.text(ref, "code");
        long version = AppService.number(ref, "version");
        var skill = port.remote(port.skillUrl(), "/internal/skills/" + code);
        if ("DISABLED".equals(skill.get("status")))
            throw new IllegalArgumentException("技能已停用: " + code);
        return AppService.objects(skill.get("versions")).stream()
                .filter(
                        v ->
                                v.getOrDefault("versionNo", v.getOrDefault("version", 0))
                                                instanceof Number n
                                        && n.longValue() == version
                                        && Set.of("PUBLISHED", "ARCHIVED")
                                                .contains(v.get("status")))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("技能版本未发布: " + code + "@" + version));
    }

    void referenceChecks(
            Map<String, Object> config, Map<String, Object> definition, boolean guards) {
        var resolvedGoalSkills = new HashMap<String, Map<String, Object>>();
        var enabledGoalSkills = new HashSet<String>();
        for (var ref : skillRefs(config, definition)) {
            String skillCode = AppService.text(ref, "code");
            long version = AppService.number(ref, "version");
            var skill = resolveSkill(ref);
            resolvedGoalSkills.put(skillCode, skill);
            if (!Boolean.FALSE.equals(ref.get("enabled"))) enabledGoalSkills.add(skillCode);
            if (guards) {
                var guard = AppService.object(skill.getOrDefault("guard", Map.of()));
                var declared = AppService.object(config.getOrDefault("guard", Map.of()));
                var needRoles =
                        new HashSet<>((Collection<?>) guard.getOrDefault("roles", List.of()));
                var haveRoles =
                        new HashSet<>((Collection<?>) declared.getOrDefault("roles", List.of()));
                if (!haveRoles.containsAll(needRoles))
                    throw new IllegalArgumentException("应用未声明技能所需角色: " + skillCode);
                var attributes = AppService.object(guard.getOrDefault("attributes", Map.of()));
                var have = AppService.object(declared.getOrDefault("attributes", Map.of()));
                for (var e : attributes.entrySet()) {
                    if (!(e.getValue() instanceof Collection<?> required)
                            || !(have.get(e.getKey()) instanceof Collection<?> actual)
                            || !actual.containsAll(required))
                        throw new IllegalArgumentException("应用未声明技能所需属性: " + e.getKey());
                }
            } else {
                validateSchema(skill.get("inputSchema"));
                validateSchema(skill.get("outputSchema"));
            }
        }
        if (guards) return;
        for (var goal : AppService.objects(config.get("goals"))) {
            var criterion = AppService.object(goal.get("criterion"));
            var readback = AppService.object(criterion.get("readback"));
            String readbackCode = Objects.toString(readback.get("skillCode"), "");
            var readbackSkill = resolvedGoalSkills.get(readbackCode);
            var execution = readbackSkill == null ? Map.<String,Object>of()
                    : AppService.object(readbackSkill.get("execution"));
            if (!enabledGoalSkills.contains(readbackCode) || !Boolean.TRUE.equals(execution.get("readonly"))
                    || !Boolean.FALSE.equals(execution.get("sideEffect")))
                throw new IllegalArgumentException("目标读回必须引用本应用已启用的只读技能: " + readbackCode);
            if (criterion.containsKey("action") && !enabledGoalSkills.contains(
                    Objects.toString(AppService.object(criterion.get("action")).get("skillCode"), "")))
                throw new IllegalArgumentException("目标动作必须引用本应用已启用的技能");
        }
        if (config.get("modelBinding") instanceof Map<?, ?> binding) {
            Object id = binding.get("modelEntryId");
            if (id == null) throw new IllegalArgumentException("模型绑定需显式modelEntryId");
            var model = port.remote(port.modelUrl(), "/internal/model-entries/" + id);
            if (!"ENABLED".equals(AppService.object(model.get("provider")).get("status")))
                throw new IllegalArgumentException("模型供应商不可用");
        }
        var promptRefs = new ArrayList<>(AppService.objects(config.get("promptRefs")));
        for (var node : AppService.objects(definition.get("nodes")))
            if (node.get("promptRef") instanceof Map) {
                var prompt = AppService.object(node.get("promptRef"));
                promptRefs.add(
                        Map.of(
                                "code",
                                prompt.getOrDefault(
                                        "templateCode", prompt.getOrDefault("code", "")),
                                "version",
                                prompt.getOrDefault("version", 0)));
            }
        for (var ref : promptRefs) {
            var rows =
                    db.queryForList(
                            "select status from app.prompt_template_version where tenant_id=? and"
                                    + " template_code=? and version_no=?",
                            port.tenantId(),
                            AppService.text(ref, "code"),
                            AppService.number(ref, "version"));
            if (rows.isEmpty() || !"PUBLISHED".equals(rows.getFirst().get("status")))
                throw new IllegalArgumentException("模板引用未发布");
        }
    }

    public Map<String, Object> commit(String code, Map<String, Object> body) {
        String request = AppService.text(body, "idempotencyRequestNo"),
                precheck = AppService.text(body, "precheckId"),
                hash = AppService.text(body, "candidateSha256");
        long rev = AppService.number(body, "draftRev");
        // 当前禁用策略在事务外复核；事务内只读已持久快照，绝不持应用锁等待HTTP。
        var frozenReports =
                db.queryForList(
                        "select model_refs::text from app.publish_report where tenant_id=? and id=?"
                                + " and app_code=? and draft_rev=? and candidate_sha256=? and"
                                + " passed=true and expires_at>now()",
                        port.tenantId(),
                        precheck,
                        code,
                        rev,
                        hash);
        boolean replay =
                !db.queryForList(
                                "select 1 from app.publish_commit where tenant_id=? and app_code=?"
                                        + " and request_no=?",
                                port.tenantId(),
                                code,
                                request)
                        .isEmpty();
        if (!replay && !frozenReports.isEmpty())
            for (var ref :
                    AppService.objects(
                            Json.read(
                                    frozenReports.getFirst().get("model_refs").toString(),
                                    Object.class)))
                port.remotePost(
                        port.modelUrl(),
                        "/internal/model-bindings/validate",
                        AppService.object(ref.get("modelBindingRef")));
        // 发布事务仅使用预检已固定的报告编号，不在持锁期间进行HTTP；15分钟有效期限制依赖校验结果的使用窗口。
        // 本体绑定是事务提交后的外部副作用（事务内禁止 HTTP）：lambda 只把候选带出事务，
        // 重放分支读已固化快照，普通分支用刚校验过的候选。
        var released = new AtomicReference<Map<String, Object>>();
        var committed = tx.execute(
                s -> {
                    // 所有改稿与发布都锁同一草稿行：复核与固化之间不能插入未经检查的修改。
                    var app = port.loadForUpdate(code);
                    var prior =
                            db.queryForList(
                                    "select version_no,draft_rev,candidate_sha256 from"
                                        + " app.publish_commit where tenant_id=? and app_code=? and"
                                        + " request_no=?",
                                    port.tenantId(),
                                    code,
                                    request);
                    if (!prior.isEmpty()) {
                        var old = prior.getFirst();
                        if (((Number) old.get("draft_rev")).longValue() != rev
                                || !hash.equals(old.get("candidate_sha256")))
                            throw AppService.error(409, "IDEMPOTENCY_CONFLICT", "请求号已用于其他候选");
                        released.set(releasedCandidate(code, ((Number) old.get("version_no")).intValue()));
                        return publication(code, ((Number) old.get("version_no")).intValue(), hash);
                    }
                    port.assertCapabilitySource(app);
                    // 即使旧预检报告尚有效，提交也不能固化不可执行的目标方言。
                    cn.agentplatform.common.goal.GoalContract.requireBindings(Json.tree(AppService.object(app.get("config"))));
                    if (((Number) app.get("draftRev")).longValue() != rev || !AppService.sha(app).equals(hash))
                        throw AppService.error(412, "CANDIDATE_CHANGED", "草稿已变更，请重新预检");
                    var reports =
                            db.queryForList(
                                    "select * from app.publish_report where tenant_id=? and id=?"
                                        + " and app_code=? and draft_rev=? and candidate_sha256=?"
                                        + " and passed=true and expires_at>now()",
                                    port.tenantId(),
                                    precheck,
                                    code,
                                    rev,
                                    hash);
                    if (reports.isEmpty())
                        throw AppService.error(412, "PRECHECK_INVALID", "预检无效、已过期或被阻断，请重新预检");
                    var publicationEvidence = requirePublicationEnvironment(reports.getFirst(), app, code, rev, hash, precheck, body);
                    // 报告有效期 15 分钟，豁免窗口可能更短或已被撤销：提交时重查一次，
                    // 否则撤销/到期后仍能用旧报告发布，撤销语义会被报告窗口吞掉。
                    var waiverRef = reports.getFirst().get("waiver_id");
                    if (waiverRef != null && !waiverActive(waiverRef.toString()))
                        throw AppService.error(409, "EVAL_GATE_BLOCKED", "发布豁免已撤销或过期，请重新预检或重新申请豁免");
                    var ids = new LinkedHashSet<Long>();
                    port.collectModelIds(app.get("config"), ids);
                    port.collectModelIds(app.get("definition"), ids);
                    if (!ids.isEmpty()) {
                        var frozen =
                                db.queryForList(
                                        "select model_refs::text from app.candidate_model_snapshot"
                                            + " where tenant_id=? and app_code=? and draft_rev=?"
                                            + " and candidate_sha256=?",
                                        port.tenantId(),
                                        code,
                                        rev,
                                        hash);
                        if (frozen.isEmpty()
                                || !Json.canonical(
                                                Json.read(
                                                        frozen.getFirst()
                                                                .get("model_refs")
                                                                .toString()))
                                        .equals(
                                                Json.canonical(
                                                        Json.read(
                                                                reports.getFirst()
                                                                        .get("model_refs")
                                                                        .toString()))))
                            throw AppService.error(412, "MODEL_SNAPSHOT_MISMATCH", "候选与预检模型快照不一致，请重新评测预检");
                    }
                    if (!Set.of("DRAFT", "PUBLISHED").contains(app.get("status")))
                        throw AppService.error(409, "APP_STATE", "应用不可发布");
                    released.set(AppService.candidate(app));
                    var existing =
                            db.queryForList(
                                    "select version_no from app.app_version where tenant_id=? and"
                                            + " app_code=? and draft_rev=? and candidate_sha256=?",
                                    port.tenantId(),
                                    code,
                                    rev,
                                    hash);
                    int version;
                    // 内容身份是(应用,修订,摘要)。不同请求号也复用已固化版本，不为并发重复发号。
                    if (existing.isEmpty()) {
                        version =
                                db.queryForObject(
                                        "select coalesce(max(version_no),0)+1 from app.app_version"
                                                + " where tenant_id=? and app_code=?",
                                        Integer.class,
                                        port.tenantId(),
                                        code);
                        var snapshot = AppService.candidate(app);
                        snapshot.put("versionNo", version);
                        snapshot.put("draftRev", rev);
                        snapshot.put("candidateSha256", hash);
                        snapshot.put("appVersionId", code + "@" + version);
                        snapshot.put("interpreterVersion", "1.0");
                        snapshot.put("publicationTarget", port.publicationTarget());
                        snapshot.put("publicationEvidence", publicationEvidence);
                        if ("DEMO".equals(publicationEvidence.get("environment")))
                            snapshot.put("evaluationEnvironmentConfirmation", Map.of("confirmed", true,
                                    "precheckId", precheck, "evalReportId", publicationEvidence.get("evalReportId"),
                                    "evidenceSha256", publicationEvidence.get("evidenceSha256")));
                        port.attachModels(
                                snapshot,
                                AppService.objects(
                                        Json.read(
                                                reports.getFirst().get("model_refs").toString(),
                                                Object.class)));
                        db.update(
                                "insert into"
                                    + " app.app_version(tenant_id,app_id,app_draft_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by)"
                                    + " values(?,?,?,?,?,?,?,?::jsonb,?,?)",
                                port.tenantId(),
                                app.get("id"),
                                app.get("draftId"),
                                code,
                                version,
                                rev,
                                hash,
                                Json.write(snapshot),
                                reports.getFirst().get("eval_report_id"),
                                Actor.current().principalId());
                        for (var ref :
                                skillRefs(AppService.object(app.get("config")), AppService.object(app.get("definition"))))
                            db.update(
                                    "insert into"
                                        + " app.skill_reference(tenant_id,app_code,app_version_no,app_version_id,skill_code,skill_version)"
                                        + " values(?,?,?,(select id from app.app_version where"
                                        + " tenant_id=? and app_code=? and version_no=?),?,?) on"
                                        + " conflict do nothing",
                                    port.tenantId(),
                                    code,
                                    version,
                                    port.tenantId(),
                                    code,
                                    version,
                                    AppService.text(ref, "code"),
                                    AppService.number(ref, "version"));
                    } else {
                        version = ((Number) existing.getFirst().get("version_no")).intValue();
                        var oldSnapshot = Json.read(db.queryForObject("select snapshot::text from app.app_version where tenant_id=? and app_code=? and version_no=?",
                                String.class, port.tenantId(), code, version));
                        var oldEvidence = oldSnapshot.path("publicationEvidence").isObject()
                                ? AppService.object(Json.mapper().convertValue(oldSnapshot.path("publicationEvidence"), Map.class)) : Map.<String, Object>of();
                        if (!port.publicationTarget().equals(oldSnapshot.path("publicationTarget").asText())
                                || !validPublicationEvidence(oldEvidence, code, rev, hash, AppDigest.semantic(AppService.candidate(app))))
                            throw AppService.error(409, "EVALUATION_ENVIRONMENT_REQUIRED", "既有版本缺少当前目标的可信来源，不能借新请求追认；请新建修订重新评测");
                    }
                    // 版本、当前指针、引用与幂等凭据处于同一事务，任一写失败全部回滚。
                    db.update(
                            "update app.application set"
                                + " current_version_no=?,status='PUBLISHED',updated_at=now() where"
                                + " tenant_id=? and code=?",
                            version,
                            port.tenantId(),
                            code);
                    db.update(
                            "insert into"
                                + " app.publish_commit(tenant_id,app_id,app_version_id,app_code,request_no,precheck_id,version_no,draft_rev,candidate_sha256)"
                                + " values(?,?,(select id from app.app_version where tenant_id=?"
                                + " and app_code=? and version_no=?),?,?,?,?,?,?)",
                            port.tenantId(),
                            app.get("id"),
                            port.tenantId(),
                            code,
                            version,
                            code,
                            request,
                            precheck,
                            version,
                            rev,
                            hash);
                    port.log(
                            code,
                            "PUBLISH",
                            app.get("status").toString(),
                            "PUBLISHED",
                            Map.of("versionNo", version, "candidateSha256", hash));
                    return publication(code, version, hash);
                });
        // 绑定失败不回滚已提交版本（fail-open 默认）；结果只并入返回副本，不改事务产出的不可变发布凭据。
        var result = new LinkedHashMap<String, Object>(committed);
        var pin = pin(released.get(), code);
        // T18.3：落库发生在发布事务提交之后、与返回值组装同一处——发布事务内禁止 HTTP，pin 结果
        // 只在提交后才存在；落库自身是单条 UPSERT 自动提交，失败只告警，绝不影响已提交的发布与响应。
        recordPin(code, released.get(), pin, committed.get("versionNo"));
        result.put("pin", pin);
        return result;
    }

    /**
     * 事务提交后的观测写入（T18.3）：复用 {@link ReleasePins} 状态机结果，只读其返回 Map 与候选里的
     * config.ontology 声明，不改客户端本体。fail-closed 时 {@link #pin} 已抛 502，本方法不会被调用，
     * 与"拒绝本次响应"语义一致（版本已落库，重放同一请求号会再次尝试绑定并写观测行）。
     */
    private void recordPin(
            String code, Map<String, Object> candidate, Map<String, Object> pin, Object versionNo) {
        if (!port.pinRecordEnabled()) return;
        try {
            port.store().recordPin(
                    port.tenantId(),
                    code,
                    ((Number) Objects.requireNonNull(versionNo, "发布版本号缺失")).intValue(),
                    Objects.toString(pin.get("state"), ""),
                    ontologyReleaseId(candidate),
                    Objects.toString(pin.get("bindingId"), null),
                    Objects.toString(pin.get("reason"), null),
                    port.pinEnvironment());
        } catch (RuntimeException failure) {
            // 观测不得破坏发布：已提交的版本与响应照常返回；traceId 走 MDC（TraceContextFilter），
            // 日志模式自动带上，这里补记应用与状态供事后补偿。
            log.warn(
                    "pin 结果落库失败（不影响已提交发布）app={} state={} cause={}",
                    code,
                    pin.get("state"),
                    failure.getClass().getSimpleName());
        }
    }

    /** 候选里声明的目标 release（尽力记录，非远端回执）；未声明或为空返回 null。 */
    private static String ontologyReleaseId(Map<String, Object> candidate) {
        if (candidate == null || !(candidate.get("config") instanceof Map<?, ?> config)) return null;
        if (!(config.get("ontology") instanceof Map<?, ?> ontology)) return null;
        Object releaseId = ontology.get("releaseId");
        if (releaseId == null) return null;
        String value = releaseId.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 按应用反查当前 pin 状态（管理面/运维用，T18.3）。应用不存在显式 404；
     * V106 之前的旧发布记录没有 pin 行，返回空 Map 而不是报错或静默造数据。
     */
    public Map<String, Object> pinStatus(String code) {
        port.load(code);
        var rows = port.store().pinRows(port.tenantId(), code);
        if (rows.isEmpty()) return new LinkedHashMap<>();
        var r = rows.getFirst();
        var view = new LinkedHashMap<String, Object>();
        view.put("appCode", code);
        view.put("versionNo", r.get("app_version_no"));
        view.put("state", r.get("state"));
        view.put("releaseId", r.get("release_id"));
        view.put("bindingId", r.get("binding_id"));
        view.put("reason", r.get("reason"));
        view.put("environment", r.get("environment"));
        view.put("lastPinnedReleaseId", r.get("last_pinned_release_id"));
        view.put("lastPinnedBindingId", r.get("last_pinned_binding_id"));
        view.put(
                "lastPinnedAt",
                r.get("last_pinned_at") == null
                        ? null
                        : ((Timestamp) r.get("last_pinned_at")).toInstant().toString());
        view.put(
                "updatedAt",
                r.get("updated_at") == null
                        ? null
                        : ((Timestamp) r.get("updated_at")).toInstant().toString());
        return view;
    }

    /**
     * 事务提交后调用一次绑定；结果只在这里并入返回值。fail-open 时 FAILED 供调用方观测，
     * fail-closed 由客户端抛 502，语义是拒绝本次响应，而事务内的发布已经不可回滚。
     */
    private Map<String, Object> pin(Map<String, Object> candidate, String code) {
        var client = port.pins();
        if (client == null) return Map.of("state", "DISABLED", "reason", "未装配发布绑定客户端");
        return client.pin(Actor.current(), code, candidate == null ? Map.of() : candidate);
    }

    /**
     * 重放时按版本号读回不可变快照：同一请求号可能在草稿被继续修改后重放，绑定必须描述当次发布的
     * 版本内容，而不是重放时刻的草稿。
     */
    private Map<String, Object> releasedCandidate(String code, int version) {
        var rows =
                db.query(
                        "select snapshot::text from app.app_version where tenant_id=? and app_code=?"
                                + " and version_no=?",
                        (r, n) -> {
                            var snapshot =
                                    new LinkedHashMap<String, Object>(
                                            Json.read(r.getString(1), Map.class));
                            snapshot.put("code", code);
                            return AppService.candidate(snapshot);
                        },
                        port.tenantId(),
                        code,
                        version);
        if (rows.isEmpty()) throw AppService.error(404, "VERSION_NOT_FOUND", "版本不存在");
        return rows.getFirst();
    }

    private Map<String, Object> publication(String code, int version, String hash) {
        String base =
                System.getenv()
                        .getOrDefault("AGENT_WEBAPP_URL", "http://127.0.0.1:15174")
                        .replaceAll("/+$", "");
        String gateway =
                System.getenv()
                        .getOrDefault("AGENT_PUBLIC_GATEWAY_URL", "http://127.0.0.1:18080")
                        .replaceAll("/+$", "");
        String snippet =
                "<script src="
                        + Json.write(base + "/assistant.js")
                        + "></script>\n<script>\nAIAssistant.init({endpoint:"
                        + Json.write(gateway)
                        + ",appCode:"
                        + Json.write(code)
                        + ",ticket:async()=>{const response=await"
                        + " fetch('/your-backend/ticket',{method:'POST'});if(!response.ok)throw new"
                        + " Error('ticket exchange failed');const body=await response.json();return"
                        + " body.data.ticket;}});\n"
                        + "</script>";
        return Map.of(
                "versionNo",
                version,
                "sha256",
                hash,
                "appCode",
                code,
                "artifacts",
                Map.of(
                        "webAppUrl",
                        base + "/w/" + code,
                        "apiDocUrl",
                        "/admin/apps/" + code + "/integration",
                        "embedSnippet",
                        snippet,
                        "ticketUrl",
                        "/your-backend/ticket",
                        "ticketInstructions",
                        "宿主后端持有 ApiKey 并向网关换取短期应用票据；浏览器仅接收 ticket，禁止嵌入长期 ApiKey。"));
    }
}
