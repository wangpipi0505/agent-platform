package cn.agentplatform.engine.harness.context;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * R-15（spill 接入工具环）：外溢组件装配——{@link SpillStore} 入 Spring 容器，供
 * AgentDriver 超限分支可选注入（{@code @Autowired(required=false)}，工具环接线点）。
 *
 * <p>对象存储接收侧（{@link SpillStore.StorageSink}）与读取侧（
 * {@link SpillStore.StorageSource}）生产实现为 MinIO ArtifactStore——引擎模块暂无对象
 * 存储 SDK 依赖，本配置不提供；两者到位后经各自注入点（AgentDriver.spillSinkFactory、
 * PromptAssembler 构造）激活落盘/取回，无需再改工具环。未装配时的运行口径如实降级：
 * 超限保持 OUTPUT_TOO_LARGE 错误信封（调用事实仍保留完整原文），落盘/取回链路只在
 * 组件齐备的部署形态生效。
 */
@Configuration
public class SpillWiringConfiguration {

  @Bean
  public SpillStore spillStore(JdbcTemplate db, PlatformTransactionManager tx) {
    return new SpillStore(db, tx);
  }
}
