package cn.agentplatform.authority.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 票据 {@code iss} 的唯一来源。
 * <p>签发方必须与验证方看到同一个 issuer，否则平台侧按 issuer 选 JWKS 会直接失败，所以：
 * <ul>
 *   <li>生产：{@code agent.authority.issuer}（{@code AGENT_AUTHORITY_ISSUER}）显式配置，且必须与
 *       {@code jwksUri} 同源；</li>
 *   <li>开发/测试：只在绑定地址是回环时按实际端口推导，避免随机端口下写死 issuer。</li>
 * </ul>
 * 非回环绑定又没有显式配置时启动失败（fail-closed）：宁可起不来，也不要签发一张 issuer 错误的票。
 */
@Component
public final class IssuerResolver implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(IssuerResolver.class);

    private final AuthorityServiceProperties properties;
    private final String bindAddress;
    private volatile String issuer;

    public IssuerResolver(AuthorityServiceProperties properties, @Value("${server.address:}") String bindAddress) {
        this.properties = properties;
        this.bindAddress = bindAddress;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (properties.hasConfiguredIssuer()) {
            this.issuer = AuthorityServiceProperties.requireValidIssuer(properties.issuer());
            log.info("authority.issuer configured issuer={} bind={}", this.issuer, bindAddress);
            return;
        }
        if (!loopback(bindAddress)) {
            throw new IllegalStateException(
                    "AUTHORITY_ISSUER_REQUIRED: 绑定地址 " + bindAddress + " 不是回环，必须显式配置 agent.authority.issuer"
                            + "（AGENT_AUTHORITY_ISSUER），且与 jwksUri 同源");
        }
        this.issuer = "http://127.0.0.1:" + event.getWebServer().getPort();
        log.warn("authority.issuer derived from loopback bind issuer={}：生产部署必须显式配置 AGENT_AUTHORITY_ISSUER",
                this.issuer);
    }

    /** 请求期一定发生在端口绑定之后；未就绪直接失败而不是签出 issuer 为空的票。 */
    public String issuer() {
        String resolved = issuer;
        if (resolved == null) {
            throw new IllegalStateException("AUTHORITY_ISSUER_UNAVAILABLE: 签发服务尚未完成端口绑定");
        }
        return resolved;
    }

    public String jwksUri() {
        return issuer() + "/.well-known/jwks.json";
    }

    private static boolean loopback(String address) {
        if (address == null || address.isBlank()) {
            // 未显式绑定 = 监听全部网卡，不能当回环处理。
            return false;
        }
        String value = address.trim();
        if ("localhost".equalsIgnoreCase(value)) {
            return true;
        }
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
