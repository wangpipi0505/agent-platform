import fs from 'node:fs';let p='admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java',s=fs.readFileSync(p,'utf8');
s=s.replace('            PolicyResolveResult actual=integration&&externalPolicy!=null?externalPolicy(details,null,null):null;',`            String sourceHash=Digest.sha256(token);
            Grant original=integration&&externalPolicy!=null?grants.values().stream().filter(g->sourceHash.equals(g.integration.get("source_token_sha256"))).findFirst().orElse(null):null;
            PolicyResolveResult actual=integration&&externalPolicy!=null?externalPolicy(details,original==null?null:original.policyVersion,original==null?null:original.ceiling):null;
            if(actual!=null)details.put("source_token_sha256",sourceHash);`);
fs.writeFileSync(p,s);
p='app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java';s=fs.readFileSync(p,'utf8');s=s.replace('static final AtomicInteger queries=new AtomicInteger();','static final AtomicInteger queries=new AtomicInteger(),auditErrors=new AtomicInteger();');s=s.replace('        @Bean DataSource dataSource()', '        @Bean TestDatabaseErrors databaseErrors(){return new TestDatabaseErrors();}\n        @Bean DataSource dataSource()');s=s.replace('    @BeforeAll static void start()',`    @org.springframework.web.bind.annotation.RestControllerAdvice static class TestDatabaseErrors {
        @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DataAccessException.class)
        org.springframework.http.ResponseEntity<Object> failed(){auditErrors.incrementAndGet();return org.springframework.http.ResponseEntity.status(500).body(Map.of("code","FINITE_DATABASE_FAILURE"));}
    }
    @BeforeAll static void start()`);s=s.replace('fixture.clearFiniteGrants();','fixture.clearFiniteGrants();auditErrors.set(0);');s=s.replace('try{assertThat(update(policy("bob")).statusCode()).isEqualTo(500);}','try{assertThat(update(policy("bob")).statusCode()).isEqualTo(500);assertThat(auditErrors).hasValue(1);}');fs.writeFileSync(p,s);
