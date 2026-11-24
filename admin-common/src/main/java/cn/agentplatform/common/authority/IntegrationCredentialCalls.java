package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;

/** Machine-only app-management calls for the integration ingress. */
public final class IntegrationCredentialCalls {
    public static final String VERIFY = "/internal/integration-credentials/{id}/verify";
    public static final String ORIGINS = "/internal/integration-origins";

    private IntegrationCredentialCalls() {}

    public static ServiceCall verify(long id) {
        if (id <= 0) throw AuthorityProtocol.denied();
        String credential = Long.toString(id);
        var resource = new ResourceBinding("integration-credential", credential,
                Digest.sha256("integration-credential.v1:" + credential));
        return new ServiceCall("app", "integration.credential.verify", resource);
    }

    public static ServiceCall origin(String origin) {
        if (origin == null || origin.length() > 2048) throw AuthorityProtocol.denied();
        String digest = Digest.sha256(origin);
        return new ServiceCall("app", "integration.origin.check",
                new ResourceBinding("integration-origin", digest, digest));
    }
}
