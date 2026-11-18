package cn.agentplatform.common;

import java.util.Set;

/**
 * §3.1 授权目录常量（R-17，台账 §7.6b）——平台角色全集的单一出处。
 *
 * <p>此前各服务的授权判定内联角色字符串（{@code actor.roles().contains("OPERATOR")} 等散写），
 * 角色名无单一出处、易拼写漂移；对账端点（T2-12）的 OPERATOR 判定即其中一处。本类收敛目录：
 * 服务端授权判定一律引用本类常量（判定助手 {@link #has}/{@link #hasAny} 替代内联写法），
 * 首批接线=对账端点（TaskReconciliation）与同类授权判定端点（Tasks 恢复投影特权门、
 * ChatSessions 诊断视图门、ConfirmationCenterService 入口门）。
 *
 * <p>目录（8 角色，接口稿权限矩阵角色全集）：
 *
 * <ul>
 *   <li>门户用户面：{@link #USER}、{@link #BUILDER}（{@link #EDITOR} 为 BUILDER 兼容别名，
 *       照 F1-2 page-access 映射）、{@link #APPROVER}、{@link #OPERATOR}（代运维，工作区授权
 *       随工作区授权模型另卡——现状按租户放行的占位口径见 TaskReconciliation）、{@link #ADMIN}；
 *   <li>机器面：{@link #SERVICE}（外部集成票据面，attributes.bizSystemCode 判别）、
 *       {@link #GATEWAY_AUTH}（网关转发面）。
 * </ul>
 *
 * <p>ADMIN 通配语义不在此实现——各端点语义不同（有的 ADMIN 通配、有的仅显式列举，如
 * ConfirmationCenterService 的读取角色不含 ADMIN 通配之外的隐含展开），由调用端点显式书写
 * 判定组合；本类只承载目录名与最基本的集合判定，不做策略。
 */
public final class Roles {

    public static final String USER = "USER";
    public static final String BUILDER = "BUILDER";
    /** BUILDER 兼容别名（F1-2 page-access 映射；发布/确认等写面仍以 BUILDER 权限矩阵为准）。 */
    public static final String EDITOR = "EDITOR";
    public static final String APPROVER = "APPROVER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";
    public static final String SERVICE = "SERVICE";
    public static final String GATEWAY_AUTH = "GATEWAY_AUTH";

    private Roles() {}

    /** 当前身份是否持有指定角色（替代 {@code actor.roles().contains(...)} 内联写法）。 */
    public static boolean has(Actor actor, String role) {
        return actor.roles().contains(role);
    }

    /** 当前身份是否持有任一给定角色（对账端点等"多角色放行"授权链共用）。 */
    public static boolean hasAny(Actor actor, String... roles) {
        for (String role : roles) if (actor.roles().contains(role)) return true;
        return false;
    }

    /** 目录全集视图（测试断言与目录核对用；用例引用常量集合而非散写字面量）。 */
    public static Set<String> catalog() {
        return Set.of(USER, BUILDER, EDITOR, APPROVER, OPERATOR, ADMIN, SERVICE, GATEWAY_AUTH);
    }
}
