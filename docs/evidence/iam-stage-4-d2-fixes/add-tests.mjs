import fs from 'node:fs';import assert from 'node:assert/strict';
const file='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';
let s=fs.readFileSync(file,'utf8'),eol=s.includes('\r\n')?'\r\n':'\n';s=s.replaceAll('\r\n','\n');
const old='    static String integrationToken(String digest){var c=fixture.integrationClaims();c.put("tenant_id",TENANT);c.put("sub",IntegrationClaims.principal(BIZ,"alice"));c.put("app_code",APP);c.put("biz_system_code",BIZ);c.put("user_id","alice");c.put("credential_id",id);c.put("policy_digest",digest);return fixture.integration(c);}';
assert.ok(s.includes(old));
s=s.replace(old,`    static Map<String,Object> sourceClaims(String digest){
        var c=fixture.integrationClaims();c.put("iss",ServiceTokens.ISSUER);c.put("tenant_id",TENANT);c.put("sub",IntegrationClaims.principal(BIZ,"alice"));c.put("app_code",APP);c.put("biz_system_code",BIZ);c.put("user_id","alice");c.put("credential_id",id);c.put("policy_digest",digest);
        c.put("roles",List.of("reader"));c.put("attributes",Map.of("region",List.of("A"),"dataLabels",List.of("source-only"),"bizSystemCode",List.of(BIZ),"userId",List.of("alice")));return c;
    }
    static String integrationToken(String digest){return fixture.integration(sourceClaims(digest));}
    static ServiceCall integrationCall(){return new ServiceCall("engine","proxy.engine.get.RunController.status",new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1")));}
    static com.fasterxml.jackson.databind.JsonNode ticketClaims(ServiceTicket ticket){return Json.read(new String(Base64.getUrlDecoder().decode(ticket.value().split("\\\\.")[1]),java.nio.charset.StandardCharsets.UTF_8));}
    static Map<String,Object> widePolicy(){return Map.of("claimLimits",Map.of("principalIds",List.of("alice",LONG_USER),"roles",List.of("reader","writer","ADMIN"),"attributes",Map.of("region",List.of("A","B"),"dataLabels",List.of("restricted"))),"allowedOrigins",List.of("https://embed.example.invalid"));}
    static String padBitsVariant(String token)throws Exception{
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";String result=token.substring(0,token.length()-1)+alphabet.charAt(alphabet.indexOf(token.charAt(token.length()-1))+1);
        var original=com.nimbusds.jose.JWSObject.parse(token);var changed=com.nimbusds.jose.JWSObject.parse(result);
        assertThat(changed.getSignature().decode()).isEqualTo(original.getSignature().decode());assertThat(changed.getPayload().toBytes()).isEqualTo(original.getPayload().toBytes());assertThat(result).isNotEqualTo(token);return result;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pad-bits","reordered-claims"})
    void stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision(String representation)throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();var claims=sourceClaims(digest);String original=fixture.integration(claims);
        String equivalent=representation.equals("pad-bits")?padBitsVariant(original):fixture.integration(new TreeMap<>(claims));assertThat(equivalent).isNotEqualTo(original);
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            var call=integrationCall();int before=queries.get();var ticket=gateway.exchange(original,call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var current=engine.authorize(ticket.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var same=gateway.exchange(equivalent,call);assertThat(queries.get()-before).isEqualTo(1);assertThat(ticketClaims(same).get("initial_ceiling")).isEqualTo(ticketClaims(ticket).get("initial_ceiling"));
            assertThat(update(policy("alice")).statusCode()).isEqualTo(200);assertThat(Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText()).isEqualTo(digest);
            int grants=fixture.grantCount();before=queries.get();assertThatThrownBy(()->gateway.exchange(equivalent,call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);assertThat(fixture.grantCount()).isEqualTo(grants);
            before=queries.get();assertThatThrownBy(()->gateway.exchange(original,call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->gateway.resume(current.delegation(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->engine.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);assertThat(fixture.grantCount()).isEqualTo(grants);
            var fresh=new LinkedHashMap<>(claims);fresh.put("jti",UUID.randomUUID().toString());before=queries.get();var next=gateway.exchange(fixture.integration(fresh),call);assertThat(queries.get()-before).isEqualTo(1);assertThat(fixture.grantCount()).isEqualTo(grants+1);
            before=queries.get();assertThat(engine.authorize(next.value(),call).actor().roles()).containsExactly("reader");assertThat(queries.get()-before).isEqualTo(1);
        }
        assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
    @Test void externalGrantKeepsNarrowSourceClaimsAndUnionsLabelsThroughEveryDecision()throws Exception{
        assertThat(update(widePolicy()).statusCode()).isEqualTo(200);String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();var claims=sourceClaims(digest);claims.put("roles",List.of("reader","ADMIN"));
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            var call=integrationCall();int before=queries.get();var ticket=gateway.exchange(fixture.integration(claims),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var current=engine.authorize(ticket.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var resumed=gateway.resume(current.delegation(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var resumedCurrent=engine.authorize(resumed.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var repeated=gateway.exchange(fixture.integration(new TreeMap<>(claims)),call);assertThat(queries.get()-before).isEqualTo(1);
            assertSoftly(soft->{
                for(var candidate:List.of(ticket,resumed,repeated)){
                    var n=ticketClaims(candidate);for(var actor:List.of(n,n.get("initial_ceiling"))){
                        soft.assertThat(actor.path("roles").toString()).isEqualTo("[\\"reader\\"]");
                        soft.assertThat(actor.path("attributes").path("region").toString()).isEqualTo("[\\"A\\"]");
                        soft.assertThat(actor.path("attributes").path("dataLabels")).containsExactlyInAnyOrder(Json.tree("source-only"),Json.tree("restricted"));
                        soft.assertThat(actor.path("attributes").path("bizSystemCode").toString()).isEqualTo("[\\"partner\\"]");soft.assertThat(actor.path("attributes").path("userId").toString()).isEqualTo("[\\"alice\\"]");
                    }
                }
                for(var actor:List.of(current.actor(),resumedCurrent.actor())){soft.assertThat(actor.roles()).containsExactly("reader");soft.assertThat(actor.attributes().get("region")).containsExactly("A");soft.assertThat(actor.attributes().get("dataLabels")).containsExactlyInAnyOrder("source-only","restricted");}
            });
        }
        assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
    @Test void malformedSourceAuthenticationRejectsBeforePolicyAndConsumesNoGrant()throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();
        try(var gateway=fixture.client("gateway")){
            for(String field:List.of("iss","jti","roles","attributes")){
                var missing=sourceClaims(digest);missing.remove(field);assertSourceDeniedWithoutQuery(gateway,missing);
                var wrong=sourceClaims(digest);wrong.put(field,7);assertSourceDeniedWithoutQuery(gateway,wrong);
            }
            for(Object jti:List.of(""," ","j".repeat(129),List.of("jti"))){var c=sourceClaims(digest);c.put("jti",jti);assertSourceDeniedWithoutQuery(gateway,c);}
            var issuer=sourceClaims(digest);issuer.put("iss","wrong-issuer");assertSourceDeniedWithoutQuery(gateway,issuer);
            var subject=sourceClaims(digest);subject.put("sub","other");assertSourceDeniedWithoutQuery(gateway,subject);
            for(var attributes:List.of(Map.of("unknown",List.of("A")),Map.of("bizSystemCode",List.of("other"),"userId",List.of("alice")),Map.of("bizSystemCode",List.of(BIZ),"userId",List.of("other")),Map.of("region",List.of(1),"bizSystemCode",List.of(BIZ),"userId",List.of("alice")))){var c=sourceClaims(digest);c.put("attributes",attributes);assertSourceDeniedWithoutQuery(gateway,c);}
        }
    }
    static void assertSourceDeniedWithoutQuery(HttpCurrentAuthorityClient gateway,Map<String,Object> claims){
        int before=queries.get(),grants=fixture.grantCount();assertThatThrownBy(()->gateway.exchange(fixture.integration(claims),integrationCall())).isInstanceOf(PlatformException.class);assertThat(queries.get()).isEqualTo(before);assertThat(fixture.grantCount()).isEqualTo(grants);
    }
    @Test void sameAuthenticationCannotChangeBindingsTimesOrOriginalCeiling()throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();var claims=sourceClaims(digest);
        try(var gateway=fixture.client("gateway")){
            gateway.exchange(fixture.integration(claims),integrationCall());
            for(String field:List.of("tenant_id","sub","app_code","biz_system_code","user_id","credential_id","origin","policy_digest","iat","nbf","exp","roles","attributes")){
                var changed=new LinkedHashMap<>(claims);changed.put(field,switch(field){case "credential_id"->id+1;case "iat","nbf"->((Number)claims.get(field)).longValue()-1;case "exp"->((Number)claims.get(field)).longValue()+1;case "roles"->List.of("reader","writer");case "attributes"->Map.of("region",List.of("A","B"),"dataLabels",List.of("source-only"),"bizSystemCode",List.of(BIZ),"userId",List.of("alice"));default->"changed";});assertSourceDeniedWithoutQuery(gateway,changed);
            }
        }
    }
`);
fs.writeFileSync(file,s.replaceAll('\n',eol));
const fixture='admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java';
let f=fs.readFileSync(fixture,'utf8');assert.ok(f.includes('    public int authorityCalls()'));f=f.replace('    public int authorityCalls()','    public int grantCount(){return grants.size();}'+(f.includes('\r\n')?'\r\n':'\n')+'    public int authorityCalls()');fs.writeFileSync(fixture,f);
