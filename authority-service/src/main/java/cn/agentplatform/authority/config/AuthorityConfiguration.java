package cn.agentplatform.authority.config;

import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.authority.sign.KeyRing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 最小装配：{@link Clock} 显式成 bean，票据时间全部经由它计算（测试可用固定时钟复现边界）；
 * {@link KeyRing} 在启动时就构造，密钥缺失/格式错误会直接让进程起不来，而不是等到第一次签发才暴露。
 */
@Configuration(proxyBeanMethods = false)
public final class AuthorityConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    KeyRing keyRing(AuthorityServiceProperties properties, Clock clock) {
        return new KeyRing(properties, clock);
    }

    /** 容量由 {@code agent.authority.delegation.capacity} 控制，测试直接 new 小容量实例。 */
    @Bean
    DecisionStore decisionStore(DelegationProperties delegation, Clock clock) {
        return new DecisionStore(delegation.capacity(), clock);
    }
}
