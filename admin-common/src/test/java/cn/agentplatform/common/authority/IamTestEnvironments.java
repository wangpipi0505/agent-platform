package cn.agentplatform.common.authority;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Finite test inputs for the EX-43 iam startup gate. No production class is doubled here. */
final class IamTestEnvironments {
    static final String SECRET_PROPERTY = "AGENT_SECRET_IAM_ENGINE_CLIENT";
    static final String SECRET_VALUE = "stage1-test-only-iam-engine-secret";

    private IamTestEnvironments() {}

    /** Same complete iam deployment used by the historical binder tests, plus a local secret. */
    static MockEnvironment completeIam() {
        var environment = new MockEnvironment();
        new LinkedHashMap<>(AuthorityConfigurationTest.completeIam())
                .forEach((key, value) -> environment.setProperty(key, String.valueOf(value)));
        environment.setProperty("server.address", "127.0.0.1");
        environment.setProperty(SECRET_PROPERTY, SECRET_VALUE);
        return environment;
    }

    static ServiceRoutePolicy routes(ServiceRoutePolicy.Route... rows) {
        return new ServiceRoutePolicy(List.of(rows));
    }

    /** Registers a handler method per {@code template -> method} without starting a context. */
    static Collection<RequestMappingInfoHandlerMapping> mappings(Map<String, String> templates) {
        var mapping = new RequestMappingHandlerMapping();
        var handler = new Probe();
        templates.forEach((template, method) -> {
            try {
                Method call = Probe.class.getDeclaredMethod("call");
                mapping.registerMapping(
                        RequestMappingInfo.paths(template)
                                .methods(RequestMethod.valueOf(method))
                                .build(),
                        handler,
                        call);
            } catch (NoSuchMethodException error) {
                throw new AssertionError(error);
            }
        });
        return new ArrayList<>(List.of(mapping));
    }

    static final class Probe {
        public void call() {}
    }
}
