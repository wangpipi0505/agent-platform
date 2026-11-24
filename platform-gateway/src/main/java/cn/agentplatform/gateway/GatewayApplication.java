package cn.agentplatform.gateway;

import cn.agentplatform.common.authority.PlatformAccessClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** 详设第一部分：服务边界独立部署，公共安全能力显式纳入扫描。 */
@SpringBootApplication(scanBasePackages = {"cn.agentplatform.gateway", "cn.agentplatform.common"})
@Import(PlatformAccessClient.class)
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
