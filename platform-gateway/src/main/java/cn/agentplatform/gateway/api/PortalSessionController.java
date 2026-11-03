package cn.agentplatform.gateway.api;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Finite synchronous projection. MVC never receives a deferred value that could outlive its proof. */
@RestController
public final class PortalSessionController {
    private final Optional<CurrentAuthorityClient> authority;
    public PortalSessionController(Optional<CurrentAuthorityClient> authority){this.authority=authority;}

    @GetMapping("/auth/session")
    public void session(HttpServletRequest request,HttpServletResponse response)throws IOException {
        response.setHeader("Cache-Control","no-store");
        response.setContentType("application/json;charset=UTF-8");
        try{
            var source=GatewayIngressBoundary.claimSession(request);
            var client=authority.orElseThrow(()->new PlatformException(503,"PORTAL_SESSION_UNAVAILABLE","门户会话能力尚未就绪"));
            var proof=client.portalSession(source.sourceToken());
            byte[] json=Json.write(ApiResponse.ok(proof.view())).getBytes(StandardCharsets.UTF_8);
            var output=response.getOutputStream();
            // MVC 6.2.7 wraps the response but this synchronous void handler owns its first write.
            // Acquiring the stream can run wrapper code, so the final proof check comes after it.
            proof.requireVerified();
            output.write(json);
            response.flushBuffer();
        }catch(PlatformException rejected){
            if(!response.isCommitted()){
                response.resetBuffer();
                // 身份源不可达时统一成契约登记码：宿主据此展示"无法确认身份"的阻塞，
                // 不会把它误判成 403 去降级到 dev/共享账号。
                var emitted = degrade(rejected);
                response.setStatus(emitted.status());
                response.setContentType("application/json;charset=UTF-8");
                response.getOutputStream().write(Json.write(ApiResponse.error(emitted.status(),emitted.code(),emitted.getMessage())).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * 身份源不可达（authority 本身或其上游身份服务）时对外只留契约 §6 的阻塞码。
     * 两种内部码都来自 503：IAM_AUTHORITY_UNAVAILABLE（可信授权服务不可达）与
     * IAM_IDENTITY_UNAVAILABLE（身份源返回 5xx/坏响应）。不把它们原样泄露给宿主，
     * 也不能退化成 403，否则宿主会误以为"凭据错误"而切到 dev/共享账号。
     */
    static PlatformException degrade(PlatformException rejected){
        if(rejected.status()!=503)return rejected;
        if(!"IAM_IDENTITY_UNAVAILABLE".equals(rejected.code())&&!"IAM_AUTHORITY_UNAVAILABLE".equals(rejected.code()))return rejected;
        return new PlatformException(503,"INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE","身份源暂不可用，未执行业务操作");
    }
}
