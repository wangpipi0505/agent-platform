package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.time.Clock;
import java.util.*;

/** Pure validation only: trusted manifests and atomic payload snapshots remain the owning domain's job. */
public final class MaterialAuthorization {
    private MaterialAuthorization(){}
    public static void require(MaterialScopeV1 scope,VerifiedAuthority current){require(scope,current,Clock.systemUTC());}
    public static void require(MaterialScopeV1 scope,VerifiedAuthority current,Clock clock){
        AuthorityProtocol.requireCurrent(current,clock);validate(scope);
        AuthorityProtocol.same(scope.owner(),AuthorityProtocol.subject(current.actor()));cover(scope,current.actor());
    }
    public static void requireForResponse(MaterialScopeV1 scope,ResponseMaterialAccess access){
        if(access==null)throw AuthorityProtocol.denied();access.require(scope);
    }
    static void cover(MaterialScopeV1 scope,Actor actor){
        if(!actor.roles().containsAll(scope.requiredRoles()))throw AuthorityProtocol.denied();
        scope.requiredEntitlements().forEach((key,values)->{
            if("region".equals(key)){if(!actor.attributes().getOrDefault(key,Set.of()).containsAll(values))throw AuthorityProtocol.denied();}
            else if(Set.of("bizSystemCode","userId").contains(key)){if(values.size()!=1||!values.equals(actor.attributes().get(key)))throw AuthorityProtocol.denied();}
            else throw AuthorityProtocol.denied();
        });
        // dataLabels are obligations, not permission sets. Provider/export checks must retain them.
    }
    public static String scopeDigest(MaterialScopeV1 scope){return AuthorityProtocol.digest(fields(scope));}
    private static Map<String,Object> fields(MaterialScopeV1 s){
        var m=new LinkedHashMap<String,Object>();
        m.put("schemaVersion",s.schemaVersion());m.put("material",s.material());m.put("owner",s.owner());
        m.put("sourceDecisionId",s.sourceDecisionId());m.put("sourceAuthorizationVersion",s.sourceAuthorizationVersion());
        m.put("initialCeilingSha256",s.initialCeilingSha256());m.put("requiredRoles",s.requiredRoles());m.put("requiredEntitlements",s.requiredEntitlements());
        m.put("requiredDataLabels",s.requiredDataLabels());m.put("parentScopeSha256",s.parentScopeSha256());return m;
    }
    static void validate(MaterialScopeV1 s){
        if(s==null||s.schemaVersion()!=1||s.material()==null||s.owner()==null||s.sourceDecisionId()==null||s.sourceDecisionId().isBlank()
                ||s.sourceAuthorizationVersion()==null||s.sourceAuthorizationVersion().isBlank()||s.requiredRoles()==null
                ||s.requiredEntitlements()==null||s.requiredDataLabels()==null||s.parentScopeSha256()==null)throw missing();
        var r=s.material();
        if(r.producer()==null||r.producer().isBlank()||r.kind()==null||r.kind().isBlank()||r.id()==null||r.id().isBlank()||r.version()==null||r.version().isBlank())throw missing();
        try{AuthorityProtocol.hash(r.payloadSha256());AuthorityProtocol.hash(s.initialCeilingSha256());AuthorityProtocol.hash(s.scopeSha256());s.parentScopeSha256().forEach(AuthorityProtocol::hash);
            AuthorityProtocol.same(scopeDigest(s),s.scopeSha256());}
        catch(Exception e){throw missing();}
    }
    static void payload(ResponseMaterial material){
        validate(material.scope());AuthorityProtocol.same(AuthorityProtocol.digest(material.payload()),material.scope().material().payloadSha256());
    }
    private static PlatformException missing(){return new PlatformException(403,"MATERIAL_SCOPE_MISSING_OR_CHANGED","材料范围缺失或已改变");}
}
