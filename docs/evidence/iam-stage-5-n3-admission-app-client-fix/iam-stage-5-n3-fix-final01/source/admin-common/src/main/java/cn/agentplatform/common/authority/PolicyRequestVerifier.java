package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Independent, pinned POLICY_MACHINE domain. Never produces an Actor or calls an authority. */
public final class PolicyRequestVerifier {
    public static final String PATH="/internal/iam/integration-policy/resolve";
    public static final String ATTRIBUTE=PolicyRequestVerifier.class.getName()+".proof";
    private final String issuer;
    private final Map<String,RSAKey> keys;
    private final Clock clock;
    public PolicyRequestVerifier(String issuer,List<RSAKey> keys,Clock clock){
        if(issuer==null||issuer.isBlank()||keys==null||keys.isEmpty()||keys.size()>2||clock==null)throw new IllegalArgumentException("POLICY_KEYS_REQUIRED");
        var fixed=new HashMap<String,RSAKey>();
        for(var key:keys){
            if(key==null||key.isPrivate()||key.getKeyID()==null||key.getKeyID().isBlank()||key.size()<2048
                ||fixed.putIfAbsent(key.getKeyID(),key)!=null)throw new IllegalArgumentException("POLICY_PUBLIC_KEY_INVALID");
        }
        this.issuer=issuer;this.keys=Map.copyOf(fixed);this.clock=clock;
    }
    public Proof verify(HttpServletRequest request,byte[] body){
        try{
            exact(request);
            if(body==null||body.length==0||body.length>8192)throw rejected();
            var headers=Collections.list(request.getHeaders("Authorization"));
            if(headers.size()!=1||!headers.getFirst().startsWith("Bearer "))throw rejected();
            String token=headers.getFirst().substring(7);
            if(token.length()>16384||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw rejected();
            var header=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
            var key=keys.get(AuthorityProtocol.text(header,"kid"));if(key==null)throw rejected();
            // Nimbus 9.37.3 verifies bytes; the existing strict verifier separately checks JSON,
            // type/use/audience/issuer and NumericDates. Local failures never become remote 503s.
            var claims=AuthorityResponseVerifier.verifyPinned(token,"agent-policy-request+jwt","policy-query","app-policy-resolver",30,key,issuer,clock).claims();
            AuthorityProtocol.same("iam-policy-resolver",AuthorityProtocol.text(claims,"sub"));
            AuthorityProtocol.same("iam-policy-resolver",AuthorityProtocol.text(claims,"caller"));
            AuthorityProtocol.same("app-policy-resolver",AuthorityProtocol.text(claims,"target"));
            AuthorityProtocol.same("integration.policy.resolve",AuthorityProtocol.text(claims,"action"));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            AuthorityProtocol.same(hash,AuthorityProtocol.text(claims,"bodySha256"));
            var json=AuthorityResponseVerifier.object(body);
            AuthorityProtocol.same(AuthorityProtocol.text(json,"nonce"),AuthorityProtocol.text(claims,"nonce"));
            return new Proof(this,request,body,AuthorityProtocol.time(claims,"exp"));
        }catch(Exception denied){throw rejected();}
    }
    public void require(HttpServletRequest request,Proof proof){
        exact(request);if(proof==null||proof.owner!=this||proof.request!=request||!proof.expires.isAfter(clock.instant()))throw rejected();
    }
    private static void exact(HttpServletRequest request){
        if(request==null||!"POST".equals(request.getMethod())||!PATH.equals(request.getServletPath())
            ||request.getQueryString()!=null||request.getDispatcherType()!=jakarta.servlet.DispatcherType.REQUEST)throw rejected();
    }
    private static PlatformException rejected(){return new PlatformException(401,"POLICY_REQUEST_REJECTED","策略请求验证失败");}
    /** Immutable original bytes are the sole controller input. Copying this object to another
     * request or another verifier cannot create an ingress capability. No public constructor. */
    public static final class Proof {
        private final PolicyRequestVerifier owner;private final HttpServletRequest request;
        private final byte[] bytes;private final Instant expires;
        private Proof(PolicyRequestVerifier owner,HttpServletRequest request,byte[] bytes,Instant expires){this.owner=owner;this.request=request;this.bytes=bytes.clone();this.expires=expires;}
        public byte[] body(HttpServletRequest request){owner.require(request,this);return bytes.clone();}
    }
}
