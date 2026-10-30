package cn.agentplatform.authority.client;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用方凭据表：{@code client_id} 只能是平台服务名，secret 由 secret_ref 注入（本服务从环境变量读，
 * 不落配置文件）。
 * <p>空 secret 视为"该客户端未启用"：未配置凭据必须 401 而不是"用空串就能过"。比较用
 * {@link MessageDigest#isEqual} 做定长比较，避免短路比较泄露前缀信息。
 */
@Component
public final class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);
    private static final String BASIC = "Basic ";

    private final Map<String, String> secrets;

    public ClientRegistry(AuthorityServiceProperties properties) {
        this.secrets = Collections.unmodifiableMap(new LinkedHashMap<>(properties.enabledClients()));
        if (secrets.isEmpty()) {
            log.warn("authority.clients empty：没有任何客户端凭据，所有票据/决策请求都会 401"
                    + "（需注入 AGENT_SECRET_IAM_<SERVICE>_CLIENT）");
        } else {
            log.info("authority.clients enabled={}", secrets.keySet());
        }
    }

    /** 返回认证通过的 client_id；任何缺失/解码失败/未知客户端/secret 不匹配都抛 401。 */
    public String authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
            throw AuthorityException.invalidClient("缺少 Basic 凭据");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(BASIC.length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw AuthorityException.invalidClient("Basic 凭据不是合法 base64");
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            throw AuthorityException.invalidClient("Basic 凭据缺少 client_id:client_secret 结构");
        }
        String clientId = decoded.substring(0, colon);
        String presented = decoded.substring(colon + 1);
        String expected = secrets.get(clientId);
        if (expected == null) {
            throw AuthorityException.invalidClient("client_id 未配置或未启用: " + clientId);
        }
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            throw AuthorityException.invalidClient("client_secret 不匹配: " + clientId);
        }
        return clientId;
    }

    public boolean known(String clientId) {
        return clientId != null && secrets.containsKey(clientId);
    }

    public java.util.Set<String> enabledClients() {
        return secrets.keySet();
    }
}
