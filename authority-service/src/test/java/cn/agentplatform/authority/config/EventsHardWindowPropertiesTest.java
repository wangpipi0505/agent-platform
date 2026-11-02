package cn.agentplatform.authority.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code agent.authority.events-hard-window-seconds} 的绑定契约：默认 55，只允许 5..55。
 *
 * <p>用 {@link ApplicationContextRunner} 而不是直接 new：越界必须在<b>启动</b>时失败，
 * 且稳定 message 是配置契约的一部分（运维排障靠它，不能让 Spring 的通用绑定错误吞掉）。
 * 上限 55 与客户端夹取常量同值：配置只能把硬窗缩短给测试用，不能把生产窗口放宽。
 */
class EventsHardWindowPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "agent.authority.issuer=https://authority.test",
                    "agent.authority.key-path=classpath:key.pem",
                    "agent.authority.kid=kid-1",
                    "agent.authority.clients.gateway=secret");

    @Test
    void defaultsToTheClientClampOfFiftyFiveSeconds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthorityServiceProperties.class).eventsHardWindowSeconds())
                    .isEqualTo(55);
        });
    }

    @Test
    void acceptsBothEndsOfTheRange() {
        runner.withPropertyValues("agent.authority.events-hard-window-seconds=5")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("agent.authority.events-hard-window-seconds=55")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupOutsideTheRangeWithAStableMessage() {
        runner.withPropertyValues("agent.authority.events-hard-window-seconds=4").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                            "AUTHORITY_TTL_INVALID: events-hard-window-seconds 必须在 5..55");
        });
        runner.withPropertyValues("agent.authority.events-hard-window-seconds=56").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                            "AUTHORITY_TTL_INVALID: events-hard-window-seconds 必须在 5..55");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthorityServiceProperties.class)
    static class PropertiesConfig {
    }
}
