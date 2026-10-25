import fs from 'node:fs';let p='admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java';let s=fs.readFileSync(p,'utf8').replace('public void clearFiniteGrants(){grants.clear();operations.clear();decisions.clear();requests.clear();}','public void clearFiniteGrants(){grants.clear();operations.clear();decisions.clear();requests.clear();changeDecision=c->{};}\n    public void narrowAdminAtResourceCheck(){var count=new AtomicInteger();changeDecision=c->{if(c.containsKey("roles")&&count.incrementAndGet()>=2)c.put("roles",Set.of());};}');fs.writeFileSync(p,s);
p='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';s=fs.readFileSync(p,'utf8');s=s.substring(0,s.lastIndexOf('}'))+`
    @Test void adminRoleMustStillExistAtTheFinalResourceDecision()throws Exception{
        fixture.narrowAdminAtResourceCheck();assertThat(update(policy("bob")).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);
    }
}\n`;fs.writeFileSync(p,s);
