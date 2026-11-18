package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.engine.application.RunService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
@ActiveProfiles("test") // R-8：mode=test 要求 test profile（AuthorityProperties.validateDeployment）
@Import(EngineContextPostgresTest.AsyncProbeConfiguration.class)
class EngineContextPostgresTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", EngineTestDatabase::url);
        properties.add("spring.datasource.username", EngineTestDatabase::user);
        properties.add("spring.datasource.password", EngineTestDatabase::password);
        // R-8：夹具碰撞消除后暴露的部署校验缺口——全上下文需显式安全模式（AuthorityProperties
        // validate：mode∈{dev-hmac,test,iam}；test+authority 缺省走早退分支，不要求 IAM 配置）。
        properties.add("agent.security.mode", () -> "test");
        properties.add("agent.security.service-id", () -> "engine");
    }

    @Autowired TraceStore store;
    @Autowired RunService service;
    @Autowired WebApplicationContext context;
    @Autowired AsyncProbe probe;

    @TestConfiguration
    static class AsyncProbeConfiguration {
        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    @RestController
    static class AsyncProbe {
        final CountDownLatch release = new CountDownLatch(1);

        @GetMapping("/test-only/async-timeout")
        Callable<String> waitForRelease() {
            return () -> {
                release.await(10, TimeUnit.SECONDS);
                return "released";
            };
        }
    }

    @Test
    void actualMvcTimeoutLeavesRoomForTheFiftyFiveSecondObservationWindow() throws Exception {
        // Exercise Boot's actual MVC configuration, rather than a fixture overriding timeout.
        // A shorter servlet timeout silently cuts off RunController's 55-second writer.
        try {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var result =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get("/test-only/async-timeout"))
                            .andReturn();
            assertTrue(result.getRequest().isAsyncStarted());
            assertTrue(
                    result.getRequest().getAsyncContext().getTimeout() > 55000,
                    "Servlet timeout must exceed the 55-second SSE observation window");
        } finally {
            probe.release.countDown();
        }
    }

    @Test
    void repositoryProxyAndSchedulerUseRealInitializedJdbc() {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(store));
        assertNotNull(store.db());
        assertEquals(1, store.db().queryForObject("select 1", Integer.class));
        assertDoesNotThrow(() -> service.recover());
    }
}
