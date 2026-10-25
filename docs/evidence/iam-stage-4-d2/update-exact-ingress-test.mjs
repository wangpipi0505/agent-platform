import fs from 'node:fs';function edit(p,fn){const s=fs.readFileSync(p,'utf8'),cr=s.includes('\r\n');const n=fn(s.replaceAll('\r\n','\n'));fs.writeFileSync(p,cr?n.replaceAll('\n','\r\n'):n);}
edit('app-management/src/main/java/cn/agentplatform/app/api/IntegrationPolicyResolverController.java',s=>s.replace('@PostMapping(PolicyRequestVerifier.PATH)','@PostMapping("/internal/iam/integration-policy/resolve")'));
edit('admin-common/src/test/java/cn/agentplatform/common/authority/AuthorityPolicyTest.java',s=>s.replace('        assertThat(actual).hasSize(158).containsExactlyInAnyOrderElementsOf(catalog);',`        // POLICY_MACHINE is a distinct exact ingress, never a service capability. Retain every
        // original catalog entry and compare the full union rather than merely changing a count.
        String policyIngress="app|POST|"+PolicyRequestVerifier.PATH;
        assertThat(catalog).hasSize(158).doesNotContain(policyIngress);
        var allIngress=new HashSet<>(catalog);allIngress.add(policyIngress);
        assertThat(actual).hasSize(159).containsExactlyInAnyOrderElementsOf(allIngress);
        assertThat(policy.routes()).noneMatch(r->r.template().equals(PolicyRequestVerifier.PATH));
        String policySource=Files.readString(root.resolve("app-management/src/main/java/cn/agentplatform/app/api/IntegrationPolicyResolverController.java"));
        assertThat(policySource).contains("@PostMapping(\\\""+PolicyRequestVerifier.PATH+"\\\")");`));
