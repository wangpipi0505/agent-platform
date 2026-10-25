package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.*;
import java.util.*;

/** A pinned key is retained only inside the short lived response capability, never for ordinary JWTs. */
public final class ResponseMaterialPermit {
    private final String raw,issuer,audience;
    private final RSAKey key;
    private final Clock clock;
    private final JsonNode claims;
    private final ResponseBinding binding;
    private final Actor ceiling;
    ResponseMaterialPermit(String raw,AuthorityResponseVerifier.Verified verified,String issuer,String audience,
                           ResponseBinding binding,VerifiedAuthority opening,String parentResponseId,Clock clock) {
        this.raw=raw;this.key=verified.key();this.issuer=issuer;this.audience=audience;this.clock=clock;
        this.claims=verified.claims().deepCopy();this.binding=binding;
        AuthorityProtocol.same("INTEGRATION",AuthorityProtocol.text(claims,"source_kind"));
        AuthorityProtocol.same(Json.tree(binding),claims.get("binding"));
        AuthorityProtocol.same(opening.decisionId(),AuthorityProtocol.text(claims,"opening_decision_id"));
        AuthorityProtocol.same(opening.authorizationVersion(),AuthorityProtocol.text(claims,"authorization_version"));
        AuthorityProtocol.same(opening.operation().operationId(),AuthorityProtocol.text(claims,"operation_id"));
        AuthorityProtocol.same(opening.call().action(),AuthorityProtocol.text(claims,"action"));
        AuthorityProtocol.same(opening.call().target(),AuthorityProtocol.text(claims,"consumer"));
        AuthorityProtocol.same(opening.caller(),AuthorityProtocol.text(claims,"caller"));
        AuthorityProtocol.text(claims,"producer");AuthorityProtocol.same(parentResponseId,AuthorityProtocol.text(claims,"parent_response_id"));
        ceiling=AuthorityProtocol.actor(claims);
        AuthorityProtocol.same(opening.actor(),ceiling);
        AuthorityProtocol.same(opening.operation().ceilingSha256(),AuthorityProtocol.text(claims,"ceiling_sha256"));
        AuthorityProtocol.same(AuthorityProtocol.ceiling(ceiling),AuthorityProtocol.text(claims,"ceiling_sha256"));
        AuthorityProtocol.text(claims,"material_kind");AuthorityProtocol.text(claims,"material_id");AuthorityProtocol.text(claims,"material_version");
        if(!claims.path("initial_cursor").isIntegralNumber()||claims.path("initial_cursor").longValue()<0)throw AuthorityProtocol.denied();
        if(AuthorityProtocol.time(claims,"exp").isAfter(binding.hardDeadline()))throw AuthorityProtocol.denied();
        verify();
    }
    void verify(){
        if(!binding.hardDeadline().isAfter(clock.instant())||!AuthorityProtocol.time(claims,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();
        var verified=AuthorityResponseVerifier.verifyPinned(raw,"agent-response-material+jwt","response-completion",audience,611,key,issuer,clock);
        AuthorityProtocol.same(claims,verified.claims());
    }
    ResponseBinding binding(){return binding;}
    SubjectKey owner(){return AuthorityProtocol.subject(ceiling);}
    String producer(){return AuthorityProtocol.text(claims,"producer");}
    String consumer(){return AuthorityProtocol.text(claims,"consumer");}
    long initialCursor(){return claims.path("initial_cursor").longValue();}
    void require(MaterialScopeV1 scope,long cursor){
        verify();MaterialAuthorization.validate(scope);
        AuthorityProtocol.same(AuthorityProtocol.subject(ceiling),scope.owner());
        AuthorityProtocol.same(AuthorityProtocol.text(claims,"ceiling_sha256"),scope.initialCeilingSha256());
        AuthorityProtocol.same(AuthorityProtocol.text(claims,"authorization_version"),scope.sourceAuthorizationVersion());
        var ref=scope.material();
        AuthorityProtocol.same(AuthorityProtocol.text(claims,"producer"),ref.producer());
        AuthorityProtocol.same(AuthorityProtocol.text(claims,"material_kind"),ref.kind());
        AuthorityProtocol.same(AuthorityProtocol.text(claims,"material_id"),ref.id());
        String version=AuthorityProtocol.text(claims,"material_version");
        if("sse".equals(binding.kind())){
            if(!ref.version().startsWith(version+":"))throw AuthorityProtocol.denied();
            try{long seq=Long.parseLong(ref.version().substring(version.length()+1));if(seq<=cursor)throw AuthorityProtocol.denied();}
            catch(NumberFormatException e){throw AuthorityProtocol.denied();}
        }else AuthorityProtocol.same(version,ref.version());
        MaterialAuthorization.cover(scope,ceiling);
        if(!scope.requiredDataLabels().containsAll(ceiling.attributes().getOrDefault("dataLabels",Set.of())))throw AuthorityProtocol.denied();
    }
    @Override public String toString(){return "ResponseMaterialPermit[redacted]";}
}
