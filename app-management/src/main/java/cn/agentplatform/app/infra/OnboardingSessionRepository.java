package cn.agentplatform.app.infra;

import cn.agentplatform.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

/** 会话是唯一编排权威；本地资源装配与收据同事务，远端执行只在短事务之外。 */
@Repository
public class OnboardingSessionRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public OnboardingSessionRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db=db; tx=new TransactionTemplate(manager); tx.setTimeout(10);
    }
    public <T> T atomic(Supplier<T> work) { return tx.execute(state -> work.get()); }
    public Map<String,Object> create(Actor actor,Map<String,Object> seed,String requestId,String sha) {
        return atomic(()->{
            db.update("insert into app.onboarding_session(tenant_id,session_id,principal_id,template_code,template_rev,app_code,app_name,template_snapshot,plan,plan_sha256,create_request_id,create_sha256)"
                    +" values(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?) on conflict(tenant_id,principal_id,create_request_id) do nothing",
                    actor.tenantId(),seed.get("sessionId"),actor.principalId(),seed.get("templateCode"),seed.get("templateRev"),seed.get("appCode"),seed.get("appName"),
                    Json.write(seed.get("template")),Json.write(seed.get("plan")),seed.get("planSha256"),requestId,sha);
            var rows=db.queryForList("select session_id,create_sha256 from app.onboarding_session where tenant_id=? and principal_id=? and create_request_id=?",actor.tenantId(),actor.principalId(),requestId);
            if (!sha.equals(rows.getFirst().get("create_sha256"))) throw conflict("同一创建requestId载荷不同");
            return get(actor,rows.getFirst().get("session_id").toString());
        });
    }
    public List<Map<String,Object>> list(Actor actor) {
        return db.queryForList("select session_id from app.onboarding_session where tenant_id=? and principal_id=? order by created_at desc limit 100",actor.tenantId(),actor.principalId())
                .stream().map(row->get(actor,row.get("session_id").toString())).toList();
    }
    public Map<String,Object> get(Actor actor,String id) { return load(actor,id,false); }
    public Map<String,Object> lock(Actor actor,String id) { return load(actor,id,true); }
    private Map<String,Object> load(Actor actor,String id,boolean lock) {
        if (!id.matches("os_[a-f0-9]{32}")) throw new PlatformException(400,"INVALID_INPUT","接入会话编号无效");
        var rows=db.queryForList("select * from app.onboarding_session where tenant_id=? and principal_id=? and session_id=?"+(lock?" for update":""),actor.tenantId(),actor.principalId(),id);
        if(rows.isEmpty()) throw new PlatformException(404,"ONBOARDING_SESSION_NOT_FOUND","接入会话不存在");
        var row=rows.getFirst(); var out=new LinkedHashMap<String,Object>();
        out.put("sessionId",id); out.put("templateCode",row.get("template_code"));out.put("templateRev",row.get("template_rev"));
        out.put("appCode",row.get("app_code"));out.put("appName",row.get("app_name"));out.put("rev",row.get("rev"));out.put("status",row.get("status"));
        if(row.get("cancel_reason")!=null)out.put("cancelReason",row.get("cancel_reason"));
        out.put("plan",json(row.get("plan")));out.put("planSha256",row.get("plan_sha256"));out.put("template",json(row.get("template_snapshot")));
        if(row.get("application")!=null)out.put("application",json(row.get("application")));
        var steps=new ArrayList<Map<String,Object>>();
        for(var step:db.queryForList("select step,status,receipt,error,request_id,request_sha256,execution_token,lease_until from app.onboarding_session_step where tenant_id=? and session_id=? and plan_sha256=? order by created_at",actor.tenantId(),id,row.get("plan_sha256"))) {
            var entry=new LinkedHashMap<String,Object>();entry.put("step",step.get("step"));entry.put("status",step.get("status"));entry.put("receipt",json(step.get("receipt")));entry.put("error",json(step.get("error")));
            entry.put("requestId",step.get("request_id"));entry.put("requestSha256",step.get("request_sha256"));entry.put("executionToken",step.get("execution_token"));entry.put("leaseUntil",step.get("lease_until"));steps.add(entry);
        }
        out.put("steps",steps);return out;
    }
    public Map<String,Object> save(Actor actor,String id,int expectedRev,Map<String,Object> plan,String planSha,String requestId,String requestSha) {
        return atomic(()->{
            var row=lock(actor,id);var prior=replay(actor,id,requestId,requestSha);if(prior!=null)return prior;
            editable(row,expectedRev); requireIdle(actor,id);
            db.update("update app.onboarding_session set plan=?::jsonb,plan_sha256=?,rev=rev+1,status='DRAFT',updated_at=now() where tenant_id=? and session_id=?",Json.write(plan),planSha,actor.tenantId(),id);
            return remember(actor,id,requestId,requestSha,get(actor,id));
        });
    }
    public Map<String,Object> cancel(Actor actor,String id,int expectedRev,String requestId,String sha,String reason) {
        return atomic(()->{var row=lock(actor,id);var prior=replay(actor,id,requestId,sha);if(prior!=null)return prior;editable(row,expectedRev);requireIdle(actor,id);
            db.update("update app.onboarding_session set status='CANCELLED',cancel_reason=?,rev=rev+1,updated_at=now() where tenant_id=? and session_id=?",reason,actor.tenantId(),id);return remember(actor,id,requestId,sha,get(actor,id));});
    }
    public void requireIdle(Actor actor,String id) {
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from app.onboarding_session_step where tenant_id=? and session_id=? and status='RUNNING')",Boolean.class,actor.tenantId(),id)))
            throw new PlatformException(409,"ONBOARDING_STEP_RUNNING","当前步骤仍在执行，请先读取原步骤结果");
    }
    public static void editable(Map<String,Object> row,int rev) {
        if(((Number)row.get("rev")).intValue()!=rev)throw conflict("接入会话修订冲突，请重新读取");
        if(Set.of("PUBLISHED","CANCELLED").contains(row.get("status")))throw new PlatformException(409,"CONFIG_STATE_TRANSITION_INVALID","终态接入会话不可修改");
    }
    public Map<String,Object> replay(Actor actor,String id,String requestId,String sha) {
        var rows=db.queryForList("select request_sha256,response from app.onboarding_session_request where tenant_id=? and session_id=? and request_id=?",actor.tenantId(),id,requestId);
        if(rows.isEmpty())return null;if(!sha.equals(rows.getFirst().get("request_sha256")))throw conflict("同一requestId载荷不同");return json(rows.getFirst().get("response"));
    }
    public Map<String,Object> remember(Actor actor,String id,String requestId,String sha,Map<String,Object> response) {
        db.update("insert into app.onboarding_session_request(tenant_id,session_id,request_id,request_sha256,response) values(?,?,?,?,?::jsonb) on conflict do nothing",actor.tenantId(),id,requestId,sha,Json.write(response));return response;
    }
    public void application(Actor actor,String id,Map<String,Object> app,String status) {
        db.update("update app.onboarding_session set application=?::jsonb,status=?,rev=rev+1,updated_at=now() where tenant_id=? and session_id=?",Json.write(app),status,actor.tenantId(),id);
    }
    public void start(Actor actor,String id,String plan,String step,String requestId,String sha,String token) {
        db.update("insert into app.onboarding_session_step(tenant_id,session_id,plan_sha256,step,status,request_id,request_sha256,execution_token,lease_until) values(?,?,?,?,'RUNNING',?,?,?,now()+interval '60 seconds')"
                +" on conflict(tenant_id,session_id,plan_sha256,step) do update set status='RUNNING',request_id=excluded.request_id,request_sha256=excluded.request_sha256,execution_token=excluded.execution_token,lease_until=excluded.lease_until,error=null,updated_at=now()",actor.tenantId(),id,plan,step,requestId,sha,token);
        db.update("update app.onboarding_session set rev=rev+1,updated_at=now() where tenant_id=? and session_id=?",actor.tenantId(),id);
    }
    public void finish(Actor actor,String id,String plan,String step,String token,String status,Map<String,Object> receipt,Map<String,Object> error) {
        int changed=db.update("update app.onboarding_session_step set status=?,receipt=?::jsonb,error=?::jsonb,updated_at=now() where tenant_id=? and session_id=? and plan_sha256=? and step=? and execution_token=?",status,receipt==null?null:Json.write(receipt),error==null?null:Json.write(error),actor.tenantId(),id,plan,step,token);
        if(changed!=1)throw conflict("步骤执行代次已变化");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> json(Object value) { return value==null?null:Json.read(value.toString(),Map.class); }
    private static PlatformException conflict(String message){return new PlatformException(409,"CONFIG_REV_CONFLICT",message);}
}
