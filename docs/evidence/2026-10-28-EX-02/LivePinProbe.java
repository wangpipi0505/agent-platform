import cn.agentplatform.app.infra.ReleasePins;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import java.util.Map;
import java.util.Set;

/** 直接运行真实发布绑定客户端；仅验证客户端/协议缝，不冒充平台发布入口或J9。 */
class LivePinProbe {
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        String tenant = required("EX02_TENANT");
        var actor = new Actor(tenant, "ex02-client-probe", Set.of(), Map.of());
        var pins = new ReleasePins(required("EX02_BASE"),
                "env:AGENT_SECRET_ONTO_APP_BINDER_KEY", true, false, "ex02");
        String space = required("EX02_SPACE"), release = required("EX02_RELEASE");
        var candidate = Map.<String, Object>of("config",
                Map.of("ontology", Map.of("space", space, "releaseId", release)));
        var first = pins.pin(actor, required("EX02_APP"), candidate);
        var again = pins.pin(actor, required("EX02_APP"), candidate);
        var foreign = pins.pin(actor, required("EX02_APP"), Map.of("config",
                Map.of("ontology", Map.of("space", required("EX02_FOREIGN_SPACE"), "releaseId", release))));
        System.out.println(Json.write(Map.of("first", first, "again", again, "foreign", foreign,
                "scope", "ReleasePins against real OR HTTP; not platform publish/J9")));
        if (args.length != 1) throw new IllegalArgumentException("Expected RED, GREEN or REPEAT phase");
        if ("RED".equals(args[0])) {
            if (!"FAILED".equals(first.get("state"))
                    || !String.valueOf(first.get("reason")).contains("HTTP 400"))
                throw new AssertionError("原客户端未在真实缺空间校验处失败");
            return;
        }
        boolean fresh = "GREEN".equals(args[0]);
        if ((!fresh && !"REPEAT".equals(args[0])) || !"PINNED".equals(first.get("state"))
                || !Boolean.valueOf(fresh).equals(first.get("changed"))
                || !"PINNED".equals(again.get("state"))
                || !Boolean.FALSE.equals(again.get("changed"))
                || !first.get("bindingId").equals(again.get("bindingId"))
                || !"FAILED".equals(foreign.get("state"))
                || !String.valueOf(foreign.get("reason")).contains("HTTP 403"))
            throw new AssertionError("真实绑定、幂等或跨空间边界未满足");
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
