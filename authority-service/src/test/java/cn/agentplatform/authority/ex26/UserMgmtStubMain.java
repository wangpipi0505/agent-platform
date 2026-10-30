package cn.agentplatform.authority.ex26;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * EX-26 证据用的 user-mgmt 同形态桩（只做"凭 API-Key 查身份"这一件事）。
 *
 * <p>真实外部依赖是 user-mgmt 服务（默认 18070，接口
 * {@code GET /system/apikeys/identity/self}，请求头 {@code X-API-Key}，HTTP 200 + body 内 code）。
 * 本仓不含 user-mgmt 源码，因此用这个同形态桩让 {@code SessionService.fetchIdentity} 走完整分支；
 * 它只回一份固定身份，不做鉴权策略、不落库、不写日志中的密钥。
 *
 * <p>用法：{@code java UserMgmtStubMain.java 18070 <api-key>}（单文件源码模式，无需编译）。
 * 支持 {@code GET /actuator/health} 作为就绪探针，便于脚本轮询。
 */
public final class UserMgmtStubMain {

    private static final String IDENTITY_PATH = "/system/apikeys/identity/self";

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18070;
        String apiKey = args.length > 1 ? args[1] : "ex26-usermgmt-api-key";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(IDENTITY_PATH, exchange -> {
            String presented = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!apiKey.equals(presented)) {
                respond(exchange, 200, "{\"code\":\"401\",\"message\":\"API-Key 无效\"}");
                return;
            }
            respond(exchange, 200, "{\"code\":\"200\",\"message\":\"ok\",\"data\":{"
                    + "\"userName\":\"ex26-operator\","
                    + "\"nickName\":\"EX26 验证操作员\","
                    + "\"status\":\"0\","
                    + "\"deptId\":\"D0001\","
                    + "\"deptName\":\"平台验证部\","
                    + "\"roles\":[{\"roleKey\":\"ADMIN\",\"dataScope\":\"ALL\"}]}}");
        });
        server.createContext("/actuator/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.start();
        System.out.println("usermgmt-stub listening on 127.0.0.1:" + port + " path=" + IDENTITY_PATH);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private UserMgmtStubMain() {
    }
}
