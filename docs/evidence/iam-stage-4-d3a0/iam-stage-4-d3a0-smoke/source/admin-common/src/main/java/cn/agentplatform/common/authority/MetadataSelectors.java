package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.util.*;

/** A versioned query description, never a revision of the application or its payload. */
public final class MetadataSelectors {
    public static final String KIND="metadata-selector-v1", ACTION="app.published.metadata.read";
    private MetadataSelectors() {}
    public static ResourceBinding published(String tenant,String code) {
        if(tenant==null||tenant.isBlank()||tenant.length()>128)throw new PlatformException(400,"INVALID_TENANT","租户标识无效");
        code(code);
        var descriptor=Map.of("schema","app-published-selector.v1","tenant_id",tenant,"app_code",code,
                "selector","PUBLISHED","projection","app-published-metadata.v1");
        // Canonical JSON array is an unambiguous tuple, including escaped Unicode and delimiters.
        return new ResourceBinding(KIND,Json.canonical(List.of("app-published-selector.v1",tenant,code,"PUBLISHED","app-published-metadata.v1")),Digest.sha256(Json.canonical(descriptor)));
    }
    /** Bidirectional binding prevents even a mis-signed selector ticket from acquiring payload rights. */
    static void requirePurpose(ServiceCall call,String caller,String mode,String source,String origin) {
        boolean queryKind=KIND.equals(call.resource().kind()), queryAction=ACTION.equals(call.action());
        if(!queryKind&&!queryAction)return;
        if(!queryKind||!queryAction||!"app".equals(call.target())||!"gateway".equals(caller)
                ||!"delegated".equals(mode)||!"PORTAL".equals(source)||!"PORTAL".equals(origin))throw AuthorityProtocol.denied();
    }
    public static String code(String code) {
        if(code==null||!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new PlatformException(400,"INVALID_APP_CODE","应用编码无效");
        return code;
    }
}
