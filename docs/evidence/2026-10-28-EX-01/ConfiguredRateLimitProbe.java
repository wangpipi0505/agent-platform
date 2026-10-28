import cn.agentplatform.common.PlatformException;
import cn.agentplatform.gateway.application.IntegrationAccess;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** 验证当次JAR/YAML的真实@Value装配与入口限流器；不签发票据、不调用下游，不冒充HTTP业务验收。 */
class ConfiguredRateLimitProbe {
    public static void main(String[] args) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (var source : new YamlPropertySourceLoader().load("actual-packaged-yaml", new FileSystemResource(args[0])))
                context.getEnvironment().getPropertySources().addLast(source);
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            // 此探针只调用rateLimit，解码器协作者禁止被调用，不能生成假鉴权成功。
            context.registerBean(JwtDecoder.class, () -> token -> { throw new AssertionError("Decoder must remain unused"); });
            context.register(IntegrationAccess.class);
            context.refresh();
            var rate = context.getBean(IntegrationAccess.class).rateLimit();
            if (args[1].equals("unlimited")) {
                for (int i = 0; i < 32; i++) rate.admit(91001, null, "/ex01-config-only");
                System.out.println("EX01_RATE_PASS enabled=true zeroLimitBypasses=true admitted=32 scope=configured-bean-only");
            } else {
                rate.admit(91001, null, "/ex01-config-only");
                rate.admit(91001, null, "/ex01-config-only");
                try { rate.admit(91001, null, "/ex01-config-only"); throw new AssertionError("Configured upper limit ignored"); }
                catch (PlatformException rejected) {
                    if (!rejected.code().equals("INTEGRATION_RATE_LIMITED") || !rejected.getMessage().contains("17 秒窗口") || !rejected.getMessage().contains("上限 2 个")) throw rejected;
                }
                System.out.println("EX01_RATE_PASS enabled=true thirdRejected=true configuredWindowMatched=true scope=configured-bean-only");
            }
        }
    }
}
