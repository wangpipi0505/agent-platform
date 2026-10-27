package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.ServiceTokens;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTicketProvider;
import cn.agentplatform.common.authority.VerifiedAuthority;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * G-8c 服务票据签发缝：skill 服务出站调用的票据从"哪签"变成一个可替换的点，但默认行为不变。
 *
 * <p>两态，且互不回落：
 *
 * <ul>
 *   <li>{@link #LEGACY}（默认）：逐字沿用 {@link ServiceTokens#issue(Actor)} 的 HS256 自签，不读
 *       {@link AuthorityContext}、不查路由目录。这是今天的行为，也是回滚目标。
 *   <li>{@link #AUTHORITY}：只认"当前请求已绑定的 {@link VerifiedAuthority} + 目录登记 + 装配好的
 *       {@link ServiceTicketProvider}"。{@code ServiceTokens} 在这一态里完全不参与。
 * </ul>
 *
 * <p><b>为什么 AUTHORITY 绝不回落 LEGACY</b>：IAM 部署下，{@code ServiceTokens.issue} 已经拒绝签发
 * （{@code 403 AUTHORITY_CONTEXT_MISSING}），它的票也不再被下游的验签链接受。若在"取不到授权"时
 * 退回自签，实际效果就是把"这个请求没有可信授权"翻译成一张下游会当作合法机器的票据——等于用
 * 一条本地签名绕过整条撤销/审计链，比直接失败危险得多。所以这里宁可硬失败：缺授权就是 403，
 * 目录未登记就是 403，签发器没装配就是 500（装配错误不能被伪装成鉴权失败）。
 *
 * <p><b>模板匹配只用于签发票据，不用于判权</b>：{@link #templateOf} 只是把具体 URL 还原成目录里
 * 形如 {@code /internal/tool-calls/{id}/fact} 的<b>模板串</b>，好去凑 {@link ServiceRoutePolicy#require}
 * 那个 exact 的 (caller,target,method,template) 键。是否允许这次调用，唯一的裁决点是
 * {@code require(...)} 命中了目录行；形状匹配本身不授予任何权限，匹配不上时传进去的就是原始 path，
 * 必然命中不了目录，于是统一得到 {@code 403 SYS-AUTH-403}。
 *
 * <p>目标服务只从 host:port 判定（与 {@code agent.services.*} 同源），不从路径反推；判定不出就失败，
 * 因为"猜一个服务名去签票"正是模板匹配不该越的界。
 */
@Component
public final class ServiceTickets {
    public static final String LEGACY = "LEGACY";
    public static final String AUTHORITY = "AUTHORITY";

    static final String MISSING_AUTHORITY = "AUTHORITY_CONTEXT_MISSING";
    static final String MISSING_AUTHORITY_MESSAGE = "当前请求没有可验证授权，无法签发服务票据";
    static final String PROVIDER_MISSING = "SERVICE_TICKET_PROVIDER_MISSING";
    static final String PROVIDER_MISSING_MESSAGE =
            "服务票据签发器未装配：AUTHORITY 模式必须装配 ServiceTicketProvider";

    /** 与 application.yml 的 agent.services.* 同名；名字也必须与目录里的 target 同名。 */
    private static final List<String> SERVICES = List.of("app", "engine", "skill", "model", "monitor");

    private final String mode;
    private final ServiceTicketProvider provider;
    /** 只有 AUTHORITY 会读；LEGACY 下允许为 null（不查目录）。 */
    private final ServiceRoutePolicy policy;
    /** origin（scheme://host:port）→ 服务名；空表即"任何目标都不可判定"。 */
    private final Map<String, String> targets;

    /** 只选模式的便捷入口：LEGACY 不需要任何依赖，AUTHORITY 缺 provider 时签发即 500。 */
    public ServiceTickets(String mode) {
        // 显式转型消歧：另一个三参构造是容器入口 (String, ObjectProvider, Environment)。
        this(mode, (ServiceTicketProvider) null, (ServiceRoutePolicy) null);
    }

    /** 手工装配入口（测试/夹具）：不给服务清单时，host:port 判定不出目标即失败。 */
    public ServiceTickets(String mode, ServiceTicketProvider provider, ServiceRoutePolicy policy) {
        this(mode, provider, policy, Map.of());
    }

    /**
     * @param mode {@code LEGACY} / {@code AUTHORITY}；null 或空白按默认 {@code LEGACY}
     * @param provider AUTHORITY 下必须非空，否则每次签发都是 500
     * @param policy AUTHORITY 下传 null 会用类路径目录自建；LEGACY 下可以为 null
     * @param serviceBaseUrls 与 {@code agent.services.*} 同源，用于把 URL 的 host:port 还原成服务名
     */
    public ServiceTickets(
            String mode,
            ServiceTicketProvider provider,
            ServiceRoutePolicy policy,
            Map<String, String> serviceBaseUrls) {
        String normalized = mode == null || mode.isBlank() ? LEGACY : mode.trim().toUpperCase(Locale.ROOT);
        // 拼错的模式名必须启动即失败：静默退回 LEGACY 会让切换期的 IAM 部署继续自签。
        if (!LEGACY.equals(normalized) && !AUTHORITY.equals(normalized))
            throw new IllegalArgumentException("SERVICE_TICKET_MODE_INVALID: " + mode);
        this.mode = normalized;
        this.provider = provider;
        this.policy = policy != null || !AUTHORITY.equals(normalized) ? policy : new ServiceRoutePolicy();
        this.targets = origins(serviceBaseUrls);
    }

    /** 容器装配入口：模式与 agent.services.* 都来自配置；签发器允许缺席（缺席即 AUTHORITY 下 500）。 */
    @Autowired
    public ServiceTickets(
            @Value("${agent.skill.service-ticket-mode:LEGACY}") String mode,
            ObjectProvider<ServiceTicketProvider> provider,
            Environment environment) {
        this(mode, provider.getIfAvailable(), null, serviceBaseUrls(environment));
    }

    /**
     * 为一次内部调用签发票据。{@code caller} 固定 {@code "skill"}，{@code method} 是 HTTP 方法。
     *
     * <p>LEGACY 与 AUTHORITY 的差别是"票从哪来"，不是"要不要票"：两条路都在传输之前完成，
     * 失败都不会被下游不可用的 502 吞掉（G-8b 的语义保持不变）。
     */
    public String ticket(Actor actor, String caller, String method, String url) {
        if (LEGACY.equals(mode)) {
            // 逐字沿用改动前：不读 AuthorityContext、不查目录，票的形状与 TTL 都不变。
            return ServiceTokens.issue(actor);
        }
        VerifiedAuthority authority = currentAuthority();
        // target 只由 host:port 决定；判定不出就置空，空名不可能命中目录行，必然 403。
        String target = Objects.toString(targetOf(url), "");
        String template = templateOf(caller, target, method, pathOf(url));
        ServiceCall call = policy.require(caller, target, method, template);
        ServiceTicketProvider issuer = provider;
        if (issuer == null)
            // 装配错误不是鉴权失败：403 会把运维引向授权配置，真因是容器里没有签发器。
            throw new PlatformException(500, PROVIDER_MISSING, PROVIDER_MISSING_MESSAGE);
        return issuer.issue(authority, call).value();
    }

    /**
     * 取当前请求的已验证授权。{@link AuthorityContext#current()} 在"未绑定/已关闭/已失效"时抛
     * {@code 403 AUTHORITY_CONTEXT_MISSING} 而不是返回 null；这里只把这一种归一成签发缝的统一文案，
     * 其它授权错误（跨线程、协议拒绝、验签失败）原码原样上抛——"授权被撤销"不能伪装成"没配置"。
     */
    private static VerifiedAuthority currentAuthority() {
        VerifiedAuthority authority;
        try {
            authority = AuthorityContext.current();
        } catch (PlatformException error) {
            if (MISSING_AUTHORITY.equals(error.code())) throw missingAuthority();
            throw error;
        }
        if (authority == null) throw missingAuthority();
        return authority;
    }

    private static PlatformException missingAuthority() {
        return new PlatformException(403, MISSING_AUTHORITY, MISSING_AUTHORITY_MESSAGE);
    }

    /** host:port → 服务名；不在清单内返回 null。 */
    private String targetOf(String url) {
        String origin = origin(url);
        return origin == null ? null : targets.get(origin);
    }

    /**
     * 把具体 path 还原成目录模板串，<b>只用于签发票据</b>（见类注释）：命中零条或多条时返回原始
     * path，于是 {@link ServiceRoutePolicy#require} 必然拒绝——多义模板宁可失败，也不猜一条去签票。
     */
    private String templateOf(String caller, String target, String method, String path) {
        String matched = null;
        for (var row : policy.routes()) {
            if (!method.equals(row.method())
                    || !caller.equals(row.caller())
                    || !Objects.equals(target, row.target())
                    || !sameShape(row.template(), path)) continue;
            if (matched != null && !matched.equals(row.template())) return path;
            matched = row.template();
        }
        return matched == null ? path : matched;
    }

    /** 模板按 / 分段：{@code {}} / {@code {id}} 段匹配任意单段，字面段必须逐段相等，段数也必须相等。 */
    static boolean sameShape(String template, String path) {
        String[] expected = segments(template);
        String[] actual = segments(path);
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            String segment = expected[i];
            if (segment.startsWith("{") && segment.endsWith("}")) continue;
            if (!segment.equals(actual[i])) return false;
        }
        return true;
    }

    private static String[] segments(String value) {
        return Arrays.stream(Objects.toString(value, "").split("/"))
                .filter(segment -> !segment.isEmpty())
                .toArray(String[]::new);
    }

    /** 归一成 scheme://host:port：默认端口按 scheme 补齐，host 小写，避免书写差异漏判。 */
    static String origin(String url) {
        try {
            var parsed = URI.create(url.trim());
            String host = parsed.getHost();
            if (host == null) return null;
            String scheme = parsed.getScheme() == null ? "http" : parsed.getScheme().toLowerCase(Locale.ROOT);
            int port = parsed.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : parsed.getPort();
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** 取 URL 的 path；解析不了就原样返回，交给 require 得到统一的 403。 */
    static String pathOf(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException malformed) {
            return url;
        }
    }

    private static Map<String, String> origins(Map<String, String> serviceBaseUrls) {
        if (serviceBaseUrls == null) return Map.of();
        var byOrigin = new HashMap<String, String>();
        serviceBaseUrls.forEach(
                (service, baseUrl) -> {
                    if (service == null || baseUrl == null || baseUrl.isBlank()) return;
                    String origin = origin(baseUrl);
                    if (origin != null) byOrigin.put(origin, service);
                });
        return Map.copyOf(byOrigin);
    }

    /** 与 application.yml 的 agent.services.* 同名读取；缺项即"目标不可判定"，宁可不签。 */
    private static Map<String, String> serviceBaseUrls(Environment environment) {
        var urls = new HashMap<String, String>();
        for (String service : SERVICES) {
            String baseUrl = environment.getProperty("agent.services." + service);
            if (baseUrl != null && !baseUrl.isBlank()) urls.put(service, baseUrl);
        }
        return Map.copyOf(urls);
    }
}
