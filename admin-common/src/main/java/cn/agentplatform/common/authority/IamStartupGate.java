package cn.agentplatform.common.authority;

import org.springframework.core.env.Environment;

/**
 * EX-43 §3.2 local iam startup checks.
 *
 * <p>The gate never opens a socket: the JWKS, token and decision endpoints are touched only by
 * request-time clients, so an authority outage must not stop the process from starting. It surfaces
 * later as 503 {@code IAM_AUTHORITY_UNAVAILABLE} at the first verification.
 */
public final class IamStartupGate {
    public static final String CONFIG_INCOMPLETE = "IAM_CONFIG_INCOMPLETE";
    public static final String CLIENT_SECRET_MISSING = "IAM_CLIENT_SECRET_MISSING";

    private IamStartupGate() {}

    /**
     * Resolves the client-secret reference from local configuration/env sources only, mirroring
     * SecretVault's {@code env:} shape without needing a request Actor: SecretVault's tenant binding
     * check reads the authenticated caller, which does not exist during startup.
     */
    public static void requireLocalClientSecret(Environment environment, AuthorityProperties properties) {
        var reference = properties.authority() == null ? null : properties.authority().clientSecretRef();
        if (localSecret(environment, reference) == null)
            throw new IllegalStateException(CLIENT_SECRET_MISSING + ": " + reference);
    }

    static String localSecret(Environment environment, String reference) {
        if (reference == null || !reference.startsWith("env:")) return null;
        var name = reference.substring("env:".length());
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        var value = environment.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
