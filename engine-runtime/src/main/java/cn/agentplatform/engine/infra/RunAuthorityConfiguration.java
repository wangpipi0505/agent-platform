package cn.agentplatform.engine.infra;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.engine.application.RunAuthority;

/**
 * IAM 模式下把受理授权（EX-26 的 {@code resume} 续期）接进生产容器。
 *
 * <p>只在 {@code agent.security.mode=iam} 时生效：legacy（口令/API key）部署没有 authority-service，
 * 也就没有可续期的委托，此时 {@code RunService.admissionProof} 保持既有 403
 * {@code IAM_ADMISSION_UNAVAILABLE} 的默认拒绝行为，不装配任何半成品依赖。
 *
 * <p>依赖方向：{@link RunAuthorityStore} 是 PG 权威源（唯一业务真相），
 * {@link HttpCurrentAuthorityClient} 由 {@code ServiceTrustChainConfiguration} 在本模式内声明，
 * 这里只复用、不重复声明（否则同类 bean 两个定义，注入点直接歧义失败）。
 * {@link Clock} 与 authority 客户端一样是可选依赖：容器没有时退回系统时钟，
 * 与 {@code ServiceTrustChainConfiguration} 的 {@code ObjectProvider<Clock>} 约定一致。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "iam")
public class RunAuthorityConfiguration {

    /**
     * 续期失败后的退避秒数。窗口刻意收窄到 1..60 秒：
     * 低于 1 秒会把 authority-service 打成重试风暴，高于 60 秒会让运行在恢复窗口内持续 503。
     * 越界直接启动失败，而不是带病启动后再由运行期报错。
     */
    static final String BACKOFF_PROPERTY = "agent.engine.run-authority-backoff-seconds";
    static final long DEFAULT_BACKOFF_SECONDS = 30L;
    static final long MIN_BACKOFF_SECONDS = 1L;
    static final long MAX_BACKOFF_SECONDS = 60L;

    @Bean
    @ConditionalOnMissingBean
    RunAuthorityStore runAuthorityStore(DataSource source, ObjectProvider<Clock> clocks) {
        return new RunAuthorityStore(source, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    RunAuthority runAuthority(RunAuthorityStore store, ObjectProvider<HttpCurrentAuthorityClient> client,
            ObjectProvider<Clock> clocks, Environment environment) {
        HttpCurrentAuthorityClient authority = client.getIfAvailable();
        if (authority == null) {
            // 没有 authority 客户端就没有续期来源。宁可启动失败，也不装配一个永远 403 的"已装配"状态：
            // 后者会把配置错误伪装成用户的授权错误。
            throw new IllegalStateException("IAM_AUTHORITY_CLIENT_REQUIRED");
        }
        return new RunAuthority(store, authority, clocks.getIfAvailable(Clock::systemUTC), backoff(environment));
    }

    static Duration backoff(Environment environment) {
        long seconds = environment.getProperty(BACKOFF_PROPERTY, Long.class, DEFAULT_BACKOFF_SECONDS);
        if (seconds < MIN_BACKOFF_SECONDS || seconds > MAX_BACKOFF_SECONDS) {
            throw new IllegalStateException("RUN_AUTHORITY_BACKOFF_OUT_OF_RANGE: " + seconds
                    + " (expected " + MIN_BACKOFF_SECONDS + ".." + MAX_BACKOFF_SECONDS + " seconds)");
        }
        return Duration.ofSeconds(seconds);
    }
}
