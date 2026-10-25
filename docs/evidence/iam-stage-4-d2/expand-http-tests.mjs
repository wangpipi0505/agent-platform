import fs from 'node:fs';const p='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';let s=fs.readFileSync(p,'utf8');s=s.replace('app=(ServletWebServerApplicationContext)application.run();','app=(ServletWebServerApplicationContext)application.run();fixture.externalPolicy(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+PolicyRequestVerifier.PATH));');s=s.replace('    @BeforeEach void seed(){','    @BeforeEach void seed(){\n        fixture.clearFiniteGrants();');
s=s.replace('    static String assertion(String body){','    static Map<String,Object> assertionClaims(String body){');s=s.replace('return fixture.sign(c,fixture.policySigningKey(),"agent-policy-request+jwt");}','return c;}\n    static String assertion(String body){return fixture.sign(assertionClaims(body),fixture.policySigningKey(),"agent-policy-request+jwt");}');
const tests=`
    @Test void wrongSignatureClaimsAlgorithmsMethodPathAndBindingNeverQueryPg()throws Exception{
        String body=Json.write(requestBody("alice"));
        for(String field:List.of("iss","caller","sub","target","action","aud","token_use","nonce","bodySha256")){
            var c=assertionClaims(body);c.put(field,"wrong");var r=http("POST",PolicyRequestVerifier.PATH,fixture.sign(c,fixture.policySigningKey(),"agent-policy-request+jwt"),body);assertThat(r.statusCode()).as(field+r.body()).isIn(401,403);
        }
        for(String field:List.of("iat","nbf","exp")){var c=assertionClaims(body);c.put(field,field.equals("exp")?0:Instant.now().plusSeconds(120).getEpochSecond());assertThat(http("POST",PolicyRequestVerifier.PATH,fixture.sign(c,fixture.policySigningKey(),"agent-policy-request+jwt"),body).statusCode()).isIn(401,403);}
        for(String token:List.of(fixture.sign(assertionClaims(body),fixture.authoritySigningKey(),"agent-policy-request+jwt"),fixture.sign(assertionClaims(body),fixture.policySigningKey(),"at+jwt"),fixture.portal(),fixture.integration(fixture.integrationClaims()),"eyJhbGciOiJub25lIn0.e30."))assertThat(http("POST",PolicyRequestVerifier.PATH,token,body).statusCode()).isIn(401,403);
        assertThat(http("POST",PolicyRequestVerifier.PATH,assertion(body),body+" ").statusCode()).isIn(401,403);
        assertThat(http("GET",PolicyRequestVerifier.PATH,assertion(body),body).statusCode()).isIn(401,403);
        assertThat(http("POST","/internal/iam/integration-policy/other",assertion(body),body).statusCode()).isIn(401,403);
        String duplicate=body.replace("{","{\\\"schemaVersion\\\":1,");assertThat(http("POST",PolicyRequestVerifier.PATH,assertion(duplicate),duplicate).statusCode()).isIn(401,403);
        assertThat(queries).hasValue(0);assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
    @Test void strictDtoTypesBoundsAndUnknownFieldsRejectBeforePg()throws Exception{
        for(String field:List.of("schemaVersion","credentialId","expectedTenantId","expectedAppCode","expectedBizSystemCode","userId","expectedPrincipalId","expectedPolicyVersion","nonce")){
            var missing=requestBody("alice");missing.remove(field);var r=resolve(missing);assertThat(r.statusCode()).as("missing "+field+r.body()).isIn(400,401);
            var wrong=requestBody("alice");wrong.put(field,List.of("wrong"));assertThat(resolve(wrong).statusCode()).as(field).isIn(400,401);
        }
        for(var entry:Map.<String,Object>of("schemaVersion",2,"credentialId",0,"expectedTenantId","t".repeat(129),"expectedAppCode","a".repeat(65),"userId","u".repeat(129),"expectedPolicyVersion","1:0").entrySet()){var r=requestBody("alice");r.put(entry.getKey(),entry.getValue());assertThat(resolve(r).statusCode()).isEqualTo(400);}
        var extra=requestBody("alice");extra.put("allowed",true);assertThat(resolve(extra).statusCode()).isEqualTo(400);
        assertThat(queries).hasValue(0);
    }
    @Test void tenantAppBizCredentialPrincipalIsolationLongMappingAndSubjectRemoval()throws Exception{
        for(String field:List.of("expectedTenantId","expectedAppCode","expectedBizSystemCode","credentialId","expectedPrincipalId")){
            var r=requestBody("alice");r.put(field,field.equals("credentialId")?Long.MAX_VALUE:"wrong");var out=resolve(r);assertThat(out.statusCode()).as(field+out.body()).isIn(403,404);assertThat(out.body()).doesNotContain("d2-only-reference","claimLimits","principalIds");
        }
        var outsider=resolve(requestBody("bob"));assertThat(outsider.statusCode()).isEqualTo(200);assertThat(Json.read(outsider.body()).path("subjectAllowed").asBoolean()).isFalse();
        var longResult=resolve(requestBody(LONG_USER));assertThat(longResult.statusCode()).as(longResult.body()).isEqualTo(200);assertThat(Json.read(longResult.body()).path("principalId").asText()).isEqualTo(IntegrationClaims.principal(BIZ,LONG_USER));
        var active=resolve(requestBody("alice"));String digest=Json.read(active.body()).path("policyDigest").asText();var meta=db.queryForMap("select secret_ref,claim_limits,allowed_origins from app.integration_credential where id=?",id);
        assertThat(digest).isEqualTo(Digest.sha256(Json.canonical(Map.of("secretRef",Json.tree(meta.get("secret_ref")),"claimLimits",Json.read(meta.get("claim_limits").toString()),"allowedOrigins",Json.read(meta.get("allowed_origins").toString())))));
    }
    @Test void crossedTrustDomainsAndWrongCredentialResourcesWriteNothing()throws Exception{
        String body=Json.write(requestBody("alice"));
        assertThat(http("PUT","/admin/apps/"+APP+"/integration/credentials/"+BIZ,assertion(body),Json.write(policy("bob"))).statusCode()).isIn(401,403);
        try(var gateway=fixture.client("gateway")){
            var route=new ServiceRoutePolicy().require("gateway","app","PUT","/admin/apps/{code}/integration/credentials/{biz}");
            var token=gateway.exchange(fixture.portal(),new ServiceCall("app",route.action(),credentialResource())).value();
            assertThat(http("POST",PolicyRequestVerifier.PATH,token,body).statusCode()).isIn(401,403);
        }
        for(var resource:List.of(new ResourceBinding("integration-credential",Long.toString(id+1),credentialResource().versionDigest()),new ResourceBinding("integration-credential",Long.toString(id),Digest.sha256("wrong")))){
            assertThat(admin("PUT","/admin/apps/"+APP+"/integration/credentials/"+BIZ,"/admin/apps/{code}/integration/credentials/{biz}",resource,Json.write(policy("bob"))).statusCode()).isEqualTo(403);
        }
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);assertThat(queries).hasValue(0);
    }
    @Test void invalidPolicyAndAuditFailureRollBackRevisionAndPayload()throws Exception{
        var invalid=new HashMap<>(policy("bob"));invalid.put("allowedOrigins",List.of("http://external.invalid"));assertThat(update(invalid).statusCode()).isEqualTo(400);
        db.execute("create or replace function app.d2_fail_audit() returns trigger language plpgsql as $$ begin if new.tenant_id='d2-policy-tests' and new.action='INTEGRATION_POLICY' then raise exception 'finite-audit-failure'; end if; return new; end $$");
        db.execute("create trigger d2_fail_audit before insert on app.lifecycle_log for each row execute function app.d2_fail_audit()");
        try{assertThat(update(policy("bob")).statusCode()).isEqualTo(500);}finally{db.execute("drop trigger d2_fail_audit on app.lifecycle_log");db.execute("drop function app.d2_fail_audit()");}
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("select claim_limits->'principalIds' @> '[\\\"alice\\\"]'::jsonb from app.integration_credential where id=?",Boolean.class,id)).isTrue();
    }
    @Test void issueBindsStoredPublishedVersionAndInitialRevisionIsOne()throws Exception{
        var body=new HashMap<>(policy("alice"));body.put("bizSystemCode","issued");
        var resource=new ResourceBinding("app-version",APP+"@1",Digest.sha256("published-d2"));
        assertThat(admin("POST","/admin/apps/"+APP+"/integration/credentials","/admin/apps/{code}/integration/credentials",new ResourceBinding("app-version",APP+"@1",Digest.sha256("wrong")),Json.write(body)).statusCode()).isEqualTo(403);
        var issued=admin("POST","/admin/apps/"+APP+"/integration/credentials","/admin/apps/{code}/integration/credentials",resource,Json.write(body));assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where tenant_id=? and app_code=? and biz_system_code='issued'",Long.class,TENANT,APP)).isEqualTo(1);
    }
    static String integrationToken(String digest){var c=fixture.integrationClaims();c.put("tenant_id",TENANT);c.put("sub",IntegrationClaims.principal(BIZ,"alice"));c.put("app_code",APP);c.put("biz_system_code",BIZ);c.put("user_id","alice");c.put("credential_id",id);c.put("policy_digest",digest);return fixture.integration(c);}
    @Test void realResolverBackedGrantsFailAfterRevisionAndExplicitNewAuthenticationRecovers()throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();String subject=integrationToken(digest);
        var call=new ServiceCall("engine","proxy.engine.get.RunController.status",new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1")));
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            int before=queries.get();var ticket=gateway.exchange(subject,call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var current=engine.authorize(ticket.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            assertThat(current.actor().roles()).containsExactly("reader");
            assertThat(update(policy("alice")).statusCode()).isEqualTo(200);
            before=queries.get();assertThatThrownBy(()->engine.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->gateway.resume(current.delegation(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->gateway.exchange(subject,call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            var fresh=gateway.exchange(integrationToken(digest),call);assertThat(engine.authorize(fresh.value(),call).actor().roles()).containsExactly("reader");
            assertThat(admin("POST","/admin/apps/"+APP+"/integration/credentials/"+BIZ+"/revoke","/admin/apps/{code}/integration/credentials/{biz}/revoke",credentialResource(),"").statusCode()).isEqualTo(200);
            assertThatThrownBy(()->gateway.exchange(integrationToken(digest),call)).isInstanceOf(PlatformException.class);
        }
        assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
`;
s=s.substring(0,s.lastIndexOf('}'))+tests+'}\n';fs.writeFileSync(p,s);
