package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.*;

/** 既有报告JSON中的逐轮检查点；短租约只保护写回，所有引擎HTTP均在事务外。 */
final class EvaluationConversations {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BiFunction<String,Map<String,Object>,Map<String,Object>> remote;
    private final Function<String,Map<String,Object>> reports;
    private final BiPredicate<String,Map<String,Object>> sources;
    private static final Set<String> PENDING=Set.of("PENDING","CREATED","READY","RUNNING","WAITING_APPROVAL","WAITING_STEP","WAITING_EXTERNAL");
    EvaluationConversations(JdbcTemplate db,TransactionTemplate tx,BiFunction<String,Map<String,Object>,Map<String,Object>> remote,Function<String,Map<String,Object>> reports,
            BiPredicate<String,Map<String,Object>> sources) {
        this.db=db; this.tx=tx; this.remote=remote; this.reports=reports; this.sources=sources;
    }
    static boolean conversation(Map<String,Object> value){return "CONVERSATION".equals(value.get("mode"));}
    private static PlatformException invalid(String code,String message){return new PlatformException(400,code,message);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}

    /** 新形状显式互斥；客户端不能提供会话/历史/身份指针，断言仍使用原规则。 */
    static Map<String,Object> canonical(Map<String,Object> input,boolean configurable) {
        if(!Set.of("id","mode","safety","turns").containsAll(input.keySet()) || !(input.get("safety") instanceof Boolean)
                || !(input.get("turns") instanceof List<?> turns) || turns.isEmpty() || turns.size()>200)
            throw invalid("EVALUATION_CONVERSATION_INVALID","多轮用例须显式mode、safety与1至200轮，不能混入单轮字段或会话指针");
        var normalized=new ArrayList<Map<String,Object>>();
        for(Object raw:turns){
            if(!(raw instanceof Map<?,?>)) throw invalid("EVALUATION_CONVERSATION_INVALID","轮次必须为对象");
            var turn=new LinkedHashMap<>(object(raw));
            if(!Set.of("inputs","expected","assertion").containsAll(turn.keySet()) || !(turn.get("inputs") instanceof Map) || !turn.containsKey("expected"))
                throw invalid("EVALUATION_CONVERSATION_INVALID","每轮只接受inputs、expected和可选assertion");
            try{
                var spec=EvaluationAssertions.requireValid(turn.get("assertion"),turn.get("expected"),Boolean.TRUE.equals(input.get("safety")));
                if(configurable && turn.get("assertion")!=null) turn.put("assertion",spec.canonical());
            }catch(EvaluationAssertions.InvalidAssertion error){throw invalid(error.code(),error.userMessage());}
            normalized.add(turn);
        }
        var result=new LinkedHashMap<>(input); result.put("turns",normalized); return result;
    }

    static void limits(Map<String,Object> candidate,List<Map<String,Object>> cases) {
        int total=0; var fixed=Json.tree(candidate); var value=fixed.path("config").path("context").path("maxTurns");
        int maximum=value.isMissingNode()?40:value.asInt();
        if(!value.isMissingNode() && (!value.isIntegralNumber() || !value.canConvertToInt() || maximum<1 || maximum>200))
            throw invalid("EVALUATION_TURNS_INVALID","固定候选最大轮数须为1至200，缺省40");
        for(var item:cases){
            if(conversation(item)) {
                if(!"CHAT".equals(fixed.path("type").asText())) throw invalid("EVALUATION_CHAT_REQUIRED","多轮评测只支持固定CHAT候选");
                if(list(item.get("turns")).size()>maximum) throw invalid("EVALUATION_TURNS_INVALID","用例轮数超过固定候选上限");
                total+=list(item.get("turns")).size();
            } else total++;
        }
        if(total>200) throw invalid("EVALUATION_TURNS_INVALID","每份报告总轮数不能超过200");
    }

    private static Map<String,Object> source(String dataset,String sha) {
        var value=new LinkedHashMap<String,Object>(); value.put("schemaVersion",2); value.put("environment","UNKNOWN"); value.put("datasetVersion",dataset); value.put("datasetSha256",sha); return value;
    }
    /** 首次外调前随原报告落库；恢复只读取，绝不为旧报告补造协议或REAL来源。 */
    static Map<String,Object> initial(Map<String,Object> item,String dataset,String sha) {
        var result=new LinkedHashMap<String,Object>(); result.put("id",item.get("id")); result.put("sourceEvidence",source(dataset,sha));
        if(conversation(item)) {
            result.put("conversationProtocol",1); result.put("conversationId",null);
            var turns=new ArrayList<Map<String,Object>>(); int index=0;
            for(var turn:list(item.get("turns"))){
                var checkpoint=new LinkedHashMap<String,Object>(); checkpoint.put("turnNo",++index); checkpoint.put("inputsSha256",Digest.sha256(Json.canonical(turn.get("inputs"))));
                checkpoint.put("status","NOT_RUN"); checkpoint.put("sourceEvidence",source(dataset,sha)); turns.add(checkpoint);
            }
            result.put("turns",turns);
        }
        return result;
    }

    Map<String,Object> execute(String id,String code,long rev,String hash,String dataset,Map<String,Object> candidate,
            List<Map<String,Object>> suite,double threshold,Set<String> baseline,String assertionModel) {
        var actor=Actor.current(); String tenant=actor.tenantId(); UUID token=UUID.randomUUID();
        int claimed=tx.execute(status->db.update("update monitor.evaluation_report set execution_token=?,execution_until=now()+interval '120 seconds',status='RUNNING',completed_at=null where tenant_id=? and id=? and created_by=? and status in ('RUNNING','ERROR') and (execution_until is null or execution_until<now())",token,tenant,id,actor.principalId()));
        if(claimed!=1){var report=reports.apply(id); if(Set.of("ACCEPTED","REJECTED").contains(report.get("status")))return report; throw new PlatformException(409,"EVALUATION_EXECUTION_ACTIVE","原评测仍在执行或等待租约到期");}
        try{
            String datasetSha=db.queryForObject("select sha256 from monitor.dataset where tenant_id=? and app_code=? and dataset_version=?",String.class,tenant,code,dataset);
            var results=list(Json.read(db.queryForObject("select results::text from monitor.evaluation_report where tenant_id=? and id=?",String.class,tenant,id),List.class));
            if(results.size()!=suite.size()) throw invalid("EVALUATION_CHECKPOINT_INVALID","报告缺少原始用例检查点，不能追认多轮来源");
            boolean pending=false,error=false;
            for(int index=0;index<suite.size();index++){
                var item=suite.get(index); var result=results.get(index);
                if(!Objects.equals(item.get("id"),result.get("id"))) throw invalid("EVALUATION_CHECKPOINT_INVALID","检查点顺序与冻结数据集不一致");
                boolean conversation=conversation(item);
                List<Map<String,Object>> turns=conversation?list(item.get("turns")):List.of(item);
                List<Map<String,Object>> checkpoints;
                if(conversation){
                    if(!Integer.valueOf(1).equals(result.get("conversationProtocol")) || !(result.get("turns") instanceof List<?>) || list(result.get("turns")).size()!=turns.size()) throw invalid("EVALUATION_CHECKPOINT_INVALID","旧报告未冻结多轮协议，不能恢复追认");
                    checkpoints=list(result.get("turns"));
                }else{
                    if(!result.containsKey("checkpoint")) {var single=new LinkedHashMap<String,Object>(); single.put("turnNo",1); single.put("inputsSha256",Digest.sha256(Json.canonical(item.get("inputs")))); single.put("status","NOT_RUN"); single.put("sourceEvidence",result.get("sourceEvidence")); result.put("checkpoint",single);}
                    checkpoints=List.of(object(result.get("checkpoint")));
                }
                for(int offset=0;offset<turns.size();offset++){
                    var turn=turns.get(offset); var checkpoint=checkpoints.get(offset);
                    if(!Integer.valueOf(offset+1).equals(checkpoint.get("turnNo")) || !Digest.sha256(Json.canonical(turn.get("inputs"))).equals(checkpoint.get("inputsSha256")) || !protocol(object(checkpoint.get("sourceEvidence")),dataset,datasetSha))
                        throw invalid("EVALUATION_CHECKPOINT_INVALID","轮次检查点与原数据集不一致");
                    String previous=Objects.toString(checkpoint.get("status"),"NOT_RUN");
                    if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(previous)) {
                        if(!"SUCCEEDED".equals(previous)) break;
                        continue;
                    }
                    renew(tenant,id,actor.principalId(),token);
                    var request=new LinkedHashMap<String,Object>();
                    request.put("appCode",code); request.put("draftRev",rev); request.put("candidateSha256",hash); request.put("datasetVersion",dataset); request.put("evalReportId",id); request.put("caseId",item.get("id")); request.put("frozenCandidate",candidate); request.put("inputs",turn.get("inputs"));
                    if(conversation){request.put("turnNo",offset+1); if(result.get("conversationId")!=null)request.put("conversationId",result.get("conversationId"));}
                    Map<String,Object> run;
                    try{run=remote.apply(conversation?"/internal/evaluations/turn":"/internal/evaluations/run",request);}
                    catch(RuntimeException failure){checkpoint.put("status","ERROR"); checkpoint.put("passed",false); checkpoint.put("reasonCode","DEPENDENCY_UNAVAILABLE"); result.put("status","ERROR"); result.put("passed",false); error=true; persist(tenant,id,actor.principalId(),token,results); break;}
                    String runState=Objects.toString(run.get("status"),"UNKNOWN"), runId=Objects.toString(run.get("runId"),"");
                    var evidence=object(checkpoint.get("sourceEvidence"));
                    var runSource=Json.tree(run.get("sourceEvidence"));
                    String mode=runSource.path("environment").asText("UNKNOWN");
                    boolean unfinished=PENDING.contains(runState);
                    boolean consistent=!Boolean.TRUE.equals(evidence.get("invalid")) && !runId.isBlank()
                            && dataset.equals(run.get("datasetVersion"))
                            && (evidence.get("runId")==null || runId.equals(evidence.get("runId")));
                    if (!unfinished) consistent &= sources.test(runId,run)
                            && ("REAL".equals(run.get("environment"))&&Boolean.FALSE.equals(run.get("demoMode")) || "DEMO".equals(run.get("environment"))&&Boolean.TRUE.equals(run.get("demoMode")))
                            && ("UNKNOWN".equals(evidence.get("environment")) || mode.equals(evidence.get("environment")));
                    if(conversation){
                        String session=Objects.toString(run.get("conversationId"),"");
                        consistent &= !session.isBlank() && (result.get("conversationId")==null || session.equals(result.get("conversationId")))
                                && Integer.valueOf(offset+1).equals(run.get("turnNo")) && id.equals(run.get("evalReportId")) && item.get("id").equals(run.get("caseId"))
                                && code.equals(run.get("appCode")) && hash.equals(run.get("candidateSha256")) && run.get("draftRev") instanceof Number number && number.longValue()==rev;
                        if(result.get("conversationId")==null && !session.isBlank()) result.put("conversationId",session);
                    }
                    evidence.put("environment",consistent && !unfinished ? mode : "UNKNOWN");
                    if(consistent && !unfinished)evidence.put("runSource",runSource);
                    if(!consistent)evidence.put("invalid",true);
                    if(!runId.isBlank())evidence.put("runId",runId);
                    var comparison="SUCCEEDED".equals(runState)?EvaluationAssertions.compare(EvaluationAssertions.MODEL_CONFIGURABLE.equals(assertionModel)?turn.get("assertion"):null,turn.get("expected"),run.get("output")):new EvaluationAssertions.CompareResult(false,"ASSERTION_EXECUTION_NOT_SUCCEEDED","轮次尚未成功完成");
                    checkpoint.put("status",runState); checkpoint.put("runId",runId); checkpoint.put("actual",run.get("output")); checkpoint.put("expected",turn.get("expected"));
                    checkpoint.put("passed",consistent && "SUCCEEDED".equals(runState) && comparison.pass());
                    checkpoint.put("reasonCode",consistent?comparison.code():"EVALUATION_SOURCE_INVALID");
                    aggregate(result,item,checkpoints,dataset,datasetSha,baseline);
                    persist(tenant,id,actor.principalId(),token,results);
                    if(PENDING.contains(runState)){pending=true; break;}
                    if(!"SUCCEEDED".equals(runState) || !consistent)break;
                }
                aggregate(result,item,checkpoints,dataset,datasetSha,baseline);
                persist(tenant,id,actor.principalId(),token,results);
                if(error || pending)break;
            }
            boolean accepted=!error && !pending && EvaluationRules.accepted(results,threshold,baseline);
            String status=error?"ERROR":pending?"RUNNING":accepted?"ACCEPTED":"REJECTED";
            int saved=db.update("update monitor.evaluation_report set status=?,results=?::jsonb,completed_at=case when ?='RUNNING' then null else now() end,detail=? where tenant_id=? and id=? and created_by=? and execution_token=? and execution_until>now() and status='RUNNING'",status,Json.write(results),status,"逐轮固定候选执行；安全100%、原断言与同数据集基线；未完成轮不计通过",tenant,id,actor.principalId(),token);
            if(saved!=1)throw lost(); return reports.apply(id);
        }finally{db.update("update monitor.evaluation_report set execution_token=null,execution_until=null where tenant_id=? and id=? and created_by=? and execution_token=?",tenant,id,actor.principalId(),token);}
    }

    private static boolean protocol(Map<String,Object> source,String dataset,String sha){return source!=null && Integer.valueOf(2).equals(source.get("schemaVersion")) && dataset.equals(source.get("datasetVersion")) && sha.equals(source.get("datasetSha256"));}
    private static void aggregate(Map<String,Object> result,Map<String,Object> item,List<Map<String,Object>> turns,String dataset,String sha,Set<String> baseline) {
        boolean complete=turns.stream().allMatch(turn->"SUCCEEDED".equals(turn.get("status")));
        boolean passed=complete && turns.stream().allMatch(turn->Boolean.TRUE.equals(turn.get("passed")));
        result.put("safety",item.get("safety")); result.put("passed",passed); result.put("baselinePassed",baseline.contains(item.get("id"))); result.put("regression",baseline.contains(item.get("id"))&&!passed);
        result.put("status",complete?"SUCCEEDED":turns.stream().anyMatch(turn->"ERROR".equals(turn.get("status")))?"ERROR":turns.stream().anyMatch(turn->PENDING.contains(Objects.toString(turn.get("status"),"")))?"RUNNING":"FAILED");
        result.put("expected",turns.stream().map(turn->turn.get("expected")).toList()); result.put("actual",turns.stream().map(turn->turn.get("actual")).toList()); result.put("attribution",passed?"NONE":"CONVERSATION_FAILURE");
        var evidence=source(dataset,sha); boolean valid=complete; boolean demo=false; var ids=new ArrayList<String>();
        for(var turn:turns){var source=object(turn.get("sourceEvidence")); String mode=Objects.toString(source.get("environment"),"UNKNOWN");
            boolean bound=protocol(source,dataset,sha) && !Boolean.TRUE.equals(source.get("invalid")) && source.get("runId")!=null
                    && Json.tree(source.get("runSource")).path("schemaVersion").asInt()==2;
            valid &= bound && Set.of("REAL","DEMO").contains(mode); demo |= bound && "DEMO".equals(mode);
            if(source.get("runId")!=null)ids.add(source.get("runId").toString());}
        // 未完成用例不发来源通过结论；完成后保留任一轮DEMO，不能被末轮REAL覆盖。
        evidence.put("environment",complete && demo?"DEMO":valid?"REAL":"UNKNOWN"); if(!ids.isEmpty())evidence.put("runId",ids.getLast());
        evidence.put("runSource",Map.of("schemaVersion",2,"scope","PLATFORM_PARTICIPANT_MODES_CONVERSATION",
                "turnCount",turns.size(),"turnSourcesSha256",Digest.sha256(Json.canonical(turns.stream().map(turn->turn.get("sourceEvidence")).toList()))));
        evidence.put("turnRunIds",ids); evidence.put("turnCount",turns.size()); if(result.get("conversationId")!=null)evidence.put("conversationId",result.get("conversationId")); result.put("sourceEvidence",evidence);
    }
    private void renew(String tenant,String id,String owner,UUID token){if(db.update("update monitor.evaluation_report set execution_until=now()+interval '120 seconds' where tenant_id=? and id=? and created_by=? and execution_token=? and execution_until>now() and status='RUNNING'",tenant,id,owner,token)!=1)throw lost();}
    private void persist(String tenant,String id,String owner,UUID token,List<Map<String,Object>> results){if(db.update("update monitor.evaluation_report set results=?::jsonb where tenant_id=? and id=? and created_by=? and execution_token=? and execution_until>now() and status='RUNNING'",Json.write(results),tenant,id,owner,token)!=1)throw lost();}
    private static PlatformException lost(){return new PlatformException(409,"EVALUATION_EXECUTION_RETIRED","原执行代次已失去检查点写回资格");}
}
