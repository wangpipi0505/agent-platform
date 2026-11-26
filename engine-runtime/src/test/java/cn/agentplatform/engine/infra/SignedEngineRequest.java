package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Exercise the engine's receiver with a ticket from the loopback authority. */
final class SignedEngineRequest {
    private SignedEngineRequest() {}

    static JsonNode signedRequest(LoopbackAuthority iam, CurrentAuthorityClient gateway, TrustedJwtDecoder decoder,
            ServiceAuthorization receiver, Object controller, String method, String path, String handlerName,
            ServiceCall call, java.util.function.Supplier<Object> invoke) throws Exception {
        var ticket = gateway.exchange(iam.portal(), call);
        var handler = new HandlerMethod(controller, java.util.Arrays.stream(controller.getClass().getMethods())
                .filter(m -> m.getName().equals(handlerName)).findFirst().orElseThrow());
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                path.replaceFirst("/apps/[^/]+", "/apps/{code}").replaceFirst("/s_[a-f0-9]{32}", "/{id}"));
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()), List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            receiver.preHandle(request, response, handler);
            return Json.tree(invoke.get()).path("data");
        } finally {
            receiver.afterCompletion(request, response, handler, null);
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
