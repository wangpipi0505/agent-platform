package cn.agentplatform.common.auth;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecretVault;

import java.util.Map;

/**
 * 技能契约 {@code authScheme} 字段的唯一权威定义（PLAT-11）：取值、解析、头映射与注册期校验
 * 只在本类实现，其他模块不得各自解释字符串，否则同一契约会在不同入口被当成不同鉴权方式。
 *
 * <p>不变量一：契约里能存的取值只有 {@link #BEARER}、{@link #API_KEY}、{@link #NONE} 的枚举名。
 * 注册期归一化后一律显式落库，执行期不再依赖“缺字段=旧行为”的隐含分支；只有尚未重新保存的
 * 历史契约才在读取时按缺省解释，且解释结果必然与改造前一致。
 *
 * <p>不变量二：默认值必须是 {@code BEARER}。本字段出现之前所有HTTP出站都硬编码
 * {@code Authorization: Bearer <secret>}，默认值只要偏离一次，存量技能就会被静默换掉对外凭据
 * 形态——远端返回401而平台侧没有任何“我改了什么”的记录。这就是
 * {@code agent.skill.auth.legacy-bearer} 只做收紧（关闸）而不做放宽（改默认）的原因。
 *
 * <p>兼容窗口：{@code legacy-bearer=true}（默认）时未声明字段的老契约归一化为BEARER；
 * 运维确认在用契约都已显式声明后可置false，此时未声明即拒绝注册（400
 * {@value #CODE_REQUIRED}），逼迫重新保存/发布的契约写清鉴权方式，而不是继续被补成BEARER。
 * 开关只作用于注册期：已发布的契约在读取侧永远按存储值执行，避免运行中改变存量技能的出站行为。
 *
 * <p>不变量三：{@code NONE} 与 {@code authRef} 互斥。NONE 的语义是“对端不需要鉴权”，此时仍
 * 保存一份可解析的凭据只有两种可能：配置遗留（凭据继续被保管、继续出现在审计与掩码面）或声明
 * 写错（以为不发鉴权、实际打算发）。两者都是配置错误，注册期拒绝比运行期默默忽略凭据更能暴露
 * 问题——后者会让“我明明配了密钥却不生效”变成一次无声的排障。
 *
 * <p>有意限制：{@code API_KEY} 只接受HTTP适配器。MCP与ALGORITHM的出站鉴权在各自适配器内实现
 * （SSE transport 头、SDK apiKey 前缀），不受本字段管辖；允许它们声明 API_KEY 等于在契约里
 * 承诺一个当前实现不会兑现的鉴权方式，这正是本卡要消除的“声明与行为不一致”。
 */
public enum AuthScheme {
    /** 默认方式：{@code Authorization: Bearer <secret>}，与改造前的HTTP出站完全一致。 */
    BEARER("Authorization"),
    /** 只发送 {@code X-API-Key: <secret>}，且不得发送Authorization（多发的头会让对端按错误凭据拒绝）。 */
    API_KEY("X-API-Key"),
    /** 不发送任何鉴权头，也不允许配置authRef。 */
    NONE(null);

    /** 契约JSON中的字段名；执行侧按此键读取，注册侧按此键归一化。 */
    public static final String FIELD = "authScheme";

    /** 声明值不在枚举内。 */
    public static final String CODE_INVALID = "SKILL_AUTH_SCHEME_INVALID";
    /** legacy-bearer=false 时未显式声明。 */
    public static final String CODE_REQUIRED = "SKILL_AUTH_SCHEME_REQUIRED";
    /** scheme与authRef组合自相矛盾（NONE带凭据、已存契约声明与配置不一致）。 */
    public static final String CODE_CONFLICT = "SKILL_AUTH_SCHEME_CONFLICT";
    /** 声明了需要凭据的方式，但凭据不存在或无法解析。 */
    public static final String CODE_CREDENTIAL_MISSING = "SKILL_CREDENTIAL_MISSING";

    private final String headerName;

    AuthScheme(String headerName) {
        this.headerName = headerName;
    }

    /** 该方式使用的鉴权头名；{@link #NONE} 为null（不发头）。 */
    public String headerName() {
        return headerName;
    }

    /** 由已解析的密钥值构造头发送值；{@link #NONE} 不接受凭据。 */
    public String headerValue(String secret) {
        if (this == NONE) throw new IllegalStateException("NONE不发送鉴权头");
        if (secret == null || secret.isBlank())
            throw new PlatformException(401, CODE_CREDENTIAL_MISSING, "技能凭据为空");
        return this == BEARER ? "Bearer " + secret : secret;
    }

    /** 严格解析显式声明值；大小写必须与枚举名一致，错误输入不接受“猜测意图”。 */
    public static AuthScheme parse(Object declared) {
        String text = declared == null ? "" : declared.toString().trim();
        for (AuthScheme scheme : values()) if (scheme.name().equals(text)) return scheme;
        throw invalid(declared);
    }

    /**
     * 注册期归一化：决定“未声明”是补默认值还是拒绝，并保证返回值一定是可落库的显式取值。
     *
     * @param declared 契约中的原始值，null/空白视为未声明
     * @param legacyBearer 兼容开关；false 表示契约必须显式声明
     */
    public static AuthScheme registration(Object declared, boolean legacyBearer) {
        String text = declared == null ? "" : declared.toString().trim();
        if (text.isEmpty()) {
            if (!legacyBearer)
                throw new PlatformException(
                        400,
                        CODE_REQUIRED,
                        "已关闭legacy-bearer：技能契约必须显式声明authScheme（BEARER/API_KEY/NONE）");
            return BEARER;
        }
        return parse(declared);
    }

    /**
     * 注册期组合校验：scheme与authRef、适配器类型必须彼此相容。
     *
     * <p>统一用400表达“契约不合法”，让编辑器把错误定位到表单字段；运行期同样条件用409/401，
     * 因为那时它已不是用户输入错误，而是已存契约与配置不一致或凭据缺失。
     */
    public static void checkCombination(AuthScheme scheme, String authRef, String adapterType) {
        if (scheme == null) throw invalid(null);
        boolean hasRef = authRef != null && !authRef.isBlank();
        if (scheme == NONE && hasRef)
            throw new PlatformException(
                    400,
                    CODE_CONFLICT,
                    "authScheme=NONE表示对端不需要鉴权，不能同时配置authRef；请删除authRef或改用BEARER/API_KEY");
        if (scheme == API_KEY) {
            if (!"HTTP".equals(adapterType))
                throw new PlatformException(
                        400,
                        CODE_INVALID,
                        "authScheme=API_KEY仅支持HTTP技能；MCP/ALGORITHM的出站鉴权不走该字段");
            if (!hasRef)
                throw new PlatformException(
                        400, CODE_CREDENTIAL_MISSING, "authScheme=API_KEY必须配置authRef");
        }
    }

    /**
     * 执行期把契约字段翻译成实际头发送表；空表表示不带鉴权头。
     *
     * <p>凭据解析失败必须在发请求之前抛出：先发一次无鉴权（或错鉴权）的请求再看远端报错，会把
     * 配置错误伪装成业务失败，还可能让不支持幂等的端点先执行一次副作用。
     *
     * <p>兼容窗口：BEARER且authRef为空时返回空表——改造前“没配凭据就不发头”的技能必须继续
     * 原样工作；API_KEY没有凭据则是声明本身不自洽，直接401而不是静默降级成无鉴权请求。
     */
    public static Map<String, String> headers(Object declared, String authRef) {
        String text = declared == null ? "" : declared.toString().trim();
        AuthScheme scheme = text.isEmpty() ? BEARER : parse(declared);
        boolean hasRef = authRef != null && !authRef.isBlank();
        if (scheme == NONE) {
            if (hasRef)
                throw new PlatformException(
                        409,
                        CODE_CONFLICT,
                        "已存契约声明NONE却携带authRef；请重新保存技能以修正契约");
            return Map.of();
        }
        if (!hasRef) {
            if (scheme == API_KEY)
                throw new PlatformException(
                        401, CODE_CREDENTIAL_MISSING, "技能声明API_KEY但未配置authRef");
            return Map.of();
        }
        String secret = resolve(authRef.trim());
        return Map.of(scheme.headerName(), scheme.headerValue(secret));
    }

    /**
     * 解析凭据并归一错误语义：租户越权（403 {@code SECRET_SCOPE_DENIED}）保留原有语义，
     * 其余“取不到密钥”的失败（引用未配置、文件缺失、GCM校验失败、格式非法）统一收敛为
     * 401 {@value #CODE_CREDENTIAL_MISSING}，避免把实现细节（路径、密钥ID）暴露给调用方。
     */
    public static String resolve(String authRef) {
        try {
            return SecretVault.resolve(authRef);
        } catch (PlatformException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new PlatformException(
                    401,
                    CODE_CREDENTIAL_MISSING,
                    "技能凭据不可用：authRef指向的密钥无法解析（" + unavailable.getClass().getSimpleName() + "）");
        }
    }

    private static PlatformException invalid(Object declared) {
        return new PlatformException(
                400,
                CODE_INVALID,
                "authScheme只能是BEARER/API_KEY/NONE，收到：" + String.valueOf(declared));
    }
}
