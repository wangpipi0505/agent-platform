import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.SecretVault;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.FileSystemResource;

/** 使用当次JAR里的真实客户端及YAML，经Spring注入环境；只输出协议结果，不代表发布入口J9。 */
class ConfiguredOntologyClientProbe {
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        String stage = args[0], tenant = required("EX01_TENANT"), app = required("EX01_APP");
        var actor = new Actor(tenant, "ex01-service-config-probe", Set.of(), Map.of());
        if (stage.equals("readonly-negative")) {
            String key = SecretVault.resolve(required("AGENT_ONTOLOGY_API_KEY_REF"), actor);
            var request = HttpRequest.newBuilder(URI.create(required("AGENT_ONTOLOGY_URL") + "/onto/internal/release-bindings"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("X-API-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("spaceId", required("EX01_SPACE"),
                    "platformTenant", tenant, "appCode", app, "environment", "ex02", "releaseId", required("EX01_RELEASE")))))
                .build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 403 || !Json.read(response.body()).path("code").asText().equals("NO_PERMISSION"))
                throw new AssertionError("Readonly write rejection missing");
            System.out.println("EX01_CLIENT_PASS stage=readonly-negative http=403 code=NO_PERMISSION");
            return;
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            var yaml = new YamlPropertySourceLoader().load("actual-packaged-yaml", new FileSystemResource(args[1]));
            for (var source : yaml) context.getEnvironment().getPropertySources().addLast(source);
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            Class<?> type = Class.forName(stage.equals("app-pin") ? "cn.agentplatform.app.infra.ReleasePins" : "cn.agentplatform.engine.infra.OntologyContextPacks");
            context.register(type);
            context.refresh();
            Object bean = context.getBean(type);
            if (stage.equals("app-pin")) {
                var candidate = Map.<String,Object>of("config", Map.of("ontology", Map.of("space", required("EX01_SPACE"), "releaseId", required("EX01_RELEASE"))));
                var pin = type.getMethod("pin", Actor.class, String.class, Map.class);
                var first = (Map<?,?>) pin.invoke(bean, actor, app, candidate);
                var again = (Map<?,?>) pin.invoke(bean, actor, app, candidate);
                if (!"PINNED".equals(first.get("state")) || !Boolean.TRUE.equals(first.get("changed")) || !Boolean.FALSE.equals(again.get("changed")) || !first.get("bindingId").equals(again.get("bindingId")))
                    throw new AssertionError("Configured binder client failed");
                System.out.println("EX01_CLIENT_PASS stage=app-pin state=PINNED firstChanged=true againChanged=false bindingId=" + first.get("bindingId"));
            } else {
                var binding = type.getMethod("resolveBinding", String.class, String.class, String.class, Actor.class).invoke(bean, tenant, app, "ex02", actor);
                String release = String.valueOf(binding.getClass().getMethod("releaseId").invoke(binding));
                if (!required("EX01_RELEASE").equals(release)) throw new AssertionError("Binding release mismatch");
                Object pack = type.getMethod("fetch", String.class, String.class, String.class, Actor.class).invoke(bean, tenant, required("EX01_SPACE"), release, actor);
                if (pack == null) throw new AssertionError("Configured readonly fetch failed");
                System.out.println("EX01_CLIENT_PASS stage=engine-read bindingMatches=true contextPackFetched=true bindingId=" + binding.getClass().getMethod("bindingId").invoke(binding));
            }
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
