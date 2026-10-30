package cn.agentplatform.skill.infra;

import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.common.authority.IamServiceTicketProvider;
import cn.agentplatform.common.authority.ServiceTicketProvider;
import cn.agentplatform.common.authority.WorkloadCredentialResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Clock;

/**
 * skill 出站签发的容器接线；复用公共 IAM 协议及预算，不启用尚未迁完的全服务入站信任链。
 * LEGACY 不创建 IAM 网络资源；AUTHORITY 缺可信部署配置或凭据时启动失败，不能退回自签。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.skill", name = "service-ticket-mode", havingValue = "AUTHORITY")
public class ServiceTicketConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CurrentAuthorityClient.class)
    HttpCurrentAuthorityClient skillCurrentAuthorityClient(
            AuthorityProperties deployment, Environment environment,
            ObjectProvider<WorkloadCredentialResolver> credentials, ObjectProvider<Clock> clocks) {
        if (!"iam".equals(deployment.mode()) || !"skill".equals(deployment.serviceId()))
            throw new IllegalStateException("AUTHORITY_REQUIRES_SKILL_IAM");
        return new HttpCurrentAuthorityClient(deployment, clocks.getIfAvailable(Clock::systemUTC),
                credentials.getIfAvailable(() -> new WorkloadCredentialResolver(deployment)),
                environment.acceptsProfiles(Profiles.of("test")));
    }

    @Bean
    @ConditionalOnMissingBean(ServiceTicketProvider.class)
    ServiceTicketProvider skillServiceTicketProvider(
            AuthorityProperties deployment, CurrentAuthorityClient currentAuthority, ObjectProvider<Clock> clocks) {
        if (!"iam".equals(deployment.mode()) || !"skill".equals(deployment.serviceId()))
            throw new IllegalStateException("AUTHORITY_REQUIRES_SKILL_IAM");
        return new IamServiceTicketProvider(clocks.getIfAvailable(Clock::systemUTC), currentAuthority);
    }
}
