import fs from 'node:fs';const p='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';let s=fs.readFileSync(p,'utf8');let tests=`
    @Test void concurrentStaleWritesSerializeAndSnapshotsNeverMixRevisionAndPolicy()throws Exception{
        String oldDigest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();
        var route=new ServiceRoutePolicy().require("gateway","app","PUT","/admin/apps/{code}/integration/credentials/{biz}");var call=new ServiceCall("app",route.action(),credentialResource());String first,second;
        try(var gateway=fixture.client("gateway")){first=gateway.exchange(fixture.portal(),call).value();second=gateway.exchange(fixture.portal(),call).value();}
        String path="/admin/apps/"+APP+"/integration/credentials/"+BIZ,body=Json.write(policy("bob"));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(3)){
            var gate=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return http("PUT",path,first,body);});
            var b=pool.submit(()->{gate.await();return http("PUT",path,second,body);});
            var reads=pool.submit(()->{gate.await();var out=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();for(int i=0;i<4;i++){var r=resolve(requestBody("alice"));assertThat(r.statusCode()).isEqualTo(200);out.add(Json.read(r.body()));}return out;});gate.countDown();
            assertThat(List.of(a.get(8,java.util.concurrent.TimeUnit.SECONDS).statusCode(),b.get(8,java.util.concurrent.TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,403);
            String newDigest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();assertThat(newDigest).isNotEqualTo(oldDigest);
            for(var n:reads.get(8,java.util.concurrent.TimeUnit.SECONDS)){if(n.path("policyVersion").asText().equals(id+":1")){assertThat(n.path("policyDigest").asText()).isEqualTo(oldDigest);assertThat(n.path("subjectAllowed").asBoolean()).isTrue();}else{assertThat(n.path("policyVersion").asText()).isEqualTo(id+":2");assertThat(n.path("policyDigest").asText()).isEqualTo(newDigest);assertThat(n.path("subjectAllowed").asBoolean()).isFalse();}}
        }
        assertThat(update(policy("alice")).statusCode()).isEqualTo(200);assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(3);
    }
    @Test void nonAdminAndWrongTenantCannotMutateEvenWithCorrectServiceRoute()throws Exception{
        try{
            fixture.portalActor(new Actor(TENANT,"reader-d2",Set.of("reader"),Map.of("dataLabels",Set.of("restricted"))));assertThat(update(policy("bob")).statusCode()).isEqualTo(403);
            fixture.portalActor(new Actor("other-tenant","admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))));assertThat(update(policy("bob")).statusCode()).isEqualTo(404);
        }finally{fixture.portalActor(new Actor(TENANT,"admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))));}
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);
    }
`;
s=s.substring(0,s.lastIndexOf('}'))+tests+'}\n';fs.writeFileSync(p,s);
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
