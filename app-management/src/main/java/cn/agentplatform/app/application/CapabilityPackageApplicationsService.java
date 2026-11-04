package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.CapabilityPackageRepository;
import cn.agentplatform.app.infra.CapabilitySkillClient;
import cn.agentplatform.common.*;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 首次组装的全部只读校验先完成，再以同一 app 事务创建草稿、来源关系和幂等收据。 */
@Service
public class CapabilityPackageApplicationsService {
    private final CapabilityPackageRepository packages;
    private final AppService apps;
    private final CapabilitySkillClient skills;
    private final TransactionTemplate tx;
    private OnboardingApplicationSources onboardingSources;
    private cn.agentplatform.app.infra.OnboardingSkillImportsClient onboardingSkills;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void onboardingSources(OnboardingApplicationSources sources){this.onboardingSources=sources;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void onboardingSkills(cn.agentplatform.app.infra.OnboardingSkillImportsClient client){this.onboardingSkills=client;}
    public CapabilityPackageApplicationsService(CapabilityPackageRepository packages, AppService apps, CapabilitySkillClient skills,
                                               PlatformTransactionManager manager) {
        this.packages = packages; this.apps = apps; this.skills = skills; this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
    }

    public record SessionApplication(long packageId, String packageSha, String resolutionSha,
            Map<String,Object> source, Map<String,Object> application,String sourceId) {
        public SessionApplication(long packageId,String packageSha,String resolutionSha,Map<String,Object> source,Map<String,Object> application){
            this(packageId,packageSha,resolutionSha,source,application,null);
        }
    }

    public SessionApplication prepareForOnboardingSource(Map<String,Object> session,Map<String,Object> input) {
        var actor=Actor.current();actor.requireRole("EDITOR");
        if(onboardingSources==null||onboardingSkills==null)throw new PlatformException(503,"ONBOARDING_SOURCE_UNAVAILABLE","会话技能来源服务未装配");
        var source=onboardingSources.resolved(session);
        if(source==null)throw new PlatformException(409,"ONBOARDING_SOURCE_REQUIRED","请先完成本计划导入技能的原验证发布门");
        long id=((Number)source.get("packageVersionId")).longValue();var stored=packages.bindingSource(id,actor.tenantId());
        if(!source.get("sha256").equals(stored.get("sha256")))throw new PlatformException(409,"CAPABILITY_VERSION_CONFLICT","冻结包声明已经变化");
        long deadline=System.nanoTime()+5_000_000_000L;
        for(var binding:AppService.objects(source.get("bindings"))) {
            var actual=onboardingSkills.version(binding.get("skillCode").toString(),((Number)binding.get("version")).intValue());
            if(!binding.get("sha256").equals(actual.get("sha256"))||((Number)binding.get("skillVersionId")).longValue()!=((Number)actual.get("skillVersionId")).longValue())
                throw new PlatformException(409,"CAPABILITY_BINDING_CONFLICT","会话精确发布技能与冻结来源不一致");
        }
        var prepared=prepare(input,source);apps.validateCapabilityDraft(prepared);
        return new SessionApplication(id,stored.get("sha256").toString(),source.get("mappingSha256").toString(),source,prepared,source.get("sessionSourceId").toString());
    }

    /** 会话独立拥有组装收据；不占用旧API的一包一次application_plan_sha256天然键。 */
    public SessionApplication prepareForSession(long id,Map<String,Object> input) {
        var actor=Actor.current();actor.requireRole("EDITOR");
        var stored=packages.bindingSource(id,actor.tenantId());
        if(!"RESOLVED".equals(stored.get("bindingStatus")))throw new PlatformException(409,"CAPABILITY_BINDING_REQUIRED","能力包尚未解析精确技能，请先完成原导入链");
        var source=source(stored);long deadline=System.nanoTime()+5_000_000_000L;
        for(var binding:AppService.objects(source.get("bindings"))) {
            var actual=skills.version(binding.get("skillCode").toString(),((Number)binding.get("version")).intValue(),actor,deadline);
            if(!binding.get("sha256").equals(actual.get("sha256"))||((Number)binding.get("skillVersionId")).longValue()!=((Number)actual.get("skillVersionId")).longValue())
                throw new PlatformException(409,"CAPABILITY_BINDING_CONFLICT","精确发布技能与包来源不一致");
        }
        var prepared=prepare(input,source);apps.validateCapabilityDraft(prepared);
        return new SessionApplication(id,stored.get("sha256").toString(),stored.get("planSha256").toString(),source,prepared);
    }

    /** 由Session行锁事务调用；本地app/来源关系/会话收据一起提交，不持锁做上面的远端验证。 */
    public Map<String,Object> assembleForSession(SessionApplication prepared,Map<String,Object> owned) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Session assembly requires its transaction");
        var actor=Actor.current();var current=packages.applicationSource(prepared.packageId(),actor.tenantId(),true);
        if(!prepared.packageSha().equals(current.get("sha256"))
                ||prepared.sourceId()==null&&!prepared.resolutionSha().equals(current.get("planSha256")))
            throw new PlatformException(409,"CAPABILITY_VERSION_CONFLICT","组装期间包声明或精确解析已改变");
        if(prepared.sourceId()!=null&&(onboardingSources==null
                ||!Json.canonical(prepared.source()).equals(Json.canonical(onboardingSources.requireSource(prepared.sourceId())))))
            throw new PlatformException(409,"ONBOARDING_SOURCE_CONFLICT","组装期间不可变会话来源不一致");
        var input=prepared.application();String code=input.get("code").toString();Map<String,Object> app;
        if(owned==null) {
            app=apps.create(input);packages.associateApplication(actor.tenantId(),app,prepared.packageId(),prepared.source());
        } else {
            app=apps.get(code);
            if(!Objects.equals(app.get("id"),owned.get("appId"))||((Number)app.get("draftRev")).longValue()!=((Number)owned.get("draftRev")).longValue())
                throw new PlatformException(409,"ONBOARDING_APPLICATION_CHANGED","会话应用已由人工修改，未覆盖原修改");
        }
        if(prepared.sourceId()!=null)onboardingSources.bind(app,prepared.sourceId());
        app=apps.save(code,Map.of("draftRev",app.get("draftRev"),"config",input.get("config")),false);
        if("FLOW".equals(input.get("type")))app=apps.save(code,Map.of("draftRev",app.get("draftRev"),"definition",input.get("definition"),"layout",input.get("layout")),true);
        return app;
    }

    public Map<String, Object> create(long id, Map<String, Object> body) {
        var actor = Actor.current(); actor.requireRole("EDITOR");
        if (!body.keySet().equals(Set.of("applications"))) throw bad("仅接受完整 applications 计划");
        var inputs = AppService.objects(body.get("applications"));
        if (inputs.isEmpty()) throw bad("applications 不可为空");
        String planSha = Digest.sha256(Json.canonical(body));
        var stored = packages.applicationSource(id, actor.tenantId(), false);
        if (stored.get("applicationPlanSha256") != null) return receipt(stored, planSha, true);
        if (!"RESOLVED".equals(stored.get("bindingStatus")))
            throw new PlatformException(409, "CAPABILITY_BINDING_REQUIRED", "先解析精确技能绑定");
        var source = source(stored);
        long deadline = System.nanoTime() + 5_000_000_000L;
        var verified = new HashSet<String>();
        for (var binding : AppService.objects(source.get("bindings"))) {
            String code = binding.get("skillCode").toString();
            if (!verified.add(code)) continue;
            var actual = skills.version(code, ((Number) binding.get("version")).intValue(), actor, deadline);
            if (((Number) binding.get("skillVersionId")).longValue() != ((Number) actual.get("skillVersionId")).longValue()
                    || !binding.get("sha256").equals(actual.get("sha256")))
                throw new PlatformException(409, "CAPABILITY_BINDING_CONFLICT", "精确发布技能与原解析来源不一致");
        }
        var prepared = new ArrayList<Map<String, Object>>();
        var codes = new HashSet<String>();
        for (var input : inputs) {
            var app = prepare(input, source);
            if (!codes.add(app.get("code").toString())) throw bad("应用编码重复");
            apps.validateCapabilityDraft(app);
            prepared.add(app);
        }
        try {
            return tx.execute(status -> {
                var locked = packages.applicationSource(id, actor.tenantId(), true);
                if (!stored.get("sha256").equals(locked.get("sha256"))
                        || !stored.get("planSha256").equals(locked.get("planSha256")))
                    throw new PlatformException(409, "CAPABILITY_VERSION_CONFLICT", "组装期间声明或解析计划已变更");
                if (locked.get("applicationPlanSha256") != null) return receipt(locked, planSha, true);
                var created = new ArrayList<Map<String, Object>>();
                for (var input : prepared) {
                    String code = input.get("code").toString();
                    var app = apps.create(input);
                    packages.associateApplication(actor.tenantId(), app, id, source);
                    // 关系先入库，普通 save 仍执行同一来源校验，不提供绕过保存门的特殊开关。
                    app = apps.save(code, Map.of("draftRev", app.get("draftRev"), "config", input.get("config")), false);
                    if ("FLOW".equals(app.get("type")))
                        app = apps.save(code, Map.of("draftRev", app.get("draftRev"), "definition", input.get("definition"),
                                "layout", input.get("layout")), true);
                    created.add(Map.of("appId", app.get("id"), "appCode", code));
                }
                packages.saveApplicationReceipt(actor.tenantId(), id, stored.get("sha256").toString(), planSha, created);
                return receipt(packages.applicationSource(id, actor.tenantId(), false), planSha, false);
            });
        } catch (DuplicateKeyException ex) {
            throw new PlatformException(409, "APP_CODE_CONFLICT", "应用编码已存在，未覆盖已有应用");
        } catch (CannotAcquireLockException | QueryTimeoutException ex) {
            throw new PlatformException(409, "CAPABILITY_IMPORT_BUSY", "应用组装忙，请重试");
        }
    }

    private Map<String, Object> receipt(Map<String, Object> stored, String planSha, boolean reused) {
        if (!planSha.equals(stored.get("applicationPlanSha256")))
            throw new PlatformException(409, "CAPABILITY_IMPORT_PLAN_CONFLICT", "该包版本已用于另一应用计划");
        var current = new ArrayList<Map<String, Object>>();
        for (var target : AppService.objects(stored.get("applicationReceipt"))) {
            Map<String, Object> app;
            try { app = apps.get(target.get("appCode").toString()); }
            catch (PlatformException ex) {
                if (!"APP_NOT_FOUND".equals(ex.code())) throw ex;
                throw missing();
            }
            if (((Number) app.get("id")).longValue() != ((Number) target.get("appId")).longValue()) throw missing();
            current.add(Map.of("appId", app.get("id"), "appCode", app.get("code"),
                    "draftRev", app.get("draftRev"), "status", app.get("status")));
        }
        return Map.of("packageVersionId", stored.get("packageVersionId"), "packageId", stored.get("packageId"),
                "version", stored.get("version"), "sha256", stored.get("sha256"), "applications", current,
                "appCreated", true, "reused", reused, "verificationStatus", "NOT_EVALUATED");
    }

    private static Map<String, Object> source(Map<String, Object> stored) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("packageVersionId", "packageId", "version", "sha256", "softwareId", "softwareVersion"))
            if (stored.containsKey(key)) result.put(key, stored.get(key));
        var mapping = AppService.object(stored.get("resolvedMapping"));
        result.putAll(mapping);
        result.put("mappingSha256", Digest.sha256(Json.canonical(mapping)));
        return result;
    }

    private static Map<String, Object> prepare(Map<String, Object> input, Map<String, Object> source) {
        if (!Set.of("appCode", "name", "type", "description", "domain", "bizCategory", "tags",
                "routeProfileId", "config", "definition", "layout").containsAll(input.keySet())) throw bad("应用计划包含不支持字段");
        String code = AppService.text(input, "appCode"), type = AppService.text(input, "type");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !Set.of("CHAT", "FLOW").contains(type)) throw bad("应用编码或类型无效");
        if (!source.get("routeProfileId").equals(input.get("routeProfileId")))
            throw new PlatformException(409, "CAPABILITY_ROUTE_BINDING_CONFLICT", "应用必须使用已解析路线");
        var config = new LinkedHashMap<>(AppService.object(input.get("config")));
        if (!Collections.disjoint(config.keySet(), Set.of("capabilityPackages", "capabilityBindings", "skillRefs", "tools",
                "frozenPrompts", "dependencyVersions", "ontologyContext"))) throw bad("不得提供服务端来源或冻结字段");
        var refs = new LinkedHashMap<String, Map<String, Object>>();
        for (var binding : AppService.objects(source.get("bindings")))
            refs.putIfAbsent(binding.get("skillCode").toString(), Map.of("code", binding.get("skillCode"), "version", binding.get("version")));
        config.put("skillRefs", new ArrayList<>(refs.values()));
        config.put("capabilityPackages", List.of(source));
        var definition = "FLOW".equals(type) ? AppService.object(input.get("definition"))
                : AppService.object(input.getOrDefault("definition", Map.of()));
        if ("CHAT".equals(type) && !definition.isEmpty()) throw bad("CHAT 不接受流程定义");
        AppService.checkCapabilityReferences(config, definition);
        var app = new LinkedHashMap<String, Object>(input);
        app.put("code", code); app.put("name", AppService.text(input, "name")); app.put("type", type);
        app.put("config", config); app.put("definition", definition);
        app.put("layout", AppService.object(input.getOrDefault("layout", Map.of())));
        return app;
    }
    private static PlatformException bad(String message) { return new PlatformException(400, "INVALID_INPUT", message); }
    private static PlatformException missing() { return new PlatformException(409, "CAPABILITY_APPLICATION_MISSING", "原计划应用已删除，不能通过重试重建"); }
}
