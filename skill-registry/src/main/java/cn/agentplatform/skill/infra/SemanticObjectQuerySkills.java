package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;

/** HTTP登记扩展：仅处理两个精确只读语义路由，和内置HTTP单元互斥，不依赖Bean顺序。 */
@Component
public class SemanticObjectQuerySkills implements SkillAdapter {
    private static final java.util.regex.Pattern PATH=java.util.regex.Pattern.compile("^/onto/releases/([0-9a-fA-F-]{36})/semantic/objects/(query|join)$");
    private final SemanticObjectClient client;
    public SemanticObjectQuerySkills(SemanticObjectClient client){this.client=client;}
    public String type(){return "HTTP";}
    public Set<String> supportedKinds(){return Set.of("SYNC");}
    public boolean handles(Map<String,Object> contract){return isSemanticEndpoint(Objects.toString(contract.get("endpoint"),""));}
    public static boolean isSemanticEndpoint(String endpoint){
        try {
            String path=URI.create(endpoint).getPath();
            // 编码/矩阵参数不能把同一语义处理方法降级到普通HTTP静态凭据通道；执行时仍要求规范原始路径。
            return path!=null&&PATH.matcher(path.replaceAll(";[^/]*", "")).matches();
        } catch(RuntimeException malformed){return false;}
    }
    public static UUID release(String endpoint){
        try{var uri=URI.create(endpoint);var matcher=PATH.matcher(uri.getRawPath());
            if(uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!matcher.matches())return null;
            return UUID.fromString(matcher.group(1));
        }catch(RuntimeException malformed){return null;}
    }
    @Override public SkillAdapters.Raw invoke(SkillAdapters host,Map<String,Object> contract,Map<String,Object> arguments,
            String key,long callId,boolean queryOnly,String runId,long expectedAttempt){
        if(SkillAdapters.asyncForm(contract)||!"SYNC".equals(Objects.toString(contract.getOrDefault("invocationKind","SYNC"))))throw new SkillAdapters.Failure("PARAM_ERROR",false,"标准语义查询只接受SYNC");
        var source=contract.get("importSource") instanceof Map<?,?> map?map:Map.of();
        String endpoint=Objects.toString(contract.get("endpoint"),"");UUID release=release(endpoint);
        if(release==null||!release.toString().equals(source.get("releaseId")))throw new SkillAdapters.Failure("NO_PERMISSION",false,"标准查询必须绑定导入时固定的正式版本");
        var body=new LinkedHashMap<String,Object>(arguments==null?Map.of():arguments);
        String op=Objects.toString(source.get("toolCode"),"");
        if(op.isBlank()||body.containsKey("opCode")&&!op.equals(body.get("opCode")))throw new SkillAdapters.Failure("NO_PERMISSION",false,"查询操作与正式导入声明不一致");
        body.put("opCode",op);
        try{
            var envelope=client.execute(endpoint,body);
            for(String field:List.of("spaceCode","releaseId","version","contentDigest"))
                if(!Objects.equals(source.get(field),envelope.path("meta").path(field).asText()))
                    throw new SkillAdapters.Failure("OUTPUT_INVALID",false,"查询响应四元组与正式导入声明不一致");
            return host.read(new java.io.ByteArrayInputStream(Json.write(envelope).getBytes(java.nio.charset.StandardCharsets.UTF_8)),contract,callId,runId,expectedAttempt,0);
        }catch(PlatformException denied){throw new SkillAdapters.Failure(denied.status()==401||denied.status()==403?"NO_PERMISSION":"RETRYABLE_FAILURE",false,denied.getMessage(),denied.code(),null);}
        catch(java.io.IOException invalid){throw new SkillAdapters.Failure("OUTPUT_INVALID",false,"标准查询返回无法投影");}
    }
    @Override public SkillAdapters.Observation observe(SkillAdapters host,Map<String,Object> contract,Map<String,Object> arguments,
            String key,long callId,boolean queryOnly,String runId,long expectedAttempt){
        return new SkillAdapters.Observation(invoke(host,contract,arguments,key,callId,queryOnly,runId,expectedAttempt),Map.of());
    }
}
