package cn.agentplatform.gateway.application;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.infra.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;

/** Only synchronous bootstrap; the existing SSE lifecycle owns every asynchronous resource. */
public final class RunEventsProxy {
    private final PortalRunEventsSse sse;
    private final PortalRunEventsHttp http;
    private final CurrentAuthorityClient authority;
    public RunEventsProxy(PortalRunEventsSse sse,PortalRunEventsHttp http,CurrentAuthorityClient authority){
        this.sse=Objects.requireNonNull(sse);this.http=Objects.requireNonNull(http);this.authority=Objects.requireNonNull(authority);
    }
    public void forward(HttpServletRequest request,HttpServletResponse response,GatewayIngressBoundary.PortalEventsRequest capture)throws IOException {
        try{sse.start(request,response,http,authority,capture);}
        catch(PlatformException failure){
            System.err.println("C2B1_DIAG_PROXY_PLATFORM");
            if(failure.status()==401)throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
            if(failure.status()==404)throw new PlatformException(404,"RUN_NOT_FOUND","运行不存在");
            if(failure.status()==403)throw new PlatformException(403,failure.code(),"当前授权不允许读取运行事件");
            throw unavailable();
        }catch(IOException|RuntimeException failure){System.err.println("C2B1_DIAG_PROXY_OTHER");throw unavailable();}
    }
    private static PlatformException unavailable(){return new PlatformException(503,"RUN_EVENTS_UNAVAILABLE","运行事件暂不可用");}
}
