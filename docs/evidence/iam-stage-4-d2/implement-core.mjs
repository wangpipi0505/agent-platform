import fs from 'node:fs';
function edit(p,fn){const s=fs.readFileSync(p,'utf8'),cr=s.includes('\r\n');const n=fn(s.replaceAll('\r\n','\n'));fs.writeFileSync(p,cr?n.replaceAll('\n','\r\n'):n);}
edit('app-management/src/main/java/cn/agentplatform/app/api/IntegrationPolicyResolverController.java',s=>s.replace('Json.read(new String(proof.body(request),java.nio.charset.StandardCharsets.UTF_8),PolicyResolveRequest.class)','PolicyResolveRequest.parse(proof.body(request))'));
edit('admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java',s=>s.replace('        var jwt=jwt();\n        var call=',`        if(policyHandler!=null&&handler instanceof org.springframework.web.method.HandlerMethod method
                &&policyHandler.equals(method.getMethod())&&PolicyRequestVerifier.PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))){
            Object proof=request.getAttribute(PolicyRequestVerifier.ATTRIBUTE);
            if(!(proof instanceof PolicyRequestVerifier.Proof verified))throw AuthorityProtocol.denied();
            policyVerifier.require(request,verified);return true;
        }
        var jwt=jwt();
        var call=`));
edit('platform-gateway/src/main/java/cn/agentplatform/gateway/domain/IntegrationPolicy.java',s=>s.replace('    private static final Set<String> PLATFORM_ROLES =\n            Set.of("ADMIN", "EDITOR", "SERVICE", "GATEWAY_AUTH");\n','').replace('roles.removeIf(r -> PLATFORM_ROLES.contains(r.toUpperCase(Locale.ROOT)));','roles.removeIf(cn.agentplatform.common.authority.IntegrationClaims::platformRole);').replace('String principal = biz + ":" + user;\n        if (principal.length() > 64)\n            principal = "ext_" + Digest.sha256(biz + "\\u0000" + user).substring(0, 60);','String principal = cn.agentplatform.common.authority.IntegrationClaims.principal(biz, user);').replace(/        return Digest.sha256\([\s\S]*?c.path\("allowedOrigins"\)\)\)\);/,'        return cn.agentplatform.common.authority.IntegrationClaims.digest(c);'));
edit('app-management/src/main/java/cn/agentplatform/app/application/IntegrationService.java',s=>s.replace('import cn.agentplatform.common.*;','import cn.agentplatform.common.*;\nimport cn.agentplatform.common.authority.*;').replace('        var app = apps.get(code);\n        if (app.get("currentVersionNo") == null) throw error(409, "APP_UNPUBLISHED", "请先发布应用");','        requirePublishedVersion(code);').replaceAll('        get(code, biz, false);','        requireCredential(code, biz);').replace("set status='REVOKED',revoked_at=now() where","set status='REVOKED',revoked_at=now(),policy_revision=policy_revision+1 where").replace(' claim_limits=?::jsonb,allowed_origins=?::jsonb where tenant_id=? and',' claim_limits=?::jsonb,allowed_origins=?::jsonb,policy_revision=policy_revision+1 where tenant_id=? and').replace('    private void audit(',`    /** Lock the real published pointer before authorization and retain the lock until issue commits.
     * The stored immutable published SHA is authoritative; no draft/payload is read to derive it. */
    private void requirePublishedVersion(String code) {
        var rows=db.queryForList("select a.current_version_no,v.candidate_sha256 from app.application a left join app.app_version v on v.tenant_id=a.tenant_id and v.app_code=a.code and v.version_no=a.current_version_no where a.tenant_id=? and a.code=? for update of a",tenant(),code);
        if(rows.isEmpty())throw error(404,"APP_NOT_FOUND","应用不存在");
        var row=rows.getFirst();if(row.get("current_version_no")==null||row.get("candidate_sha256")==null)throw error(409,"APP_UNPUBLISHED","请先发布应用");
        AuthorityContext.requireFor(Actor.current(),new ResourceBinding("app-version",code+"@"+row.get("current_version_no"),row.get("candidate_sha256").toString()));
    }
    /** Policy metadata is read under the row lock. The same transaction verifies the old exact
     * resource, mutates policy and increments revision; concurrent updates cannot lose increments. */
    private void requireCredential(String code,String biz) {
        var rows=db.queryForList("select id,policy_revision,secret_ref,claim_limits,allowed_origins from app.integration_credential where tenant_id=? and app_code=? and biz_system_code=? for update",tenant(),code,biz);
        if(rows.isEmpty())throw error(404,"INTEGRATION_NOT_FOUND","集成凭据不存在");
        var row=rows.getFirst();long id=((Number)row.get("id")).longValue();
        String digest=IntegrationClaims.digest(Json.tree(Map.of("secretRef",row.get("secret_ref"),"claimLimits",Json.read(row.get("claim_limits").toString()),"allowedOrigins",Json.read(row.get("allowed_origins").toString()))));
        String version=Digest.sha256(Json.canonical(Map.of("credentialId",id,"policyVersion",id+":"+row.get("policy_revision"),"policyDigest",digest)));
        AuthorityContext.requireFor(Actor.current(),new ResourceBinding("integration-credential",Long.toString(id),version));
    }

    private void audit(`));
