import com.sun.tools.attach.VirtualMachine;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** 只附加到本卡登记进程，读取白名单配置；密钥仅判存在，不输出值或本地JMX地址。 */
class ServiceConfigurationProbe {
    public static void main(String[] args) throws Exception {
        String address;
        var vm = VirtualMachine.attach(args[0]);
        try { address = vm.startLocalManagementAgent(); } finally { vm.detach(); }
        try (var connection = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            var server = connection.getMBeanServerConnection();
            var bean = new ObjectName("org.springframework.boot:type=Admin,name=SpringApplication");
            var expected = new LinkedHashMap<String, String>();
            String service = args[1];
            expected.put("agent.security.serviceid", service);
            String database = System.getenv("EX01_EXPECTED_DATASOURCE_URL");
            require(present(database), "expected-isolated-database");
            expected.put("spring.datasource.url", database);
            switch (service) {
                case "app" -> {
                    expected.put("agent.ontology.api-key-ref", "env:AGENT_SECRET_ONTO_APP_BINDER_KEY");
                    expected.put("agent.pin.environment", "ex02");
                    require(present(property(server, bean, "AGENT_SECRET_ONTO_APP_BINDER_KEY")), "app-own-key");
                    require(!present(property(server, bean, "AGENT_SECRET_ONTO_ENGINE_READ_KEY")), "app-foreign-key-absent");
                }
                case "engine" -> {
                    expected.put("agent.ontology.api-key-ref", "env:AGENT_SECRET_ONTO_ENGINE_READ_KEY");
                    expected.put("agent.pin.environment", "ex02");
                    expected.put("agent.engine.quota-enabled", "true");
                    expected.put("agent.engine.quota.window-seconds", "97");
                    String limit = args.length > 2 && args[2].equals("unlimited") ? "0" : "301";
                    expected.put("agent.engine.quota.tenant-tokens", limit);
                    expected.put("agent.engine.quota.app-tokens", limit.equals("0") ? "0" : "211");
                    expected.put("agent.engine.quota.credential-tokens", limit.equals("0") ? "0" : "101");
                    require(present(property(server, bean, "AGENT_SECRET_ONTO_ENGINE_READ_KEY")), "engine-own-key");
                    require(!present(property(server, bean, "AGENT_SECRET_ONTO_APP_BINDER_KEY")), "engine-foreign-key-absent");
                }
                case "gateway" -> {
                    expected.put("agent.integration.rate-limit-enabled", "true");
                    expected.put("agent.integration.rate-limit.window-seconds", "17");
                    expected.put("agent.integration.rate-limit.max-requests", args.length > 2 && args[2].equals("unlimited") ? "0" : "2");
                }
                case "skill" -> {
                    boolean legacy = args.length > 2 && args[2].equals("legacy");
                    expected.put("agent.schema.mode", legacy ? "DEGRADED" : "STRICT");
                    expected.put("agent.schema.max-expanded-work", legacy ? "8192" : "128");
                    expected.put("agent.process.enabled", "false");
                    expected.put("agent.skill.service-ticket-mode", legacy ? "LEGACY" : "AUTHORITY");
                }
                default -> throw new IllegalArgumentException("Unknown registered service");
            }
            if (!service.equals("app") && !service.equals("engine") && !(args.length > 2 && args[2].equals("legacy"))) {
                require(!present(property(server, bean, "AGENT_SECRET_ONTO_APP_BINDER_KEY")), "unassigned-binder-key-absent");
                require(!present(property(server, bean, "AGENT_SECRET_ONTO_ENGINE_READ_KEY")), "unassigned-read-key-absent");
                require(!present(property(server, bean, "AGENT_ONTOLOGY_API_KEY_REF")), "unassigned-ref-absent");
            }
            for (var entry : expected.entrySet()) {
                require(entry.getValue().equals(property(server, bean, entry.getKey())), entry.getKey());
            }
            System.out.println("EX01_JMX_PASS service=" + service + " checks=" + expected.size() + " valuesLogged=false");
        }
        // 独立诊断CLI在全部断言与连接关闭后退出，不让本地RMI辅助线程延长探针生命周期。
        System.exit(0);
    }
    private static String property(MBeanServerConnection server, ObjectName bean, String name) throws Exception {
        try { return (String) server.invoke(bean, "getProperty", new Object[]{name}, new String[]{"java.lang.String"}); }
        catch (javax.management.MBeanException e) {
            if (e.getTargetException() instanceof IllegalStateException) return null;
            throw e;
        }
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String key) { if (!condition) throw new AssertionError("Config check failed: " + key); }
}
