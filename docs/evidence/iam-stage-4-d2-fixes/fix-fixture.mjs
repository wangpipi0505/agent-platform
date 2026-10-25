import fs from 'node:fs';import assert from 'node:assert/strict';
const file='admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java';
let s=fs.readFileSync(file,'utf8'),eol=s.includes('\r\n')?'\r\n':'\n';s=s.replaceAll('\r\n','\n');
const old=`            Grant original=integration&&externalPolicy!=null?grants.values().stream().filter(g->sourceHash.equals(g.integration.get("source_token_sha256"))).findFirst().orElse(null):null;
            PolicyResolveResult actual=integration&&externalPolicy!=null?externalPolicy(details,original==null?null:original.policyVersion,original==null?null:original.ceiling):null;
            if(actual!=null)details.put("source_token_sha256",sourceHash);
            Actor ceiling=actual==null?(explicitPortalActor==null?ceiling():explicitPortalActor):policyActor(actual);`;
assert.ok(s.includes(old));
s=s.replace(old,`            // A verified authentication survives JWT text/header/JSON representation changes.
            // Keep its first version/ceiling in the existing finite Grant store; no new cache.
            Grant original=integration&&externalPolicy!=null?originalAuthentication(subject):null;
            PolicyResolveResult actual=integration&&externalPolicy!=null?externalPolicy(details,original==null?null:original.policyVersion,original==null?null:original.ceiling):null;
            if(actual!=null)details.put("source_token_sha256",sourceHash); // Diagnostic binding, never authentication identity.
            Actor ceiling=actual==null?(explicitPortalActor==null?ceiling():explicitPortalActor):(original==null?initialIntegrationCeiling(subject,actual):original.ceiling);`);
const oldValidate='            else {for(String k:List.of("app_code","biz_system_code","user_id","tenant_id","sub","policy_digest","origin"))AuthorityProtocol.text(c,k);if(!c.path("credential_id").isIntegralNumber()||!c.path("credential_id").canConvertToLong()||c.path("credential_id").longValue()<1)throw AuthorityProtocol.denied();}';
assert.ok(s.includes(oldValidate));
s=s.replace(oldValidate,`            else {
                // This finite legacy HMAC source has its own fixed issuer; it is not a production IAM.
                AuthorityProtocol.same(new JOSEObjectType("JWT"),jws.getHeader().getType());
                AuthorityProtocol.same(ServiceTokens.ISSUER,AuthorityProtocol.text(c,"iss"));
                bounded(c,"jti",128);bounded(c,"tenant_id",128);bounded(c,"app_code",64);bounded(c,"biz_system_code",64);bounded(c,"user_id",128);bounded(c,"sub",64);bounded(c,"origin",2048);
                AuthorityProtocol.hash(AuthorityProtocol.text(c,"policy_digest"));
                if(!c.path("credential_id").isIntegralNumber()||!c.path("credential_id").canConvertToLong()||c.path("credential_id").longValue()<1)throw AuthorityProtocol.denied();
                var issued=AuthorityProtocol.time(c,"iat");var expires=AuthorityProtocol.time(c,"exp");
                if(issued.isAfter(clock.instant())||!expires.isAfter(issued)||(c.has("nbf")&&AuthorityProtocol.time(c,"nbf").isAfter(clock.instant())))throw AuthorityProtocol.denied();
                sourceIntegrationActor(c); // Strict claims and reserved identity bindings before any PG query.
            }`);
const marker='    JsonNode service(String token)throws Exception {';
assert.ok(s.includes(marker));
s=s.replace(marker,`    private static String bounded(JsonNode c,String key,int max){String value=AuthorityProtocol.text(c,key);if(value.length()>max)throw AuthorityProtocol.denied();return value;}
    private static Actor sourceIntegrationActor(JsonNode c){
        var actor=AuthorityProtocol.actor(c);String biz=AuthorityProtocol.text(c,"biz_system_code"),user=AuthorityProtocol.text(c,"user_id");
        AuthorityProtocol.same(IntegrationClaims.principal(biz,user),actor.principalId());
        AuthorityProtocol.same(Set.of(biz),actor.attributes().get("bizSystemCode"));AuthorityProtocol.same(Set.of(user),actor.attributes().get("userId"));
        return actor;
    }
    private Grant originalAuthentication(JsonNode subject){
        String issuer=AuthorityProtocol.text(subject,"iss"),jti=AuthorityProtocol.text(subject,"jti");
        var original=grants.values().stream().filter(g->g.origin.equals("INTEGRATION")&&issuer.equals(g.integration.get("iss"))&&jti.equals(g.integration.get("jti"))).findFirst().orElse(null);
        if(original!=null){
            var first=Json.tree(original.integration);
            // Same identity may only be another representation, never another binding, lifetime
            // or source ceiling. Actor parsing compares role/attribute sets independent of JSON order.
            for(String k:List.of("iss","jti","tenant_id","sub","credential_id","app_code","biz_system_code","user_id","origin","policy_digest","token_use","aud","iat","nbf","exp"))AuthorityProtocol.same(first.get(k),subject.get(k));
            AuthorityProtocol.same(sourceIntegrationActor(first),sourceIntegrationActor(subject));
        }
        return original;
    }
    private static Actor initialIntegrationCeiling(JsonNode subject,PolicyResolveResult policy){
        var source=sourceIntegrationActor(subject);var upper=policyActor(policy);
        AuthorityProtocol.same(AuthorityProtocol.subject(source),AuthorityProtocol.subject(upper));
        var roles=new TreeSet<>(source.roles());roles.retainAll(upper.roles());roles.removeIf(IntegrationClaims::platformRole);
        var attributes=new TreeMap<String,Set<String>>();
        // AuthorityProtocol.attrs admits region as the permission attribute; reserved ownership
        // is exact and dataLabels are obligations. Missing/empty permissions stay empty.
        for(var entry:source.attributes().entrySet()){
            String name=entry.getKey();if(Set.of("dataLabels","bizSystemCode","userId").contains(name))continue;
            var values=new TreeSet<>(entry.getValue());values.retainAll(upper.attributes().getOrDefault(name,Set.of()));attributes.put(name,values);
        }
        for(String name:List.of("bizSystemCode","userId")){AuthorityProtocol.same(source.attributes().get(name),upper.attributes().get(name));attributes.put(name,source.attributes().get(name));}
        var labels=new TreeSet<>(source.attributes().getOrDefault("dataLabels",Set.of()));labels.addAll(upper.attributes().getOrDefault("dataLabels",Set.of()));attributes.put("dataLabels",labels);
        var result=new Actor(source.tenantId(),source.principalId(),roles,attributes);
        AuthorityProtocol.within(source,result);AuthorityProtocol.within(upper,result);return result;
    }
`+marker);
fs.writeFileSync(file,s.replaceAll('\n',eol));
