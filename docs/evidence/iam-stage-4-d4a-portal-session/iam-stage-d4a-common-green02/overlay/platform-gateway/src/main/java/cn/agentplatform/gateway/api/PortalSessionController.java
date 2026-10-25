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
                response.resetBuffer();response.setStatus(rejected.status());
                response.getOutputStream().write(Json.write(new ApiResponse<>(rejected.code(),rejected.getMessage(),null,null)).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
