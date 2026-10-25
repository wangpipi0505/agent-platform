package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * R-A7 PG 集成：AgentDriver 在外部派发前调用 pre 链、结果确定后调用 post 链，
 * 且 post 的纯变换结果真实回灌到下一轮请求的工具消息内容中。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ToolInterceptorWiringPostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    @Test
    void driverInvokesChainsAroundSkillCallAndFeedsTransformBack() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var entry = Json.read("{\"modelEntryId\":1}");
        var config =
                Json.read(
                        ("{\"modelEntryId\":1,\"guard\":{\"roles\":[\"EDITOR\"]},\"tools\":[{"
                                        + "\"code\":\"lookup\",\"name\":\"lookup\","
                                        + "\"skillVersionId\":42,\"contractDigest\":\"%s\","
                                        + "\"definition\":{\"type\":\"function\",\"function\":"
                                        + "{\"name\":\"lookup\",\"parameters\":"
                                        + "{\"type\":\"object\"}}}}]}")
                                .formatted(Digest.sha256("fixture-contract")));
        var snapshot =
                Json.read(
                        ("{\"appCode\":\"test\",\"type\":\"CHAT\",\"config\":%s,"
                                        + "\"definition\":{\"nodes\":[],\"edges\":[]},"
                                        + "\"dependencyVersions\":{\"modelRefs\":[{"
                                        + "\"modelEntryId\":1,\"entry\":{"
                                        + "\"modelEntryId\":1},\"modelBindingRef\":{"
                                        + "\"bindingId\":1,\"sha256\":\"%s\"}}]}}")
                                .formatted(Json.write(config), Digest.sha256(Json.canonical(entry))));
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), r);
        var node = TracePostgresTest.store.claim(n, "replica");
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", r);

        List<String> order = new ArrayList<>();
        List<String> toolContents = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor a) {
                        if (path.endsWith("/internal/model/measure"))
                            return Json.read(
                                    "{\"inputTokens\":10,\"contextWindow\":100000"
                                            + ",\"maxOutputTokens\":4096,\"tokenizer\":"
                                            + "\"fixture\",\"exact\":true}");
                        return Json.read("{\"status\":\"SUCCEEDED\",\"result\":{\"value\":1}}");
                    }

                    @Override
                    public JsonNode chatStream(Object body, Actor a, Consumer<String> chunk) {
                        int current = round.getAndIncrement();
                        if (current == 1)
                            Json.tree(body)
                                    .path("messages")
                                    .forEach(
                                            m -> {
                                                if ("tool".equals(m.path("role").asText()))
                                                    toolContents.add(m.path("content").asText());
                                            });
                        return current == 0
                                ? Json.read(
                                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\","
                                                + "\"name\":\"lookup\",\"arguments\":"
                                                + "{\"x\":1}}],\"usage\":{}}")
                                : Json.read("{\"text\":\"done\",\"usage\":{}}");
                    }
                };
        var chain =
                new ToolInterceptorChain(
                        List.of((code, version, args, actor) -> order.add("pre:" + code)),
                        List.of(
                                (code, result, actor) -> {
                                    order.add("post:" + code + ":" + result.toString());
                                    var wrapped = (ObjectNode) result.deepCopy();
                                    wrapped.put("touched", true);
                                    return wrapped;
                                }));
        var driver = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        driver.interceptors(chain);
        var terminal =
                driver.drive(run, node, config, Json.read("{}"), TracePostgresTest.actor, false);
        assertEquals("done", terminal.path("text").asText());
        assertEquals(List.of("pre:lookup", "post:lookup:{\"value\":1}"), order);
        assertEquals(1, toolContents.size());
        assertTrue(toolContents.getFirst().contains("touched"));
    }
}
