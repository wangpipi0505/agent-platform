import fs from 'node:fs';let p='admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java',s=fs.readFileSync(p,'utf8').replace('    public int policyCalls(){','    public int authorityCalls(){return requests.size();}\n    public int policyCalls(){');fs.writeFileSync(p,s);
p='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';s=fs.readFileSync(p,'utf8');s=s.replace('        var r=resolve(requestBody("alice"));assertThat(r.statusCode())','        var r=resolve(requestBody("alice"));assertThat(fixture.authorityCalls()).isZero();assertThat(queries).hasValue(1);assertThat(r.statusCode())');
s=s.replace('        var application=new SpringApplication(Receiver.class);','        app=startReceiver();fixture.externalPolicy(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+PolicyRequestVerifier.PATH));\n    }\n    static ServletWebServerApplicationContext startReceiver(){\n        var application=new SpringApplication(Receiver.class);');s=s.replace('        app=(ServletWebServerApplicationContext)application.run();fixture.externalPolicy(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+PolicyRequestVerifier.PATH));','        return (ServletWebServerApplicationContext)application.run();');
s=s.substring(0,s.lastIndexOf('}'))+`
    @Test void actualPolicyChainWithoutGlobalProofRegistrationCannotEnterResolver()throws Exception{
        var original=app;includeProof=false;
        try(var denied=startReceiver()){app=denied;assertThat(resolve(requestBody("alice")).statusCode()).isEqualTo(403);assertThat(queries).hasValue(0);}
        finally{app=original;includeProof=true;}
    }
}\n`;fs.writeFileSync(p,s);
p='platform-gateway/src/test/java/cn/agentplatform/gateway/domain/IntegrationPolicyTest.java';s=fs.readFileSync(p,'utf8');const cr=s.includes('\r\n');s=s.replaceAll('\r\n','\n');s=s.substring(0,s.lastIndexOf('}'))+`
    @Test void sharedRulesPreserveLongPrincipalAndCanonicalLegacyDigest() {
        String user="u".repeat(90);var c=new HashMap<>(credential());
        c.put("claimLimits",Map.of("principalIds",List.of(user),"roles",List.of("operator","admin"),"attributes",Map.of("region",List.of("A"))));
        var node=Json.tree(c);var actor=IntegrationPolicy.clip(node,Json.tree(Map.of("userId",user,"roles",List.of("operator","admin"))));
        assertThat(actor.principalId()).isEqualTo("ext_"+Digest.sha256("partner\\u0000"+user).substring(0,60));assertThat(actor.roles()).containsExactly("operator");
        assertThat(IntegrationPolicy.digest(node)).isEqualTo(Digest.sha256(Json.canonical(Map.of("secretRef",node.path("secretRef"),"claimLimits",node.path("claimLimits"),"allowedOrigins",node.path("allowedOrigins")))));
        assertThat(IntegrationPolicy.allowedPath("/internal/iam/integration-policy/resolve","POST","ops")).isFalse();
    }
}\n`;fs.writeFileSync(p,cr?s.replaceAll('\n','\r\n'):s);
