package cn.agentplatform.app.infra;

import cn.agentplatform.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;

/** 固定已部署服务的白名单调用；不接收客户端URL或身份/凭据。IAM未提供当前授权通路时明确失败。 */
@Component
public class OnboardingRuntimeClient {
    private final RestClient http;
    private final String engine,monitor;
    private boolean iam;
    private AppAuthorityTransport authorityTransport;
    @org.springframework.beans.factory.annotation.Value("${agent.security.mode:}")
    public void securityMode(String mode){this.iam="iam".equalsIgnoreCase(mode);}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void authorityTransport(AppAuthorityTransport transport){this.authorityTransport=transport;}
    public OnboardingRuntimeClient(@Value("${agent.services.engine:http://127.0.0.1:18082}")String engine,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}")String monitor) {
        this.engine=origin(engine);this.monitor=origin(monitor);
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory(){
            @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method)throws java.io.IOException{
                super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);
            }
        };factory.setConnectTimeout(3000);factory.setReadTimeout(60000);
        http=RestClient.builder().requestFactory(factory).build();
    }
    public Map<String,Object> verify(Map<String,Object> body) { return call(engine,"/admin/debug-runs",body); }
    public Map<String,Object> status(String runId) {
        if(!runId.matches("r_[A-Za-z0-9_-]{1,125}"))throw new PlatformException(409,"ONBOARDING_RUN_INVALID","验证回执缺少真实runId");
        return call(engine,"/admin/debug-runs/"+runId+"/status",null);
    }
    public Map<String,Object> evaluate(Map<String,Object> body){return call(monitor,"/admin/evaluations",body);}
    @SuppressWarnings("unchecked") private Map<String,Object> call(String base,String path,Map<String,Object> body) {
        if(iam){if(authorityTransport==null)throw new PlatformException(503,"ONBOARDING_AUTHORITY_REQUIRED","当前正式接入出站通路未装配");return authorityTransport.call(base,path,body);}
        String ticket=ServiceTokens.issue(Actor.current());
        try {
            var request=body==null?http.get().uri(base+path):http.post().uri(base+path).body(body);
            return request.header("Authorization","Bearer "+ticket).exchange((sent,response)->{
                byte[] bytes=response.getBody().readNBytes(2*1024*1024+1);
                if(bytes.length>2*1024*1024)throw new PlatformException(502,"DEPENDENCY_UNAVAILABLE","接入执行回执超过2MiB上限");
                var result=Json.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),Map.class);
                if(result==null)throw new PlatformException(502,"DEPENDENCY_UNAVAILABLE","接入执行依赖返回空回复");
                if(response.getStatusCode().is3xxRedirection())throw new PlatformException(502,"DEPENDENCY_UNAVAILABLE","接入执行依赖不允许重定向");
                if(response.getStatusCode().isError()||!"OK".equals(result.get("code")))throw new PlatformException(response.getStatusCode().value(),Objects.toString(result.get("code"),"DEPENDENCY_UNAVAILABLE"),Objects.toString(result.get("message"),"接入执行依赖拒绝请求"));
                if(!(result.get("data") instanceof Map<?,?> data))throw new PlatformException(502,"DEPENDENCY_UNAVAILABLE","接入执行回执形状无效");
                return new LinkedHashMap<>((Map<String,Object>)data);
            });
        } catch(PlatformException ex){throw ex;}catch(Exception ex){throw new PlatformException(502,"DEPENDENCY_UNAVAILABLE","接入执行依赖暂不可达，请恢复原会话重试");}
    }
    private static String origin(String value){var uri=java.net.URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException("接入依赖必须是固定origin");return value.replaceAll("/+$","");}
}
