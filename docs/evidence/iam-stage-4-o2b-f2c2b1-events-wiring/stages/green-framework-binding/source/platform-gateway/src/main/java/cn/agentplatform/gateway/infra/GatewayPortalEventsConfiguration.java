package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.gateway.application.RunEventsProxy;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

/** Explicit import only, like GatewayIamAssembly. Fixed TLS and trusted IAM must already exist. */
public final class GatewayPortalEventsConfiguration {
    @Bean PortalRunEventsHttp portalRunEventsHttp(PortalEventsTls tls){return new PortalRunEventsHttp(tls);}
    @Bean PortalRunEventsSse portalRunEventsSse(){return new PortalRunEventsSse();}
    @Bean RunEventsProxy runEventsProxy(PortalRunEventsSse sse,PortalRunEventsHttp http,CurrentAuthorityClient authority){return new RunEventsProxy(sse,http,authority);}
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> portalEventsTomcat(){
        return factory->{
            // Boot creates this actual protocol before connector customizers apply server.tomcat.*.
            // ControlledProtocol.start checks the final profile, never correcting bad configuration.
            factory.setProtocol(PortalRunEventsSse.ControlledProtocol.class.getName());
            factory.addContextCustomizers(context->context.getPipeline().addValve(PortalRunEventsSse.contextValve()));
        };
    }
}
