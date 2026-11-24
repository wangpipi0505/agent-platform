package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;

/** 身份票据使用的评测候选标识。 */
public final class EvaluationCandidateBinding {
    public static final String ROUTE = "/admin/evaluations";

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
        return new ResourceBinding("evaluation-candidate", code + "@" + revision, digest);
    }
}
