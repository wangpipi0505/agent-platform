import fs from 'node:fs';
const p='admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java';let s=fs.readFileSync(p,'utf8');
s=s.replace('    int serial;','    int serial;\n    Actor explicitPortalActor;\n    public void portalActor(Actor actor){explicitPortalActor=actor;currentRoles=actor.roles();}');
s=s.replace('Set.of("EDITOR"),Set.of("region","dataLabels")','explicitPortalActor==null?Set.of("EDITOR"):explicitPortalActor.roles(),Set.of("region","dataLabels")');
s=s.replace('        return sign(c,portalKey,"at+jwt");','        if(explicitPortalActor!=null){c.put("tenant_id",explicitPortalActor.tenantId());c.put("sub",explicitPortalActor.principalId());c.put("roles",explicitPortalActor.roles());c.put("attributes",explicitPortalActor.attributes());}\n        return sign(c,portalKey,"at+jwt");');
s=s.replace('grants.size()>=2','grants.size()>=(explicitPortalActor==null?2:32)');
s=s.replace('new Grant("grant-"+(++serial),ceiling(),','new Grant("grant-"+(++serial),explicitPortalActor==null?ceiling():explicitPortalActor,');
s=s.replace('AuthorityProtocol.same("tenant-a",AuthorityProtocol.text(c,"tenant_id"));AuthorityProtocol.same("alice",AuthorityProtocol.text(c,"sub"));','AuthorityProtocol.same(explicitPortalActor==null?"tenant-a":explicitPortalActor.tenantId(),AuthorityProtocol.text(c,"tenant_id"));AuthorityProtocol.same(explicitPortalActor==null?"alice":explicitPortalActor.principalId(),AuthorityProtocol.text(c,"sub"));');
fs.writeFileSync(p,s);
