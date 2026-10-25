"""Read-only source inventory. Explicit reviewed edges below; never infer grants by URL family."""
from pathlib import Path
import re, json, hashlib
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
MODULES={'platform-gateway':'gateway','app-management':'app','engine-runtime':'engine','skill-registry':'skill','model-gateway':'model','monitor':'monitor'}
inventory=[]
for module,target in MODULES.items():
    for f in sorted((ROOT/module/'src/main/java').rglob('*.java')):
        text=f.read_text(encoding='utf-8-sig')
        if '@RestController' not in text: continue
        cls=re.search(r'public class (\w+)',text)
        if not cls: raise ValueError(f)
        base=re.search(r'@RequestMapping\("([^"]+)"\)',text[:cls.start()])
        base=base.group(1) if base else ''
        mappings=list(re.finditer(r'@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\((.*?)\))?',text[cls.end():],re.S))
        for m in mappings:
            annotation=m.group(2) or ''
            # Strings after produces/consumes/name are not paths.
            paths=re.findall(r'"(/[^"\n]*)"',annotation) or ['']
            after=text[cls.end()+m.end():]
            handler=re.search(r'public\s+[\w.<>,?\[\] ]+\s+(\w+)\s*\(',after)
            if not handler: raise ValueError((f,m.group(0)))
            method={'Get':'GET','Post':'POST','Put':'PUT','Delete':'DELETE','Patch':'PATCH','Request':'ANY'}[m.group(1)]
            for path in paths:
                inventory.append(dict(target=target,method=method,template=base+path,handler=cls.group(1)+'.'+handler.group(1),source=f.relative_to(ROOT).as_posix()+':'+str(text[:cls.end()+m.start()].count('\n')+1),sourceSha256=hashlib.sha256(f.read_bytes()).hexdigest()))

# Each edge is reviewed against the named real caller, not the controller name or URL prefix.
edges={}
def edge(target,method,path,action,callers,source,mode='delegated'):
    edges[(target,method,path)]=(action,callers,source,mode)
edge('app','GET','/internal/integration-credentials/{id}','integration.credential.read',['gateway'],'platform-gateway/src/main/java/cn/agentplatform/gateway/application/IntegrationAccess.java:77','workload')
edge('app','GET','/internal/integration-origins','integration.origin.check',['gateway'],'platform-gateway/src/main/java/cn/agentplatform/gateway/application/IntegrationAccess.java:170','workload')
edge('app','GET','/internal/apps/{code}/candidate','app.candidate.read',['engine','monitor'],'engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:244;monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java:136')
for suffix,action,line in [('versions/{version}','app.version.read',251),('snapshot','app.snapshot.read',242),('published','app.published.read',255)]:
    edge('app','GET','/internal/apps/{code}/'+suffix,action,['engine'],f'engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:{line}')
edge('app','GET','/internal/app-drafts/{id}','app.draft.read',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/infra/TestInputClient.java:29')
edge('app','GET','/internal/prompt-templates/{code}/versions/{version}','prompt.version.read',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:868')
edge('app','GET','/internal/skills/{code}/references','skill.references.read',['skill'],'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:549')
edge('skill','GET','/internal/skills/{code}','skill.metadata.read',['app','engine'],'app-management/src/main/java/cn/agentplatform/app/application/AppService.java:916;engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:775')
edge('skill','GET','/internal/skill-versions/{id}','skill.version.read',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:517')
edge('skill','POST','/internal/skills/{code}/execute','skill.execute',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java:292')
edge('skill','GET','/internal/skill-facts/{callId}','skill.fact.read',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/infra/ExecutionRecovery.java:61')
edge('skill','POST','/internal/skill-facts/{callId}/reproduce-artifacts','skill.artifact.reproduce',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/application/ControlledReplay.java:329')
for method,path,action,line in [('GET','/{id}','tool.authority.read',57),('GET','/{id}/approval','tool.approval.read',127),('POST','/{id}/claim','tool.claim',151),('POST','/{id}/submitted','tool.submit',275),('POST','/{id}/fact','tool.fact.record',1165)]:
    edge('engine',method,'/internal/tool-calls'+path,action,['skill'],f'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:{line}')
edge('engine','POST','/internal/skill-draft-tests','skill.draft-test.start',['skill'],'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillGovernance.java:61')
edge('model','GET','/internal/model-entries/{id}','model.entry.read',['app'],'app-management/src/main/java/cn/agentplatform/app/application/AppService.java:964')
edge('model','POST','/internal/model-bindings/freeze','model.binding.freeze',['app','engine'],'app-management/src/main/java/cn/agentplatform/app/application/AppService.java:627;engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:696')
edge('model','POST','/internal/model-bindings/validate','model.binding.validate',['app'],'app-management/src/main/java/cn/agentplatform/app/application/AppService.java:753')
edge('model','POST','/internal/model/measure','model.measure',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelExchange.java:175')
edge('model','POST','/internal/model/chat','model.chat',['skill'],'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillGovernance.java:139')
edge('model','POST','/internal/model/chat/stream','model.chat-stream',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/infra/RuntimeClients.java:85')
edge('model','POST','/internal/statistics/query','statistics.query',['monitor'],'monitor/src/main/java/cn/agentplatform/monitor/application/StatisticsService.java:101')
edge('monitor','POST','/internal/datasets/promotions','dataset.promote',['engine'],'engine-runtime/src/main/java/cn/agentplatform/engine/infra/TestInputClient.java:33')
edge('monitor','GET','/internal/evaluations/accepted','evaluation.accepted.read',['app'],'app-management/src/main/java/cn/agentplatform/app/application/AppService.java:772')
edge('engine','POST','/internal/graphs/validate','graph.validate',['app'],'app-management/src/main/java/cn/agentplatform/app/application/AppService.java:736')
edge('engine','POST','/internal/evaluations/run','evaluation.run',['monitor'],'monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java:239')
edge('engine','POST','/internal/runs/{id}/approvals/{nid}','approval.decide',['monitor'],'monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java:414')
for path,action in [('approvals','approval.list'),('audit','audit.read'),('statistics','statistics.read')]:
    edge('engine','GET','/internal/'+path,action,['monitor'],'monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java:404')
edge('engine','GET','/internal/statistics/runs/{id}','statistics.run.read',['monitor'],'monitor/src/main/java/cn/agentplatform/monitor/application/StatisticsService.java:235')

integration={('app','GET','/v1/apps/{code}/input-schema'),('engine','POST','/v1/apps/{code}/runs'),('engine','POST','/v1/apps/{code}/sessions'),('engine','GET','/v1/apps/{code}/sessions/{id}'),('engine','POST','/v1/apps/{code}/sessions/{id}/turns'),('engine','GET','/v1/runs/{id}'),('engine','GET','/v1/runs/{id}/events'),('engine','GET','/v1/runs/{id}/approvals'),('engine','POST','/v1/runs/{id}/cancel'),('engine','POST','/v1/runs/{id}/approvals/{nodeId}/decision'),('skill','GET','/v1/runs/{runId}/artifacts/{id}/download')}
disabled={
('engine','POST','/internal/tool-calls/register'):('tool.register','No cross-service caller; registration is in-process'),
('app','GET','/internal/integration/{code}/{biz}'):('integration.read','No main-source caller'),
('skill','GET','/internal/statistics'):('statistics.read','No main-source caller'),
('model','GET','/internal/statistics'):('statistics.read','No main-source caller'),
('skill','POST','/internal/skill-facts/{callId}/reconcile'):('skill.reconcile','No main-source caller; later caller migration requires review'),
('skill','POST','/internal/skill-facts/{callId}/retry'):('skill.fact.deliver','No main-source caller; later caller migration requires review'),
('skill','GET','/internal/skill-facts/{callId}/reconciliation-proofs/{attemptId}'):('skill.reconciliation-proof.read','No main-source caller; later caller migration requires review'),
('skill','POST','/internal/skill-jobs/{callId}/callback'):('skill.callback.record','No approved named callback client; separate trust-domain integration pending')}
routes=[]
seen=set()
for h in inventory:
    key=(h['target'],h['method'],h['template']);seen.add(key)
    rows=[]
    if key in edges:
        action,callers,caller_source,mode=edges[key]
        for caller in callers:
            rows.append(dict(caller=caller,action=action,mode=mode,integrationAllowed=False,reason='',callerSource=caller_source))
    elif h['target']!='gateway' and h['template'].startswith(('/admin/','/v1/')):
        rows=[dict(caller='gateway',action=f"proxy.{h['target']}.{h['method'].lower()}.{h['handler']}",mode='delegated',integrationAllowed=key in integration,reason='',callerSource='platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java:34;platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java:7')]
    else:
        if key in disabled: action,reason=disabled[key]
        elif h['target']=='gateway': action,reason='ingress.gateway.'+h['handler'],'Gateway ingress/dispatch only; no service capability granted'
        else: raise ValueError(('unreviewed handler',h))
        rows=[dict(caller=None,action=action,mode='disabled',integrationAllowed=False,reason=reason,callerSource='')]
    for row in rows:
        routes.append(dict(method=h['method'],template=h['template'],caller=row['caller'],target=h['target'],action=row['action'],mode=row['mode'],integrationAllowed=row['integrationAllowed'],source=h['source'],callerSource=row['callerSource'],reason=row['reason']))
assert edges.keys() <= seen
assert disabled.keys() <= seen
assert integration <= seen
for target in ['app','engine','skill','model','monitor']:
    routes.append(dict(method='GET',template='/v3/api-docs',caller='gateway',target=target,action=f'proxy.{target}.get.openapi',mode='delegated',integrationAllowed=False,source='springdoc-openapi-starter-webmvc-api:2.8.9 (conditional framework handler)',callerSource='platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java:127',reason=''))
(OUT/'controller-inventory.json').write_text(json.dumps(inventory,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
dest=ROOT/'admin-common/src/main/resources/agent-service-authority.json';dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(routes,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
print(json.dumps(dict(controller_templates=len(inventory),capability_rows=len(routes),enabled=sum(r['mode']!='disabled' for r in routes),disabled=sum(r['mode']=='disabled' for r in routes),integration_templates=len(integration)),indent=2))
