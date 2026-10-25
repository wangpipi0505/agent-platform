package cn.agentplatform.common.authority;

import java.time.Instant;
import java.util.Map;

/** Bounded request data. A caller-supplied digest or deadline never establishes permission. */
public record PortalEventsBinding(String responseId, ResourceBinding resource, long effectiveCursor,
        String observationRequestSha256, Instant hardDeadline) {
    public PortalEventsBinding {
        if (responseId == null || !responseId.matches("[A-Za-z0-9_-]{1,128}")
                || resource == null || effectiveCursor < 0 || hardDeadline == null) {
            throw AuthorityProtocol.denied();
        }
        AuthorityProtocol.same(RunObservationSelectors.status(resource.id(), resource.versionDigest()), resource);
        AuthorityProtocol.hash(observationRequestSha256);
    }

    /** Fixed logical observation schema, separate from the exact authority HTTP body digest. */
    public static String observationRequestSha256(SubjectKey subject, String originAuthenticationId,
            ResourceBinding resource, long cursor) {
        if (subject == null || !bounded(subject.tenantId()) || !bounded(subject.principalId())
                || !bounded(originAuthenticationId) || resource == null || cursor < 0) {
            throw AuthorityProtocol.denied();
        }
        AuthorityProtocol.same(RunObservationSelectors.status(resource.id(), resource.versionDigest()), resource);
        return AuthorityProtocol.digest(Map.of(
                "schema", "portal-run-events-observation.v1",
                "method", "GET",
                "public_route", "/admin/runs/" + resource.id() + "/events",
                "run_id", resource.id(),
                "resource", AuthorityProtocol.resource(resource),
                "source_kind", "PORTAL",
                "subject", subject,
                "origin_authentication_id", originAuthenticationId,
                "effective_cursor", cursor));
    }

    private static boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }
}
