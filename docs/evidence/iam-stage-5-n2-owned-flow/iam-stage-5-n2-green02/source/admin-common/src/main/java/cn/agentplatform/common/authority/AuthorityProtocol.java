package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Private adapter profile. These fields are not OAuth grant-type extensions claimed as a standard. */
final class AuthorityProtocol {
    private AuthorityProtocol(){}
    static PlatformException denied(){return new PlatformException(403,"SYS-AUTH-403","当前授权不允许此操作");}
    static PlatformException expired(){return new PlatformException(403,"AUTHORITY_EXPIRED","当前授权已过期");}
    static String text(JsonNode n,String key){var v=n==null?null:n.get(key);if(v==null||!v.isTextual()||v.textValue().isBlank())throw denied();return v.textValue();}
    static Instant time(JsonNode n,String key){
        var v=n==null?null:n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw denied();
        try{Math.multiplyExact(v.longValue(),1000);return Instant.ofEpochSecond(v.longValue());}catch(Exception e){throw denied();}
    }
    static void same(Object expected,Object actual){if(!Objects.equals(expected,actual))throw denied();}
    static void hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw denied();}
    static Set<String> strings(JsonNode n){
        if(n==null||!n.isArray())throw denied();var out=new TreeSet<String>();
        for(var v:n){if(!v.isTextual()||v.textValue().isBlank()||!out.add(v.textValue()))throw denied();}
        return Collections.unmodifiableSet(out);
    }
    static Map<String,Set<String>> attrs(JsonNode n){
        if(n==null||!n.isObject())throw denied();var out=new TreeMap<String,Set<String>>();
        n.fields().forEachRemaining(e->{if(!Set.of("region","dataLabels","bizSystemCode","userId").contains(e.getKey()))throw denied();out.put(e.getKey(),strings(e.getValue()));});
        return Collections.unmodifiableMap(out);
    }
    static Actor actor(JsonNode n){return new Actor(text(n,"tenant_id"),text(n,"sub"),strings(n.get("roles")),attrs(n.get("attributes")));}
    static SubjectKey subject(Actor a){return new SubjectKey(a.tenantId(),a.principalId());}
    static ResourceBinding resource(JsonNode n){
        if(n==null||!n.isObject()||n.size()!=3)throw denied();
        return new ResourceBinding(text(n,"kind"),text(n,"id"),text(n,"version_digest"));
    }
    static Map<String,Object> resource(ResourceBinding r){Objects.requireNonNull(r);return Map.of("kind",r.kind(),"id",r.id(),"version_digest",r.versionDigest());}
    static Map<String,Object> call(ServiceCall c){return Map.of("target",c.target(),"action",c.action(),"resource",resource(c.resource()));}
    static void call(JsonNode n,ServiceCall c){same(c.target(),text(n,"target"));same(c.action(),text(n,"action"));same(c.resource(),resource(n.get("resource")));}
    static String digest(Object value){return Digest.sha256(Json.canonical(sorted(value)));}
    private static Object sorted(Object value){
        if(value instanceof Set<?> s)return s.stream().map(AuthorityProtocol::sorted).sorted(Comparator.comparing(Json::canonical)).toList();
        if(value instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,v)->out.put(k.toString(),sorted(v)));return out;}
        if(value instanceof List<?> l)return l.stream().map(AuthorityProtocol::sorted).toList();
        return value;
    }
    static String ceiling(Actor a){return digest(Map.of("owner",subject(a),"roles",a.roles(),"attributes",a.attributes()));}
    static void within(Actor upper,Actor candidate){
        same(subject(upper),subject(candidate));
        if(!upper.roles().containsAll(candidate.roles())||candidate.roles().stream().anyMatch(Set.of("SERVICE","GATEWAY_AUTH")::contains))throw denied();
        if(!upper.attributes().getOrDefault("region",Set.of()).containsAll(candidate.attributes().getOrDefault("region",Set.of())))throw denied();
        if(!candidate.attributes().getOrDefault("dataLabels",Set.of()).containsAll(upper.attributes().getOrDefault("dataLabels",Set.of())))throw denied();
        for(String k:List.of("bizSystemCode","userId"))if(upper.attributes().containsKey(k)||candidate.attributes().containsKey(k))same(upper.attributes().get(k),candidate.attributes().get(k));
    }
    static OperationBinding operation(JsonNode n,Actor actor,ResourceBinding resource,String grant,Clock clock){
        if(n==null||!n.isObject())throw denied();
        String source=text(n,"source_kind"),origin=text(n,"origin_kind");
        if(!Set.of("PORTAL","INTEGRATION","DELEGATED").contains(source)||!Set.of("PORTAL","INTEGRATION").contains(origin))throw denied();
        if(!source.equals("DELEGATED")&&!source.equals(origin))throw denied();
        var issued=time(n,"issued_at");var expires=time(n,"expires_at");
        if(issued.isAfter(clock.instant())||!expires.isAfter(clock.instant())||!expires.isAfter(issued))throw expired();
        same(grant,text(n,"grant_ref"));same(actor.tenantId(),text(n,"tenant_id"));same(actor.principalId(),text(n,"sub"));
        same(resource,resource(n.get("resource")));String ceiling=text(n,"ceiling_sha256");hash(ceiling);
        return new OperationBinding(text(n,"operation_id"),source,origin,issued,expires,grant,subject(actor),resource,ceiling);
    }
    static void requireCurrent(VerifiedAuthority a,Clock clock){
        if(a==null||a.delegation()==null||a.operation()==null)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少已验操作授权");
        if(!a.expiresAt().isAfter(clock.instant())||!a.delegation().expiresAt().isAfter(clock.instant())||!a.operation().expiresAt().isAfter(clock.instant()))throw expired();
        same(subject(a.actor()),a.delegation().subject());same(a.delegation().value(),a.operation().grantRef());
    }
}
