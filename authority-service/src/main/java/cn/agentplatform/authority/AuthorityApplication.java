package cn.agentplatform.authority;

import cn.agentplatform.authority.config.AuthorityServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 可信授权服务（IAM 口径 B 的 authority）：只签发/校验票据与决策断言，持有签发私钥，
 * 不持有任何业务数据，因此不连数据库、不扫描 admin-common 的组件。
 * <p>排除 security 自动配置：本服务是签发方而非资源服务器，Spring Security 只在测试期
 * 因 admin-common 的测试 fixture 出现在类路径上，默认过滤链会把三个端点全部变成 401。
 */
@SpringBootApplication(
        scanBasePackages = "cn.agentplatform.authority",
        excludeName = {
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
        })
@EnableConfigurationProperties(AuthorityServiceProperties.class)
public class AuthorityApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthorityApplication.class, args);
    }
}
