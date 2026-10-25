package cn.agentplatform.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 详设第一部分：服务边界独立部署，公共安全能力显式纳入扫描。 */
@SpringBootApplication(scanBasePackages = {"cn.agentplatform.app", "cn.agentplatform.common"})
public class AppApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppApplication.class, args);
    }
}
