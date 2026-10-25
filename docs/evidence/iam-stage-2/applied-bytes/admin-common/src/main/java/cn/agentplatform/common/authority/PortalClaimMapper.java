package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import org.springframework.security.oauth2.jwt.*;
import java.util.*;

/** This adapter's signed client_id/space-separated scope profile; input must already be verified. */
public final class PortalClaimMapper {
    public record Mapped(Actor actor, Set<String> scopes) {}
    private final AuthorityProperties.Portal portal;
    public PortalClaimMapper(AuthorityProperties.Portal portal){this.portal=Objects.requireNonNull(portal);}
    public Mapped map(Jwt jwt){
        var c=jwt.getClaims();
        if(!portal.enabled() || !portal.issuer().equals(text(c.get("iss")))
                || !portal.clientId().equals(text(c.get("client_id"))) || !"user".equals(text(c.get("token_use")))
                || !jwt.getAudience().equals(List.of(portal.audience())))throw invalid();
        String tenant=text(c.get("tenant_id")), subject=text(c.get("sub"));
        Set<String> roles=new HashSet<>(strings(c.get("roles")));roles.retainAll(portal.roleAllowlist());
        roles.removeAll(Set.of("SERVICE","GATEWAY_AUTH"));
        var attrs=attributes(c.get("attributes"));
        // Sensitivity labels remain obligations even when absent from the permission allowlist.
        var mapped=ClaimCeiling.attributes(attrs,portal.attributeValueAllowlist());
        var scopes=new HashSet<>(scopes(c.get("scope")));scopes.retainAll(portal.scopeAllowlist());
        return new Mapped(new Actor(tenant,subject,roles,mapped),Set.copyOf(scopes));
    }
    static String text(Object value){if(!(value instanceof String s)||s.isBlank())throw invalid();return s;}
    static Set<String> strings(Object value){
        if(value==null)return Set.of();
        if(!(value instanceof Collection<?> values))throw invalid();
        var result=new HashSet<String>();for(var item:values)result.add(text(item));return Set.copyOf(result);
    }
    static Set<String> scopes(Object value){
        if(value==null)return Set.of();
        if(!(value instanceof String s))throw invalid();
        if(s.isEmpty())return Set.of();
        if(!s.matches("[A-Za-z0-9:._-]+( [A-Za-z0-9:._-]+)*"))throw invalid();
        return Set.copyOf(Arrays.asList(s.split(" ")));
    }
    static Map<String,Set<String>> attributes(Object value){
        if(value==null)return Map.of();
        if(!(value instanceof Map<?,?> values))throw invalid();
        var result=new HashMap<String,Set<String>>();
        values.forEach((k,v)->{if(v==null)throw invalid();result.put(text(k),strings(v));});return Map.copyOf(result);
    }
    static BadJwtException invalid(){return new BadJwtException("JWT_PROFILE_INVALID");}
}
