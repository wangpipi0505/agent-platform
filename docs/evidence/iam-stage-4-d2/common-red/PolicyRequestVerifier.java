package cn.agentplatform.common.authority;
import cn.agentplatform.common.*;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
public final class PolicyRequestVerifier {
    public static final String PATH="/internal/iam/integration-policy/resolve";
    public static final String ATTRIBUTE=PolicyRequestVerifier.class.getName()+".proof";
    public PolicyRequestVerifier(String issuer,List<RSAKey> keys,Clock clock){}
    public Proof verify(HttpServletRequest request,byte[] body){throw new PlatformException(403,"POLICY_REQUEST_REJECTED","拒绝未验证策略请求");}
    public void require(HttpServletRequest request,Proof proof){throw new PlatformException(403,"POLICY_REQUEST_REJECTED","拒绝未验证策略请求");}
    public static final class Proof {private Proof(){} public byte[] body(HttpServletRequest request){throw new PlatformException(403,"POLICY_REQUEST_REJECTED","拒绝未验证策略请求");}}
}
