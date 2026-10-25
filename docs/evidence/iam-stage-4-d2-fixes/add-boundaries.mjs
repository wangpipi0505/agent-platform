import fs from 'node:fs';import assert from 'node:assert/strict';
const file='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';
let s=fs.readFileSync(file,'utf8'),eol=s.includes('\r\n')?'\r\n':'\n';s=s.replaceAll('\r\n','\n');
const marker='    @Test void malformedSourceAuthenticationRejectsBeforePolicyAndConsumesNoGrant()throws Exception{';assert.ok(s.includes(marker));
s=s.replace(marker,`    @Test void emptySourcePermissionsAreNeverReplenishedFromPolicy()throws Exception{
        assertThat(update(widePolicy()).statusCode()).isEqualTo(200);String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            for(boolean explicitEmptyRegion:List.of(false,true)){
                var claims=sourceClaims(digest);claims.put("roles",List.of());var attributes=new LinkedHashMap<String,Object>();attributes.put("bizSystemCode",List.of(BIZ));attributes.put("userId",List.of("alice"));if(explicitEmptyRegion)attributes.put("region",List.of());claims.put("attributes",attributes);
                int before=queries.get();var ticket=gateway.exchange(fixture.integration(claims),integrationCall());assertThat(queries.get()-before).isEqualTo(1);
                var initial=ticketClaims(ticket).path("initial_ceiling");assertThat(initial.path("roles")).isEmpty();assertThat(initial.path("attributes").path("region")).isEmpty();assertThat(initial.path("attributes").path("dataLabels").toString()).isEqualTo("[\\"restricted\\"]");
                before=queries.get();var current=engine.authorize(ticket.value(),integrationCall());assertThat(queries.get()-before).isEqualTo(1);assertThat(current.actor().roles()).isEmpty();assertThat(current.actor().attributes().getOrDefault("region",Set.of())).isEmpty();
            }
        }
    }
`+marker);
const insertion='            for(String field:List.of("iss","jti","roles","attributes")){';assert.ok(s.includes(insertion));
s=s.replace(insertion,`            var valid=sourceClaims(digest);String signed=fixture.integration(valid);int signatureStart=signed.lastIndexOf('.')+1;
            String wrongSignature=signed.substring(0,signatureStart)+(signed.charAt(signatureStart)=='A'?'B':'A')+signed.substring(signatureStart+1);
            assertSourceTokenDeniedWithoutQuery(gateway,wrongSignature);assertSourceTokenDeniedWithoutQuery(gateway,fixture.sign(valid,fixture.authoritySigningKey(),"JWT"));
`+insertion);
const old=`        int before=queries.get(),grants=fixture.grantCount();assertThatThrownBy(()->gateway.exchange(fixture.integration(claims),integrationCall())).isInstanceOf(PlatformException.class);assertThat(queries.get()).isEqualTo(before);assertThat(fixture.grantCount()).isEqualTo(grants);`;
assert.ok(s.includes(old));s=s.replace(old,`        assertSourceTokenDeniedWithoutQuery(gateway,fixture.integration(claims));
    }
    static void assertSourceTokenDeniedWithoutQuery(HttpCurrentAuthorityClient gateway,String token){
        int before=queries.get(),grants=fixture.grantCount();assertThatThrownBy(()->gateway.exchange(token,integrationCall())).isInstanceOf(PlatformException.class);assertThat(queries.get()).isEqualTo(before);assertThat(fixture.grantCount()).isEqualTo(grants);`);
fs.writeFileSync(file,s.replaceAll('\n',eol));
