package cn.agentplatform.model.infra;

import cn.agentplatform.model.application.FixturePolicy;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AP-S06 守卫装配：显示注册到 Servlet 过滤链并排在 Spring Security 之后（order 0 > -100），
 * 保证未认证请求先被 401 拦截，fixture 语义只作用于已通过认证的调用；路径按 servlet
 * url-pattern 收窄，未匹配路径不进入缓存包装，避免影响 SSE/下载等其他流量。
 */
@Configuration
public class FixtureGuardConfiguration {
    @Bean
    public FilterRegistrationBean<FixtureEgressGuard> fixtureEgressGuard(
            JdbcTemplate db, FixturePolicy policy) {
        var registration = new FilterRegistrationBean<>(new FixtureEgressGuard(db, policy.demoMode()));
        registration.addUrlPatterns(
                "/admin/model-providers",
                "/admin/model-entries",
                "/internal/model/chat",
                "/internal/model/chat/stream",
                "/internal/model/measure",
                "/internal/model-bindings/freeze",
                "/internal/model-bindings/validate");
        registration.setOrder(0);
        registration.setName("fixtureEgressGuard");
        return registration;
    }
}
