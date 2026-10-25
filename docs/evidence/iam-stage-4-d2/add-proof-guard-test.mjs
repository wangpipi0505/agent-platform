import fs from 'node:fs';let p,s,tests;
p='admin-common/src/test/java/cn/agentplatform/common/authority/PolicyRequestVerifierTest.java';s=fs.readFileSync(p,'utf8');tests=`
    static class PolicyHandler {public void resolve(){} public void other(){}}
    @Test void globalGuardRequiresRegisteredMethodAndUnforgeableProof()throws Exception{
        try(var f=new LoopbackAuthority();var client=f.client("app")){
            var verifier=verifier(f);var method=PolicyHandler.class.getMethod("resolve");
            var guard=new ServiceAuthorization("app",new ServiceRoutePolicy(),client,null,method,verifier);
            var handler=new org.springframework.web.method.HandlerMethod(new PolicyHandler(),method);
            var r=request(f.sign(claims(f),f.policyKey,"agent-policy-request+jwt"));r.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,PolicyRequestVerifier.PATH);
            assertThatThrownBy(()->guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isInstanceOf(PlatformException.class);
            r.setAttribute(PolicyRequestVerifier.ATTRIBUTE,true);assertThatThrownBy(()->guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isInstanceOf(PlatformException.class);
            var proof=verifier.verify(r,BODY);r.setAttribute(PolicyRequestVerifier.ATTRIBUTE,proof);assertThat(guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isTrue();
            var bytes=proof.body(r);bytes[0]=0;assertThat(proof.body(r)).containsExactly(BODY);
            var other=new org.springframework.web.method.HandlerMethod(new PolicyHandler(),PolicyHandler.class.getMethod("other"));assertThatThrownBy(()->guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),other)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->new ServiceAuthorization("app",new ServiceRoutePolicy(),client,null,method,null)).isInstanceOf(IllegalArgumentException.class);
            var withoutRegistration=new ServiceAuthorization("app",new ServiceRoutePolicy(),client);assertThatThrownBy(()->withoutRegistration.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isInstanceOf(PlatformException.class);
        }
    }
`;
s=s.substring(0,s.lastIndexOf('}'))+tests+'}\n';fs.writeFileSync(p,s);
