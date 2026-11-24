package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;

/** 身份票据使用的评测候选标识。 */
public final class EvaluationCandidateBinding {
    public static final String ROUTE = "/admin/evaluations";
    public static final String PRECHECK_TEMPLATE = "/admin/apps/{code}/publish/precheck";
    public static final String PRECHECK_ACTION = "proxy.app.post.AppController.precheck";
    public static final String EVIDENCE_ACTION = "evaluation.accepted.read";
    public static final String KIND = "evaluation-candidate";
    public static final String REPORT_TEMPLATE = "/admin/evaluations/{id}";
    public static final String RESUME_TEMPLATE = "/admin/evaluations/{id}/resume";
    public static final String ACCESS_TEMPLATE = "/internal/evaluations/access/{code}";

    private EvaluationCandidateBinding() {}

    public static ResourceBinding from(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new PlatformException(400, "INVALID_INPUT", "评测请求必须为 JSON 对象");
        }
        JsonNode revision = body.path("draftRev");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong()) {
            throw new PlatformException(400, "INVALID_INPUT", "候选修订无效");
        }
        return of(body.path("appCode").textValue(), revision.longValue(), body.path("candidateSha256").textValue());
    }

    public static ResourceBinding of(String code, long revision, String digest) {
        MetadataSelectors.code(code);
        if (revision < 1 || digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new PlatformException(400, "INVALID_INPUT", "候选修订或摘要无效");
        }
        return new ResourceBinding(KIND, code + "@" + revision, digest);
    }

    public static ResourceBinding publication(String code) {
        MetadataSelectors.code(code);
        return new ResourceBinding("evaluation-publication", code,
                Digest.sha256("evaluation-publication.v1:" + code));
    }

    public static VerifiedAuthority requirePublication(String code, String caller, String target, String action) {
        return requirePortal(caller, target, action, publication(code));
    }

    public static String portalTemplate(String method, String path) {
        if (ROUTE.equals(path) && ("POST".equals(method) || "GET".equals(method))) return ROUTE;
        if ("GET".equals(method) && path.matches(ROUTE + "/er_[a-f0-9-]{36}")) return REPORT_TEMPLATE;
        if ("POST".equals(method) && path.matches(ROUTE + "/er_[a-f0-9-]{36}/resume")) return RESUME_TEMPLATE;
        return null;
    }

    public static ResourceBinding report(String id) {
        if (!id.matches("er_[a-f0-9-]{36}")) throw AuthorityProtocol.denied();
        return new ResourceBinding("evaluation-report", id, Digest.sha256("evaluation-report.v1:" + id));
    }

    public static ResourceBinding reports() {
        return new ResourceBinding("evaluation-reports", "own", Digest.sha256("evaluation-reports.v1:own"));
    }

    public static VerifiedAuthority requirePortal(String caller, String target, String action, ResourceBinding expected) {
        var current = AuthorityContext.current();
        if (!caller.equals(current.caller()) || !new ServiceCall(target, action, expected).equals(current.call())
                || current.operation() == null || !"PORTAL".equals(current.operation().originKind())
                || !expected.equals(current.operation().resource())) {
            throw AuthorityProtocol.denied();
        }
        return AuthorityContext.requireFor(current.actor(), expected);
    }
}
