package cn.agentplatform.app.api;
import cn.agentplatform.app.application.IntegrationPolicyResolver;
import cn.agentplatform.app.domain.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.PolicyRequestVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@RestController
@ConditionalOnProperty(prefix="agent.policy-resolver",name="enabled",havingValue="true")
public class IntegrationPolicyResolverController {
    private final IntegrationPolicyResolver resolver;
    public IntegrationPolicyResolverController(IntegrationPolicyResolver resolver){this.resolver=resolver;}
    @PostMapping("/internal/iam/integration-policy/resolve")
    public PolicyResolveResult resolve(HttpServletRequest request){
        Object value=request.getAttribute(PolicyRequestVerifier.ATTRIBUTE);
        if(!(value instanceof PolicyRequestVerifier.Proof proof))throw new PlatformException(401,"POLICY_PROOF_REQUIRED","缺少策略请求证明");
        return resolver.resolve(PolicyResolveRequest.parse(proof.body(request)));
    }
}
