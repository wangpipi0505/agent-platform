package cn.agentplatform.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 详设第一部分：服务边界独立部署，公共安全能力显式纳入扫描。 */
@SpringBootApplication(scanBasePackages = {"cn.agentplatform.monitor", "cn.agentplatform.common"})
public class MonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}
