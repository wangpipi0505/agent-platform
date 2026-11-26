package cn.agentplatform.authority;

import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.authority.config.DelegationProperties;
import cn.agentplatform.authority.identity.IdentityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 可信授权服务（IAM 口径 B 的 authority）：只签发/校验票据与决策断言，持有签发私钥，
 * 仅在独立 PG schema 保存门户登录撤销指纹，不存储业务数据，不扫描 admin-common 的组件。
 * <p>排除 security 自动配置：本服务是签发方而非资源服务器，Spring Security 只在测试期
 * 因 admin-common 的测试 fixture 出现在类路径上，默认过滤链会把三个端点全部变成 401。
 */
@SpringBootApplication(
        scanBasePackages = "cn.agentplatform.authority",
        excludeName = {
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
        })
@EnableConfigurationProperties({AuthorityServiceProperties.class, IdentityProperties.class, DelegationProperties.class})
public class AuthorityApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthorityApplication.class, args);
    }
}
