package cn.agentplatform.common.authority;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Fixed service-id -> client-id -> environment reference; never resolves a user-supplied reference. */
public final class WorkloadCredentialResolver {
    private final String client, reference;
    private final Function<String,String> environment;
    public WorkloadCredentialResolver(AuthorityProperties config) { this(config,System::getenv,false); }
    // Package test seam supplies independent ephemeral credentials without reading the host environment.
    WorkloadCredentialResolver(AuthorityProperties config,Function<String,String> environment,boolean testProfile) {
        config.validate(testProfile);this.client=config.authority().clientId();
        this.reference=config.authority().clientSecretRef().substring(4);
        this.environment=Objects.requireNonNull(environment);resolve();
    }
    public String resolve() {
        String secret=environment.apply(reference);
        if(secret==null || secret.isBlank() || secret.indexOf('\r')>=0 || secret.indexOf('\n')>=0)
            throw new IllegalStateException("IAM_CLIENT_CREDENTIAL_REQUIRED");
        return "Basic "+Base64.getEncoder().encodeToString((client+":"+secret).getBytes(StandardCharsets.UTF_8));
    }
    @Override public String toString(){return "WorkloadCredentialResolver[redacted]";}
}
