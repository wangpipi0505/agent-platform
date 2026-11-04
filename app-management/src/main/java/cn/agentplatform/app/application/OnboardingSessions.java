package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.*;
import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.common.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** QUERY接入复用现有技能/应用/运行/发布门；会话收据不能替代任何真实执行或质量裁决。 */
@Service
public class OnboardingSessions {
    private final OnboardingSessionRepository sessions;
    private final OnboardingTemplateRepository templates;
    private final CapabilityPackageRepository packages;
    private final CapabilitySkillClient skills;
    private final CapabilityPackageApplicationsService assembly;
    private final AppService apps;
    private final ApprovedConnections connections;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final OnboardingRuntimeClient runtime;
    private OnboardingApplicationSources applicationSources;
    private OnboardingImportSourceRepository importSources;
    private OnboardingSkillImportsClient skillImports;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void importServices(OnboardingApplicationSources sources,OnboardingImportSourceRepository repository,OnboardingSkillImportsClient client){
        this.applicationSources=sources;this.importSources=repository;this.skillImports=client;
    }
    public OnboardingSessions(OnboardingSessionRepository sessions,OnboardingTemplateRepository templates,
            CapabilityPackageRepository packages,CapabilitySkillClient skills,CapabilityPackageApplicationsService assembly,
            AppService apps,ApprovedConnections connections,org.springframework.jdbc.core.JdbcTemplate db,OnboardingRuntimeClient runtime) {
        this.sessions=sessions;this.templates=templates;this.packages=packages;this.skills=skills;this.assembly=assembly;
        this.apps=apps;this.connections=connections;this.db=db;this.runtime=runtime;
    }
    private Actor actor(){var actor=Actor.current();actor.requireRole("EDITOR");return actor;}
    public List<Map<String,Object>> templates(){var actor=actor();return templates.list(actor,null,"QUERY").stream()
            .filter(row->"PUBLISHED".equals(row.get("status"))).map(row->Map.<String,Object>of("code",row.get("code"),"name",row.get("name"),"kind","QUERY","status","PUBLISHED","currentRev",row.get("templateVersion"))).toList();}
    public List<Map<String,Object>> list(){var actor=actor();return sessions.list(actor).stream().map(this::publicView).toList();}
    public Map<String,Object> get(String id){actor();return view(sessions.get(Actor.current(),id));}
    public Map<String,Object> create(Map<String,Object> body){
        var actor=actor();keys(body,Set.of("templateCode","templateRev","appCode","appName","requestId"));
        String code=text(body,"templateCode"),appCode=text(body,"appCode"),name=text(body,"appName");
        if(!appCode.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||name.length()>128)throw bad("应用编码或名称无效");
        int rev=positive(body.get("templateRev"));var template=template(actor,code,rev);String requestId=request(body);
        var seed=new LinkedHashMap<String,Object>();seed.put("sessionId","os_"+Digest.sha256(actor.tenantId()+":"+actor.principalId()+":"+requestId).substring(0,32));
        seed.put("templateCode",code);seed.put("templateRev",rev);seed.put("appCode",appCode);seed.put("appName",name);seed.put("template",template);
        var source=source(seed);Map<String,Object> schema=source.operations.isEmpty()?Map.of("type","object"):map(source.operations.getFirst().get("inputSchema"));
        var plan=new LinkedHashMap<String,Object>();plan.put("fieldsBundle",OnboardingPlan.fields(schema));
        var values=new LinkedHashMap<String,Object>();map(schema.getOrDefault("properties",Map.of())).forEach((key,value)->{var property=map(value);if(property.containsKey("default"))values.put(key,property.get("default"));});
        plan.put("values",values);plan.put("selectedOperations",source.operations.isEmpty()?List.of():List.of(source.operations.getFirst().get("operationId")));
        var mapping=map(source.stored.getOrDefault("resolvedMapping",Map.of()));String routeId=Objects.toString(mapping.get("routeProfileId"),"");
        if(routeId.isEmpty()){var routes=AppService.objects(map(source.stored.get("declaration")).get("routeProfiles"));if(routes.size()==1)routeId=routes.getFirst().get("id").toString();}
        if(!routeId.isEmpty())plan.put("routePlan",Map.of("environment",source.environment,"routeProfileId",routeId));
        var choices=choices(seed,source);var allowed=(List<?>)choices.get("connections");if(!allowed.isEmpty())plan.put("connectionRef",map(allowed.getFirst()).get("ref"));
        plan= new LinkedHashMap<>(OnboardingPlan.normalize(plan));seed.put("plan",plan);seed.put("planSha256",planSha(seed,plan));
        String sha=Digest.sha256(Json.canonical(body));return view(sessions.create(actor,seed,requestId,sha));
    }
    public Map<String,Object> save(String id,Map<String,Object> body){
        var actor=actor();keys(body,Set.of("expectedRev","requestId","plan"));var row=sessions.get(actor,id);template(actor,row.get("templateCode").toString(),((Number)row.get("templateRev")).intValue());
        var plan=OnboardingPlan.normalize(map(body.get("plan")));return view(sessions.save(actor,id,positive(body.get("expectedRev")),plan,planSha(row,plan),request(body),Digest.sha256(Json.canonical(body))));
    }
    public Map<String,Object> cancel(String id,Map<String,Object> body){var actor=actor();keys(body,Set.of("expectedRev","requestId","reason"));if(!(body.get("reason") instanceof String reason)||reason.isBlank()||reason.length()>512)throw bad("取消原因必须为1至512字符");return view(sessions.cancel(actor,id,positive(body.get("expectedRev")),request(body),Digest.sha256(Json.canonical(body)),reason.trim()));}
    public Map<String,Object> preview(String id,Map<String,Object> body){
        var actor=actor();keys(body,Set.of("expectedRev","requestId"));var row=sessions.get(actor,id);OnboardingSessionRepository.editable(row,positive(body.get("expectedRev")));request(body);
        return preview(row);
    }
    private Map<String,Object> preview(Map<String,Object> row){
        var missing=new ArrayList<Map<String,Object>>();Source source=source(row);var plan=map(row.get("plan"));
        if(!"RESOLVED".equals(source.stored.get("bindingStatus")))missing.add(missing("ONBOARDING_SKILL_IMPORT_REQUIRED","请导入本计划技能草稿，在原技能工作台完成验证发布后回此页继续"));
        try {var selected=OnboardingPlan.selectOperations(plan,source.operations);if(selected.size()!=1)throw bad("当前QUERY接入一次选择一个标准只读操作");
            var operation=selected.getFirst();var args=OnboardingPlan.arguments(plan);AppRules.validateValue(map(operation.get("inputSchema")),args,"onboarding.arguments",false);
            requireConnection(plan,operation);validateReturns(plan,operation);
        }catch(PlatformException|IllegalArgumentException failure){missing.add(missing(failure instanceof PlatformException pe?pe.code():"INVALID_INPUT",failure.getMessage()));}
        var out=new LinkedHashMap<String,Object>();out.put("ready",missing.isEmpty());out.put("missing",missing);out.put("planSha256",row.get("planSha256"));
        out.put("operations",source.operations.stream().map(this::publicOperation).toList());out.put("choices",choices(row,source));
        if(source.stored.containsKey("sessionSourceId"))out.put("importReady",true);
        else if(hasDocument(row)||!"RESOLVED".equals(source.stored.get("bindingStatus"))){
            try{importPreparation(row);out.put("importReady",true);}catch(PlatformException|IllegalArgumentException failure){out.put("importReady",false);missing.add(missing(failure instanceof PlatformException pe?pe.code():"INVALID_INPUT",failure.getMessage()));}
        }
        out.put("ready",missing.isEmpty());
        var imported=step(row,"IMPORT_SKILLS");boolean hasDrafts=imported!=null&&!AppService.objects(map(imported.get("receipt")).get("drafts")).isEmpty();
        out.put("resourcePlan",Map.of("packageVersionId",source.stored.get("packageVersionId"),"bindingStatus",source.stored.get("bindingStatus"),"applicationType","FLOW","appCode",row.get("appCode"),"reusePublishedSkills",true,
                "missingSkillDrafts",!"RESOLVED".equals(source.stored.get("bindingStatus"))&&!hasDrafts,"skillPublicationRequired",!"RESOLVED".equals(source.stored.get("bindingStatus"))&&hasDrafts,"skillImportPath","/admin/capability-packages/"+source.stored.get("packageVersionId")));
        out.put("links",links(row));return out;
    }
    public Map<String,Object> execute(String id,String step,Map<String,Object> body){
        var actor=actor();if(!Set.of("IMPORT_SKILLS","ASSEMBLE","VERIFY","PUBLISH").contains(step))throw bad("未知接入步骤");
        keys(body,Set.of("expectedRev","requestId","planSha256","evaluation","evaluationEnvironmentConfirmation"));String requestId=request(body),sha=Digest.sha256(Json.canonical(List.of(step,body)));
        var row=sessions.get(actor,id);var replay=sessions.replay(actor,id,requestId,sha);if(replay!=null)return view(replay);
        if(!Objects.equals(row.get("planSha256"),body.get("planSha256")))throw new PlatformException(409,"ONBOARDING_PLAN_CHANGED","接入计划已变化，请先重新预检");
        if("IMPORT_SKILLS".equals(step))return importSkills(row,body,requestId,sha);
        var checked=preview(row);if(!Boolean.TRUE.equals(checked.get("ready")))return withPreview(view(row),checked);
        Source source=source(row);var selected=OnboardingPlan.selectOperations(map(row.get("plan")),source.operations).getFirst();
        if("ASSEMBLE".equals(step)){
            var input=applicationPlan(row,source,selected);
            var prepared=source.stored.containsKey("sessionSourceId")?assembly.prepareForOnboardingSource(row,input)
                    :assembly.prepareForSession(((Number)source.stored.get("packageVersionId")).longValue(),input);
            return view(sessions.atomic(()->{var current=sessions.lock(actor,id);var prior=sessions.replay(actor,id,requestId,sha);if(prior!=null)return prior;
                OnboardingSessionRepository.editable(current,positive(body.get("expectedRev")));sessions.requireIdle(actor,id);
                if(!row.get("planSha256").equals(current.get("planSha256")))throw new PlatformException(409,"ONBOARDING_PLAN_CHANGED","组装期间计划已变化");
                var previous=step(current,"ASSEMBLE");if(previous!=null&&"SUCCEEDED".equals(previous.get("status")))return sessions.remember(actor,id,requestId,sha,current);
                var app=assembly.assembleForSession(prepared,current.containsKey("application")?map(current.get("application")):null);var summary=application(app);
                String token=UUID.randomUUID().toString();sessions.start(actor,id,row.get("planSha256").toString(),step,requestId,sha,token);
                sessions.application(actor,id,summary,"ASSEMBLED");sessions.finish(actor,id,row.get("planSha256").toString(),step,token,"SUCCEEDED",Map.of("application",summary,"sourcePackageSha256",source.stored.get("sha256")),null);
                return sessions.remember(actor,id,requestId,sha,sessions.get(actor,id));}));
        }
        if(!row.containsKey("application")||step(row,"ASSEMBLE")==null||!"SUCCEEDED".equals(step(row,"ASSEMBLE").get("status")))throw new PlatformException(409,"ONBOARDING_ASSEMBLY_REQUIRED","请先组装当前计划的真实应用草稿");
        final var claim=sessions.atomic(()->{var current=sessions.lock(actor,id);var existing=step(current,step);
            var prior=sessions.replay(actor,id,requestId,sha);if(prior!=null)return Map.<String,Object>of("replayed",prior);
            if(existing!=null&&requestId.equals(existing.get("requestId"))&&!sha.equals(existing.get("requestSha256")))throw new PlatformException(409,"CONFIG_REV_CONFLICT","同一步requestId载荷不同");
            boolean same=existing!=null&&requestId.equals(existing.get("requestId"));if(!same)OnboardingSessionRepository.editable(current,positive(body.get("expectedRev")));
            if(existing!=null&&"SUCCEEDED".equals(existing.get("status")))return Map.<String,Object>of("replayed",sessions.remember(actor,id,requestId,sha,current));
            if(AppService.objects(current.get("steps")).stream().anyMatch(value->!step.equals(value.get("step"))&&"RUNNING".equals(value.get("status"))))throw new PlatformException(409,"ONBOARDING_STEP_RUNNING","请先继续原在途步骤");
            if(existing!=null&&"RUNNING".equals(existing.get("status"))&&existing.get("receipt")==null
                    &&((java.sql.Timestamp)existing.get("leaseUntil")).toInstant().isAfter(Instant.now()))return Map.<String,Object>of("replayed",current);
            String token=UUID.randomUUID().toString();sessions.start(actor,id,current.get("planSha256").toString(),step,requestId,sha,token);
            var claimed=new LinkedHashMap<String,Object>();claimed.put("token",token);claimed.put("session",current);if(existing!=null&&existing.get("receipt")!=null)claimed.put("receipt",existing.get("receipt"));return claimed;});
        if(claim.containsKey("replayed"))return view(map(claim.get("replayed")));
        String token=claim.get("token").toString();Map<String,Object> receipt=claim.containsKey("receipt")?new LinkedHashMap<>(map(claim.get("receipt"))):null,error=null;String state="FAILED",sessionState="ASSEMBLED";
        try {
            var app=apps.get(row.get("appCode").toString());var owned=map(row.get("application"));
            boolean publicationRecovery="PUBLISH".equals(step)&&receipt!=null&&receipt.get("commitRequest") instanceof Map<?,?>;
            if(((Number)app.get("id")).longValue()!=((Number)owned.get("appId")).longValue()||!publicationRecovery&&((Number)app.get("draftRev")).longValue()!=((Number)owned.get("draftRev")).longValue())
                throw new PlatformException(409,"ONBOARDING_APPLICATION_CHANGED","应用已由人工修改，请在原工作台完成核验，未覆盖修改");
            if("VERIFY".equals(step)){
                var previous=map(claim.getOrDefault("receipt",Map.of()));String runId=Objects.toString(previous.get("runId"),"");
                var result=runId.isBlank()?runtime.verify(Map.of("appCode",row.get("appCode"),"mode","FULL","inputs",map(map(row.get("plan")).get("values")),"requestId","onb-"+id+"-"+row.get("planSha256"))):runtime.status(runId);
                runId=Objects.toString(result.get("runId"),Objects.toString(result.get("id"),runId));if(runId.isBlank())throw new PlatformException(502,"ONBOARDING_RUN_INVALID","真实验证未返回runId");
                receipt=new LinkedHashMap<>();receipt.put("runId",runId);receipt.put("run",result);receipt.put("links",links(row));
                String status=Objects.toString(result.get("status"),"RUNNING");state=Set.of("SUCCEEDED","COMPLETED").contains(status)?"SUCCEEDED":Set.of("FAILED","CANCELLED","UNKNOWN").contains(status)?"FAILED":"RUNNING";
                if("SUCCEEDED".equals(state)){
                    sessionState="VERIFIED";
                    if(body.get("evaluation") instanceof Map<?,?> evaluation){var request=new LinkedHashMap<String,Object>(map(evaluation));request.put("appCode",app.get("code"));request.put("draftRev",app.get("draftRev"));request.put("candidateSha256",app.get("candidateSha256"));receipt.put("evaluation",runtime.evaluate(request));}
                    else receipt.put("missing",List.of(missing("EVALUATION_REQUIRED","请在原应用工作台完成候选评测；发布门禁仍将核验真实报告")));
                } else if("FAILED".equals(state))error=missing("ONBOARDING_VERIFICATION_FAILED","原验证运行未成功，请查看真实运行回执");
            }else{
                var verified=step(row,"VERIFY");if(verified==null||!"SUCCEEDED".equals(verified.get("status")))throw new PlatformException(409,"ONBOARDING_VERIFICATION_REQUIRED","当前计划尚未真实验证成功");
                Map<String,Object> commit;
                if(publicationRecovery)commit=map(receipt.get("commitRequest"));
                else {
                    var precheck=apps.precheck(row.get("appCode").toString());
                    if(!Boolean.TRUE.equals(precheck.get("passed")))throw new PlatformException(409,"EVAL_GATE_BLOCKED","原应用发布预检未通过");
                    commit=new LinkedHashMap<>();for(String key:List.of("precheckId","draftRev","candidateSha256"))commit.put(key,precheck.get(key));
                    commit.put("idempotencyRequestNo","onb-"+id+"-"+row.get("planSha256"));if(body.containsKey("evaluationEnvironmentConfirmation"))commit.put("evaluationEnvironmentConfirmation",body.get("evaluationEnvironmentConfirmation"));
                    var durable=Map.<String,Object>of("commitRequest",commit);
                    sessions.atomic(()->{sessions.lock(actor,id);sessions.finish(actor,id,row.get("planSha256").toString(),step,token,"RUNNING",durable,null);return null;});
                    receipt=new LinkedHashMap<>(durable);
                }
                var published=apps.commit(row.get("appCode").toString(),commit);
                if(!(published.get("versionNo") instanceof Number version)||version.intValue()<1)throw new PlatformException(502,"ONBOARDING_PUBLICATION_INVALID","发布未返回可用正式应用版本");
                receipt.put("publication",published);receipt.put("handover",Map.of("templateCode",row.get("templateCode"),"appCode",row.get("appCode"),"appVersion",version.intValue(),"launchPath","/w/"+row.get("appCode")));
                state="SUCCEEDED";sessionState="PUBLISHED";
            }
        }catch(PlatformException ex){state="FAILED";if("PRECHECK_INVALID".equals(ex.code())&&receipt!=null)receipt.remove("commitRequest");error=missing(ex.code(),ex.getMessage());}catch(Exception ex){state="FAILED";error=missing("ONBOARDING_STEP_FAILED","步骤未完成，请读取原会话回执后重试");}
        final String finalState=state,finalSessionState=sessionState;final Map<String,Object> finalReceipt=receipt,finalError=error;
        return view(sessions.atomic(()->{var current=sessions.lock(actor,id);sessions.finish(actor,id,row.get("planSha256").toString(),step,token,finalState,finalReceipt,finalError);
            if("SUCCEEDED".equals(finalState))sessions.application(actor,id,map(current.get("application")),finalSessionState);
            var out=sessions.get(actor,id);if(!"RUNNING".equals(finalState))sessions.remember(actor,id,requestId,sha,out);return out;}));
    }
    private record Source(Map<String,Object> stored,List<Map<String,Object>> operations,String environment){}
    private Source source(Map<String,Object> row){
        var actor=Actor.current();var frozen=map(row.get("template"));template(actor,row.get("templateCode").toString(),((Number)row.get("templateRev")).intValue());
        var stored=new LinkedHashMap<>(packages.bindingSource(((Number)frozen.get("sourcePackageVersionId")).longValue(),actor.tenantId()));
        if(!Objects.equals(frozen.get("sourcePackageSha256"),stored.get("sha256")))throw new PlatformException(409,"CAPABILITY_VERSION_CONFLICT","模板能力包摘要已变化");
        if(applicationSources!=null&&row.containsKey("planSha256")){var resolved=applicationSources.resolved(row);if(resolved!=null){stored.put("resolvedMapping",resolved);stored.put("sessionSourceId",resolved.get("sessionSourceId"));stored.put("bindingStatus","RESOLVED");}
            else if(hasDocument(row)){stored.put("resolvedMapping",Map.of());stored.put("bindingStatus","UNRESOLVED");}}
        var declaration=map(stored.get("declaration"));var bindings=AppService.objects(map(stored.getOrDefault("resolvedMapping",Map.of())).get("bindings"));var operations=new ArrayList<Map<String,Object>>();long deadline=System.nanoTime()+5_000_000_000L;
        String environment=Objects.toString(map(frozen.get("integration")).get("environment"),"production");
        for(var tool:AppService.objects(declaration.get("tools"))){
            var operation=new LinkedHashMap<String,Object>();String operationId=Objects.toString(tool.get("operation"),tool.get("id").toString());operation.put("operationId",operationId);operation.put("toolId",tool.get("id"));operation.put("supported",false);operation.put("inputSchema",tool.getOrDefault("inputSchema",Map.of("type","object")));
            operation.put("environment",environment);
            try {
                var binding=bindings.stream().filter(value->Objects.equals(value.get("toolId"),tool.get("id"))).findFirst().orElseThrow(()->new PlatformException(409,"CAPABILITY_BINDING_REQUIRED","操作尚未绑定精确发布技能"));
                var actual=stored.containsKey("sessionSourceId")?skillImports.version(binding.get("skillCode").toString(),((Number)binding.get("version")).intValue())
                        :skills.version(binding.get("skillCode").toString(),((Number)binding.get("version")).intValue(),actor,deadline);var definition=map(actual.get("definition"));var policy=map(definition.get("execution"));
                if(!Objects.equals(binding.get("sha256"),actual.get("sha256"))||((Number)binding.get("skillVersionId")).longValue()!=((Number)actual.get("skillVersionId")).longValue()||!Boolean.TRUE.equals(policy.get("readonly"))||Boolean.TRUE.equals(policy.get("sideEffect"))||!"HTTP".equals(definition.get("type"))||!"SYNC".equals(definition.getOrDefault("invocationKind","SYNC")))throw new PlatformException(409,"ONBOARDING_OPERATION_UNSUPPORTED","当前QUERY接入只接受精确已发布标准同步只读HTTP技能");
                var guard=map(definition.get("guard"));var roles=(List<?>)guard.getOrDefault("roles",List.of());var attributes=map(guard.getOrDefault("attributes",Map.of()));
                if(roles.isEmpty()&&attributes.isEmpty()||roles.stream().anyMatch(role->!actor.roles().contains("ADMIN")&&!actor.roles().contains(role.toString())))throw new PlatformException(403,"NO_PERMISSION","当前用户未获授该技能角色");
                for(var scope:attributes.entrySet()){var granted=actor.attributes().getOrDefault(scope.getKey(),Set.of());var declared=(Collection<?>)scope.getValue();if(granted.isEmpty()||!declared.isEmpty()&&declared.stream().noneMatch(value->granted.contains(value.toString())))throw new PlatformException(403,"NO_PERMISSION","当前用户未获授该技能范围");}
                operation.put("definition",definition);operation.put("binding",binding);operation.put("inputSchema",definition.get("inputSchema"));operation.put("supported",true);operation.put("method",definition.getOrDefault("httpMethod","POST"));
            }catch(PlatformException ex){operation.put("reasons",List.of(ex.getMessage()));}
            operations.add(operation);
        }
        return new Source(stored,operations,Objects.toString(map(frozen.get("integration")).get("environment"),"production"));
    }
    private Map<String,Object> choices(Map<String,Object> session,Source source){
        var allowed=new ArrayList<Map<String,Object>>();for(var operation:source.operations){if(!Boolean.TRUE.equals(operation.get("supported")))continue;var binding=map(operation.get("binding"));String grantee="skill."+binding.get("skillCode");
            for(var row:db.queryForList("select p.profile_id,p.current_rev,r.binding_id,r.binding_rev,r.environment from app.connection_profile p join app.connection_profile_version v on v.tenant_id=p.tenant_id and v.profile_id=p.profile_id and v.profile_rev=p.current_rev join app.connection_profile_grant g on g.tenant_id=p.tenant_id and g.profile_id=p.profile_id and g.profile_rev=p.current_rev join app.environment_binding_revision r on r.tenant_id=p.tenant_id and r.connection_profile_id=p.profile_id and r.connection_profile_rev=p.current_rev join app.environment_binding b on b.tenant_id=r.tenant_id and b.binding_id=r.binding_id and b.current_rev=r.binding_rev where p.tenant_id=? and p.status='APPROVED' and b.status='APPROVED' and g.grantee=? and g.revoked_at is null and g.allowed_uses @> '[\"read\"]'::jsonb and v.allowed_uses @> '[\"read\"]'::jsonb and v.operation_allowlist @> ?::jsonb and r.environment=? order by p.profile_id,r.binding_id",Actor.current().tenantId(),grantee,Json.write(List.of(operation.get("operationId"))),source.environment)){
                var ref=Map.<String,Object>of("profileId",row.get("profile_id"),"profileRev",row.get("current_rev"),"bindingId",row.get("binding_id"),"bindingRev",row.get("binding_rev"),"operation",operation.get("operationId"),"environment",row.get("environment"));
                allowed.add(Map.of("label",row.get("profile_id")+" / "+row.get("environment")+" / "+operation.get("operationId"),"ref",ref));
            }
        }
        if(!"RESOLVED".equals(source.stored.get("bindingStatus"))||hasDocument(session))for(var candidate:importProposals(session,source)){
            var display=new LinkedHashMap<>(candidate);display.remove("proof");if(allowed.stream().noneMatch(item->Json.canonical(item.get("ref")).equals(Json.canonical(display.get("ref")))))allowed.add(display);
        }
        return Map.of("connections",allowed,"models",List.of(),"operations",source.operations.stream().map(this::publicOperation).toList());
    }
    private void requireConnection(Map<String,Object> plan,Map<String,Object> operation){
        var ref=map(plan.get("connectionRef"));if(!Objects.equals(ref.get("operation"),operation.get("operationId"))||!Objects.equals(ref.get("environment"),operation.get("environment")))throw bad("批准连接操作或环境与当前模板选择不一致");
        var binding=map(operation.get("binding"));var facts=connections.resolve(text(ref,"profileId"),positive(ref.get("profileRev")),text(ref,"environment"),text(ref,"bindingId"),positive(ref.get("bindingRev")),"skill."+binding.get("skillCode"));
        if(!((List<?>)facts.get("operationAllowlist")).contains(ref.get("operation"))||!((List<?>)facts.get("allowedUses")).contains("read")||!((List<?>)facts.get("grantedUses")).contains("read"))throw new PlatformException(403,"CONNECTION_USE_NOT_GRANTED","当前精确技能未获授此连接的读取用途");
    }
    private record ImportPreparation(Map<String,Object> packageSource,Map<String,Object> prepared,Map<String,Object> request){}
    private ImportPreparation importPreparation(Map<String,Object> row){
        if(applicationSources==null||importSources==null||skillImports==null)throw new PlatformException(503,"ONBOARDING_SOURCE_UNAVAILABLE","接入技能导入来源服务未装配");
        var frozen=importSources.get(Actor.current(),row.get("sessionId").toString(),row.get("planSha256").toString());
        if(frozen!=null){var snapshot=map(frozen.get("snapshot"));return new ImportPreparation(map(snapshot.get("package")),map(snapshot.get("prepared")),map(snapshot.get("importRequest")));}
        var plan=map(row.get("plan"));String document=Objects.toString(plan.get("sourceDocument"),"");if(document.isBlank())throw new PlatformException(409,"ONBOARDING_IMPORT_DOCUMENT_REQUIRED","请提供原始OpenAPI规格，不从摘要或端点猜造规格");
        var source=source(row);var ref=map(plan.get("connectionRef"));
        var proposal=importProposals(row,source).stream().filter(candidate->Json.canonical(candidate.get("ref")).equals(Json.canonical(ref))).findFirst()
                .orElseThrow(()->new PlatformException(403,"ONBOARDING_IMPORT_CONNECTION_FORBIDDEN","当前没有获授的模板发布快照连接提议，请管理员准备连接"));
        var facts=connections.resolve(text(ref,"profileId"),positive(ref.get("profileRev")),text(ref,"environment"),text(ref,"bindingId"),positive(ref.get("bindingRev")),null);
        if(!"NONE".equals(facts.get("authKind")))Actor.current().requireRole("ADMIN");
        var preview=skillImports.preview(document);
        @SuppressWarnings("unchecked")var selected=(List<String>)plan.get("selectedOperations");
        if(selected==null||selected.size()!=1||!Objects.equals(selected.getFirst(),ref.get("operation")))throw bad("当前QUERY导入只选择一个与批准提议相同的原生操作");
        var overrides=new LinkedHashMap<>(map(plan.get("importOverrides")));
        if("BEARER".equals(facts.get("authKind"))){
            Actor.current().requireRole("ADMIN");var binding=AppService.objects(facts.get("bindings")).getFirst();
            if(!(binding.get("secretRef") instanceof String secretRef)||secretRef.isBlank()||secretRef.length()>512)throw bad("批准绑定缺少有效secret_ref");
            for(String operation:selected){var configured=new LinkedHashMap<>(map(overrides.get(operation)));configured.put("authRef",secretRef);overrides.put(operation,configured);}
        }
        var prepared=new LinkedHashMap<>(OnboardingImportRules.prepare(document,preview,map(source.stored.get("declaration")),map(plan.get("routePlan")),selected,facts,overrides));
        var provenance=new LinkedHashMap<>(map(prepared.get("provenance")));provenance.put("importProposal",proposal.get("proof"));prepared.put("provenance",provenance);
        var request=new LinkedHashMap<String,Object>();request.put("document",document);for(String key:List.of("defaults","overrides","includeOps"))request.put(key,prepared.get(key));
        var refs=new ArrayList<Map<String,Object>>();for(String operation:selected){var fixed=new LinkedHashMap<>(ref);fixed.put("operation",operation);fixed.remove("resolutionDigest");refs.add(fixed);}request.put("bindingRefs",refs);
        var coordinates=new LinkedHashMap<String,Object>();for(String key:List.of("sessionId","planSha256","templateCode","templateRev"))coordinates.put(key,row.get(key));
        coordinates.put("packageVersionId",source.stored.get("packageVersionId"));coordinates.put("packageSha256",source.stored.get("sha256"));coordinates.put("documentSha256",prepared.get("documentSha256"));request.put("source",coordinates);
        return new ImportPreparation(packages.bindingSource(((Number)source.stored.get("packageVersionId")).longValue(),Actor.current().tenantId()),prepared,request);
    }
    private Map<String,Object> importSkills(Map<String,Object> row,Map<String,Object> body,String requestId,String sha){
        var actor=Actor.current();String id=row.get("sessionId").toString(),planSha=row.get("planSha256").toString();
        final ImportPreparation preparation;
        try{preparation=importPreparation(row);}catch(PlatformException ex){var out=view(row);out.put("preview",Map.of("ready",false,"importReady",false,"missing",List.of(missing(ex.code(),ex.getMessage()))));return out;}
        var frozen=applicationSources.prepare(row,preparation.packageSource,preparation.prepared,preparation.request);
        var frozenRequest=map(map(frozen.get("snapshot")).get("importRequest"));
        var claim=sessions.atomic(()->{var current=sessions.lock(actor,id);var replay=sessions.replay(actor,id,requestId,sha);if(replay!=null)return Map.<String,Object>of("replayed",replay);
            var previous=step(current,"IMPORT_SKILLS");boolean same=previous!=null&&requestId.equals(previous.get("requestId"));if(!same)OnboardingSessionRepository.editable(current,positive(body.get("expectedRev")));
            if(previous!=null&&same&&!sha.equals(previous.get("requestSha256")))throw new PlatformException(409,"CONFIG_REV_CONFLICT","同一导入requestId载荷不同");
            if(!planSha.equals(current.get("planSha256")))throw new PlatformException(409,"ONBOARDING_PLAN_CHANGED","导入期间计划已变化");
            if(previous!=null&&"RUNNING".equals(previous.get("status"))&&((java.sql.Timestamp)previous.get("leaseUntil")).toInstant().isAfter(Instant.now()))return Map.<String,Object>of("replayed",current);
            if(previous!=null&&"RUNNING".equals(previous.get("status")))sessions.finish(actor,id,planSha,"IMPORT_SKILLS",previous.get("executionToken").toString(),"FAILED",map(previous.get("receipt")),missing("ONBOARDING_LEASE_EXPIRED","旧导入代次已到期；仅回查原幂等导入"));
            sessions.requireIdle(actor,id);String token=UUID.randomUUID().toString();sessions.start(actor,id,planSha,"IMPORT_SKILLS",requestId,sha,token);return Map.<String,Object>of("token",token);});
        if(claim.containsKey("replayed"))return view(map(claim.get("replayed")));
        String token=claim.get("token").toString(),status="FAILED";Map<String,Object> receipt=null,error=null;
        try{
            Map<String,Object> imported;
            try{imported=skillImports.get(id,planSha);}catch(PlatformException ex){if(ex.status()!=404||!"ONBOARDING_IMPORT_NOT_FOUND".equals(ex.code()))throw ex;recheckImportCreation(row,frozen);imported=skillImports.create(frozenRequest);}
            var drafts=AppService.objects(imported.get("drafts"));var actualPublished=new ArrayList<Map<String,Object>>();var pending=new ArrayList<Map<String,Object>>();var visibleDrafts=new ArrayList<Map<String,Object>>();long deadline=System.nanoTime()+5_000_000_000L;
            for(var draft:drafts){var visible=new LinkedHashMap<String,Object>();for(String key:List.of("operationId","code","version","skillVersionId","sha256","status"))if(draft.containsKey(key))visible.put(key,draft.get(key));
                String code=text(draft,"code");visible.put("workspacePath","#/skills/"+code);visibleDrafts.add(visible);
                try{var actual=skillImports.version(code,positive(draft.get("version")));actualPublished.add(actual);visible.put("status","PUBLISHED");}
                catch(PlatformException ex){if(!Set.of("SKILL_NOT_PUBLISHED","SKILL_VERSION_NOT_FOUND").contains(ex.code()))throw ex;pending.add(missing("ONBOARDING_SKILL_PUBLICATION_REQUIRED","请管理员授予新技能连接用途，并在原技能工作台完成连接测试、选型验收和发布: "+code));}}
            if(drafts.isEmpty())throw new PlatformException(502,"ONBOARDING_IMPORT_RECEIPT_INVALID","导入没有返回真实技能草稿");
            boolean resolved=pending.isEmpty();if(resolved)applicationSources.resolve(row,actualPublished);
            receipt=new LinkedHashMap<>();receipt.put("drafts",visibleDrafts);receipt.put("missing",pending);receipt.put("sourceResolved",resolved);
            receipt.put("links",Map.of("skillWorkspaces",visibleDrafts.stream().map(draft->Map.of("code",draft.get("code"),"path",draft.get("workspacePath"))).toList()));
            status="SUCCEEDED";
        }catch(PlatformException ex){error=missing(ex.code(),ex.getMessage());}catch(Exception ex){error=missing("ONBOARDING_IMPORT_FAILED","导入尚未完成，请按原计划回查真实回执");}
        final String finalStatus=status;final Map<String,Object> finalReceipt=receipt,finalError=error;
        return view(sessions.atomic(()->{sessions.lock(actor,id);sessions.finish(actor,id,planSha,"IMPORT_SKILLS",token,finalStatus,finalReceipt,finalError);return sessions.remember(actor,id,requestId,sha,sessions.get(actor,id));}));
    }
    /** EDITOR只从模板真实发布快照+旧技能当前read授权获得元数据提议；新技能执行授权独立。 */
    private List<Map<String,Object>> importProposals(Map<String,Object> session,Source source){
        var actor=Actor.current();var result=new ArrayList<Map<String,Object>>();
        var operations=new LinkedHashSet<String>();for(var tool:AppService.objects(map(source.stored.get("declaration")).get("tools")))if("READ_ONLY".equals(tool.get("exposure")))operations.add(Objects.toString(tool.get("operation"),""));
        if(actor.roles().contains("ADMIN")){
            for(var operation:operations)for(var row:db.queryForList("select p.profile_id,p.current_rev,r.binding_id,r.binding_rev,r.environment from app.connection_profile p join app.connection_profile_version v on v.tenant_id=p.tenant_id and v.profile_id=p.profile_id and v.profile_rev=p.current_rev join app.environment_binding_revision r on r.tenant_id=p.tenant_id and r.connection_profile_id=p.profile_id and r.connection_profile_rev=p.current_rev join app.environment_binding b on b.tenant_id=r.tenant_id and b.binding_id=r.binding_id and b.current_rev=r.binding_rev where p.tenant_id=? and p.status='APPROVED' and b.status='APPROVED' and v.protocol='HTTP_SYNC' and v.allowed_uses @> '[\"read\"]'::jsonb and v.operation_allowlist @> ?::jsonb and r.environment=? order by p.profile_id,r.binding_id",actor.tenantId(),Json.write(List.of(operation)),source.environment)){
                var ref=Map.<String,Object>of("profileId",row.get("profile_id"),"profileRev",row.get("current_rev"),"bindingId",row.get("binding_id"),"bindingRev",row.get("binding_rev"),"operation",operation,"environment",row.get("environment"));
                result.add(Map.of("label",row.get("profile_id")+" / "+operation+"（仅导入提议）","ref",ref,"metadataOnly",true,"proof",Map.of("kind","ADMIN_APPROVED_PROPOSAL","ref",ref)));}
            return result;
        }
        try{
            String baseCode=text(map(session.get("template")),"appCode");var published=apps.published(baseCode);var publishedId=(Number)published.get("appVersionId");
            var coordinates=db.queryForMap("select version_no,candidate_sha256 from app.app_version where tenant_id=? and id=? and app_code=?",actor.tenantId(),publishedId.longValue(),baseCode);
            var config=map(published.get("config"));long deadline=System.nanoTime()+5_000_000_000L;
            for(var oldRef:AppService.objects(config.get("bindingRefs"))){if(!operations.contains(Objects.toString(oldRef.get("operation"),""))||!source.environment.equals(oldRef.get("environment")))continue;
                for(var skill:AppService.objects(config.get("skillRefs"))){if(skillImports==null)continue;var actual=skillImports.version(text(skill,"code"),positive(skill.get("version")));var definition=map(actual.get("definition"));var execution=map(definition.get("execution"));
                    if(!Boolean.TRUE.equals(execution.get("readonly"))||Boolean.TRUE.equals(execution.get("sideEffect"))||!"HTTP".equals(definition.get("type")))continue;
                    var guard=map(definition.get("guard"));var roles=(Collection<?>)guard.getOrDefault("roles",List.of());if(roles.isEmpty()&&map(guard.getOrDefault("attributes",Map.of())).isEmpty()||roles.stream().anyMatch(role->!actor.roles().contains(role.toString())))continue;
                    boolean scoped=true;for(var entry:map(guard.getOrDefault("attributes",Map.of())).entrySet()){var granted=actor.attributes().getOrDefault(entry.getKey(),Set.of());var required=(Collection<?>)entry.getValue();if(granted.isEmpty()||!required.isEmpty()&&required.stream().noneMatch(value->granted.contains(value.toString())))scoped=false;}if(!scoped)continue;
                    var facts=connections.resolve(text(oldRef,"profileId"),positive(oldRef.get("profileRev")),text(oldRef,"environment"),text(oldRef,"bindingId"),positive(oldRef.get("bindingRev")),"skill."+actual.get("code"));
                    if(!((List<?>)facts.get("allowedUses")).contains("read")||!((List<?>)facts.get("grantedUses")).contains("read")||!((List<?>)facts.get("operationAllowlist")).contains(oldRef.get("operation")))continue;
                    var ref=new LinkedHashMap<>(oldRef);ref.remove("resolutionDigest");var proof=new LinkedHashMap<String,Object>();proof.put("kind","TEMPLATE_PUBLISHED_PROPOSAL");proof.put("baseAppCode",baseCode);proof.put("baseAppVersion",coordinates.get("version_no"));proof.put("baseCandidateSha256",coordinates.get("candidate_sha256"));proof.put("baseBindingRefs",config.get("bindingRefs"));proof.put("baseSkillRef",skill);proof.put("skillVersionId",actual.get("skillVersionId"));proof.put("skillSha256",actual.get("sha256"));proof.put("ref",ref);
                    result.add(Map.of("label",baseCode+" / "+ref.get("operation")+"（模板导入提议）","ref",ref,"metadataOnly",true,"proof",proof));break;}
            }
        }catch(PlatformException|org.springframework.dao.DataAccessException ex){return result;}
        return result;
    }
    private static boolean hasDocument(Map<String,Object> row){return map(row.get("plan")).get("sourceDocument") instanceof String document&&!document.isBlank();}
    private void recheckImportCreation(Map<String,Object> session,Map<String,Object> frozen){
        var actor=Actor.current();var snapshot=map(frozen.get("snapshot"));var prepared=map(snapshot.get("prepared"));
        var proof=map(map(prepared.get("provenance")).get("importProposal"));var ref=map(proof.get("ref"));
        String grantee=null;
        if(!actor.roles().contains("ADMIN")){
            if(!"TEMPLATE_PUBLISHED_PROPOSAL".equals(proof.get("kind")))throw new PlatformException(403,"ONBOARDING_IMPORT_CONNECTION_FORBIDDEN","当前用户不能继承管理员连接提议");
            String baseCode=text(proof,"baseAppCode");var current=apps.get(baseCode);if(Set.of("DISABLED","ARCHIVED").contains(current.get("status")))throw new PlatformException(403,"ONBOARDING_IMPORT_CONNECTION_FORBIDDEN","模板基底应用已停用");
            var published=apps.version(baseCode,positive(proof.get("baseAppVersion")));var raw=db.queryForMap("select candidate_sha256 from app.app_version where tenant_id=? and app_code=? and version_no=?",actor.tenantId(),baseCode,positive(proof.get("baseAppVersion")));
            if(!Objects.equals(raw.get("candidate_sha256"),proof.get("baseCandidateSha256"))||!Json.canonical(map(published.get("config")).get("bindingRefs")).equals(Json.canonical(proof.get("baseBindingRefs"))))throw new PlatformException(409,"ONBOARDING_SOURCE_CONFLICT","原模板发布快照与导入提议不同");
            var old=map(proof.get("baseSkillRef"));var skill=skillImports.version(text(old,"code"),positive(old.get("version")));
            if(!Objects.equals(skill.get("sha256"),proof.get("skillSha256"))||((Number)skill.get("skillVersionId")).longValue()!=((Number)proof.get("skillVersionId")).longValue())throw new PlatformException(409,"ONBOARDING_SOURCE_CONFLICT","原精确技能来源已变化");
            var guard=map(map(skill.get("definition")).get("guard"));var roles=(Collection<?>)guard.getOrDefault("roles",List.of());var attributes=map(guard.getOrDefault("attributes",Map.of()));
            if(roles.isEmpty()&&attributes.isEmpty()||roles.stream().anyMatch(role->!actor.roles().contains(role.toString())))throw new PlatformException(403,"NO_PERMISSION","原技能当前角色不足");
            for(var entry:attributes.entrySet()){var allowed=actor.attributes().getOrDefault(entry.getKey(),Set.of());var required=(Collection<?>)entry.getValue();if(allowed.isEmpty()||!required.isEmpty()&&required.stream().noneMatch(value->allowed.contains(value.toString())))throw new PlatformException(403,"NO_PERMISSION","原技能当前范围不足");}
            grantee="skill."+skill.get("code");
        }
        var facts=connections.resolve(text(ref,"profileId"),positive(ref.get("profileRev")),text(ref,"environment"),text(ref,"bindingId"),positive(ref.get("bindingRev")),grantee);
        if(!actor.roles().contains("ADMIN")&&(!((List<?>)facts.get("grantedUses")).contains("read")||!((List<?>)facts.get("operationAllowlist")).contains(ref.get("operation"))))throw new PlatformException(403,"CONNECTION_USE_NOT_GRANTED","原模板读取授权已撤销，未新建技能草稿");
    }
    private Map<String,Object> applicationPlan(Map<String,Object> row,Source source,Map<String,Object> operation){
        var plan=map(row.get("plan"));var definition=map(operation.get("definition"));var binding=map(operation.get("binding"));var mappings=map(map(plan.get("fieldsBundle")).get("mappings"));var args=new LinkedHashMap<String,Object>();var required=new LinkedHashSet<String>();
        var originalSchema=map(definition.get("inputSchema"));var originalRequired=(List<?>)originalSchema.getOrDefault("required",List.of());
        for(var entry:mappings.entrySet()){var mapping=map(entry.getValue());if("constant".equals(mapping.get("kind")))args.put(entry.getKey(),mapping.get("value"));else {args.put(entry.getKey(),"${input."+mapping.get("from")+"}");if(originalRequired.contains(entry.getKey()))required.add(mapping.get("from").toString());}}
        var inputSchema=new LinkedHashMap<>(originalSchema);inputSchema.put("required",new ArrayList<>(required));
        var config=new LinkedHashMap<String,Object>();config.put("inputSchema",inputSchema);config.put("outputSchema",Map.of("type","object"));config.put("guard",definition.get("guard"));config.put("bindingRefs",List.of(plan.get("connectionRef")));config.put("onboardingFields",plan.get("fieldsBundle"));
        if(plan.containsKey("modelEntryId"))config.put("modelBinding",Map.of("modelEntryId",plan.get("modelEntryId")));
        // END.args走既有Values结构递归，保留JSON值；outputs.from只允许字符串，不能塞卡片对象。
        var endArgs=new LinkedHashMap<String,Object>();endArgs.put("structured","${query.output.structured}");
        var returns=OnboardingPlan.returnSpec(plan);if(!((List<?>)returns.get("fileRefPaths")).isEmpty())endArgs.put("fileRefs","${query.output.fileRefs}");
        if(returns.get("modelSummary") instanceof Map<?,?> summary)endArgs.put("modelSummary",graphTemplate(Objects.toString(summary.get("tpl"),"")));else endArgs.put("modelSummary","${query.output.modelSummary}");
        if(returns.containsKey("cardTemplate"))endArgs.put("cardPayload",graphTemplate(returns.get("cardTemplate")));
        var graph=new LinkedHashMap<String,Object>();graph.put("definitionVersion","1.0");graph.put("trigger","TASK");graph.put("entry","start");graph.put("nodes",List.of(Map.of("id","start","type","START"),Map.of("id","query","type","SKILL","skillRef",Map.of("code",binding.get("skillCode"),"version",binding.get("version")),"args",args),Map.of("id","end","type","END","args",endArgs)));
        graph.put("edges",List.of(Map.of("from","start","to","query"),Map.of("from","query","to","end")));graph.put("joins",List.of());graph.put("executionRules",Map.of("parallel",false,"maxParallel",1,"cyclesForbidden",true));
        return Map.of("appCode",row.get("appCode"),"name",row.get("appName"),"type","FLOW","description","从批准查询模板创建","routeProfileId",map(source.stored.get("resolvedMapping")).get("routeProfileId"),"config",config,"definition",graph,"layout",Map.of());
    }
    private void validateReturns(Map<String,Object> plan,Map<String,Object> operation){
        var spec=OnboardingPlan.returnSpec(plan);var original=map(map(operation.get("definition")).getOrDefault("returnSpec",Map.of()));
        var paths=(List<?>)spec.getOrDefault("fileRefPaths",List.of());if(!((List<?>)original.getOrDefault("fileRefPaths",List.of())).containsAll(paths))throw bad("文件呈现只能选择原技能已提供的受控文件路径");
        if(!paths.isEmpty()&&!new HashSet<>(paths).equals(new HashSet<>((List<?>)original.getOrDefault("fileRefPaths",List.of()))))throw bad("当前读取路线仅支持展示全部原受控文件或隐藏文件，不伪装任意文件路径筛选");
        if(Json.write(spec).matches("(?s).*\\[[0-9]+].*"))throw bad("当前流程返回映射尚不支持数组下标，请使用原技能已发布投影");
    }
    private Object graphTemplate(Object value){if(value instanceof Map<?,?> map){var out=new LinkedHashMap<String,Object>();map.forEach((key,item)->out.put(key.toString(),graphTemplate(item)));return out;}if(value instanceof List<?> list)return list.stream().map(this::graphTemplate).toList();if(!(value instanceof String text))return value;
        var matcher=java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}").matcher(text);var result=new StringBuffer();while(matcher.find())matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement("${query.output.structured."+matcher.group(1)+"}"));matcher.appendTail(result);return result.toString();}
    private Map<String,Object> template(Actor actor,String code,int rev){var state=templates.state(actor,code,rev);if(state==null||!state.revisionExists()||state.currentRev()!=rev||!"PUBLISHED".equals(state.status()))throw new PlatformException(409,"ONBOARDING_TEMPLATE_UNAVAILABLE","模板不在当前已发布修订，请重新选择");var row=templates.revision(actor,code,rev);if(!"QUERY".equals(row.get("kind")))throw new PlatformException(409,"ONBOARDING_TEMPLATE_UNSUPPORTED","当前接入闭环仅开放QUERY模板");return row;}
    private Map<String,Object> view(Map<String,Object> row){var out=publicView(row);try{var source=source(row);out.put("choices",choices(row,source));}catch(PlatformException ex){out.put("missing",List.of(missing(ex.code(),ex.getMessage())));}out.put("links",links(row));return out;}
    private Map<String,Object> publicView(Map<String,Object> row){var out=new LinkedHashMap<>(row);out.remove("template");var clean=new ArrayList<Map<String,Object>>();for(var step:AppService.objects(row.get("steps"))){var entry=new LinkedHashMap<>(step);entry.keySet().removeAll(Set.of("executionToken","requestSha256","leaseUntil"));clean.add(entry);var receipt=map(step.getOrDefault("receipt",Map.of()));if("PUBLISH".equals(step.get("step"))&&"SUCCEEDED".equals(step.get("status"))&&receipt.containsKey("handover"))out.put("handover",receipt.get("handover"));}out.put("steps",clean);return out;}
    private Map<String,Object> publicOperation(Map<String,Object> operation){var out=new LinkedHashMap<String,Object>();for(String key:List.of("operationId","supported","method","path","reasons"))if(operation.containsKey(key))out.put(key,operation.get(key));return out;}
    private static Map<String,Object> application(Map<String,Object> app){var out=new LinkedHashMap<String,Object>();out.put("appId",app.get("id"));out.put("appCode",app.get("code"));for(String key:List.of("draftRev","status","currentVersionNo"))out.put(key,app.get(key));return out;}
    private static Map<String,Object> step(Map<String,Object> row,String name){return AppService.objects(row.get("steps")).stream().filter(value->name.equals(value.get("step"))).findFirst().orElse(null);}
    private static Map<String,Object> links(Map<String,Object> row){return Map.of("appCode",row.get("appCode"),"workspacePath","#/apps/"+row.get("appCode")+"/orchestration","evaluationPath","#/apps/"+row.get("appCode")+"/publish");}
    private static String planSha(Map<String,Object> row,Map<String,Object> plan){return Digest.sha256(Json.canonical(List.of(row.get("templateCode"),row.get("templateRev"),row.get("appCode"),plan)));}
    private static Map<String,Object> withPreview(Map<String,Object> row,Map<String,Object> preview){row.put("preview",preview);return row;}
    private static Map<String,Object> missing(String code,String message){return Map.of("code",code,"message",Objects.toString(message,"前置条件未满足"));}
    private static String request(Map<String,Object> body){String value=text(body,"requestId");if(value.length()>128)throw bad("requestId过长");return value;}
    private static int positive(Object value){if(!(value instanceof Number n)||n.intValue()<1||n.doubleValue()!=n.intValue())throw bad("修订必须是精确正整数");return ((Number)value).intValue();}
    private static String text(Map<String,Object> value,String key){return AppService.text(value,key);}
    private static Map<String,Object> map(Object value){return value==null?new LinkedHashMap<>():AppService.object(value);}
    private static void keys(Map<String,Object> body,Set<String> allowed){if(!allowed.containsAll(body.keySet()))throw bad("请求包含不允许字段");}
    private static PlatformException bad(String message){return new PlatformException(400,"INVALID_INPUT",message);}
}
