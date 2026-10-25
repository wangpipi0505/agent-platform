from pathlib import Path
import json,hashlib,shutil
r=Path.cwd();parent=r/'docs/evidence/iam-stage-3-review-fixes';o=parent/'i3';o.mkdir(exist_ok=True)
source=json.loads((parent/'source-after.json').read_text(encoding='utf8'))
assert all(hashlib.sha256((r/n).read_bytes()).hexdigest()==s for n,s in source.items())
for n in source:
 p=o/'baseline'/n;p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(r/n,p)
shutil.copyfile(parent/'source-after.json',o/'source-before.json');shutil.copyfile(r/'docs/evidence/iam-stage-3-review-fixes.md',o/'i1-i2-candidate-report.md')
(o/'candidate-protected.json').write_text(json.dumps({p.relative_to(r).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in parent.rglob('*') if p.is_file() and not p.is_relative_to(o)},indent=2,ensure_ascii=False),encoding='utf8')
p=r/'admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java';s=p.read_text(encoding='utf8').replace('    Set<String> currentRoles=Set.of("EDITOR");','    Set<String> currentRoles=Set.of("EDITOR");\n    Set<String> approverRoles=Set.of("APPROVER");').replace('new Actor("tenant-a","approver",Set.of("APPROVER"),grant.ceiling.attributes())','new Actor("tenant-a","approver",approverRoles,grant.ceiling.attributes())');p.write_bytes(s.encode('utf8'))
p=r/'admin-common/src/test/java/cn/agentplatform/common/authority/CurrentAuthorityClientTest.java';s=p.read_text(encoding='utf8');pos=s.index('    @Test');s=s[:pos]+'''    @Test void reviewI3CurrentEditorApproverProofDoesNotImposeFrozenBusinessRoles()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            f.approverRoles=Set.of("EDITOR");
            assertThatCode(()->{
                var proof=engine.approver(current.delegation(),new SubjectKey("tenant-a","approver"),call.resource());
                assertThat(proof.actor().roles()).containsExactly("EDITOR");assertThat(proof.actor().principalId()).isEqualTo("approver");
                assertThat(proof.call().resource()).isEqualTo(call.resource());assertThat(proof.delegation().value()).isEqualTo(current.delegation().value());
            }).doesNotThrowAnyException();
        }
    }
    @Test void reviewI3ApproverCurrentTrustAndBindingsRemainMandatory()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            var subject=new SubjectKey("tenant-a","approver");
            f.approverRoles=Set.of("EDITOR");
            for(String failure:List.of("revoked","subject","503","version","inactive","nonce","resource","signature","grant","expiry")){
                switch(failure){
                    case "revoked"->f.approverAllowed=false;case "503"->f.unavailable=true;case "version"->f.version="v2";
                    case "subject"->f.changeDecision=c->c.put("sub","alice");case "inactive"->f.changeDecision=c->c.put("current",false);
                    case "nonce"->f.changeDecision=c->c.put("nonce","wrong");case "resource"->f.changeDecision=c->c.put("resource",Map.of("kind","run","id","other","version_digest","wrong"));
                    case "signature"->f.badSignature=true;case "grant"->f.changeDecision=c->c.put("grant_ref","other");case "expiry"->f.changeDecision=c->c.put("exp",0);
                }
                assertThatThrownBy(()->engine.approver(current.delegation(),subject,call.resource())).as(failure).isInstanceOf(PlatformException.class);
                f.approverAllowed=true;f.unavailable=false;f.version="v1";f.badSignature=false;f.changeDecision=c->{};
            }
            assertThatThrownBy(()->engine.approver(current.delegation(),new SubjectKey("tenant-a","alice"),call.resource())).isInstanceOf(PlatformException.class);
        }
    }
''' +s[pos:];p.write_bytes(s.encode('utf8'))
