package cn.agentplatform.gateway.domain;

/** 服务方向由详设固定；不允许客户端提供上游URL，内部API永不透出。 */
public final class Routes {
    private Routes() {}

    public static String service(String path) {
        // 用量事实由运行时提供，精确入口不扩展为任意 usage 子路径。
        if (path.equals("/admin/usage/summary")) return "engine";
        // Named inputs remain runtime resources; promotion's service-only endpoint stays private.
        if (path.matches("/admin/debug-inputs(?:/[0-9]+(?:/promote)?)?")) return "engine";
        if (path.matches("/admin/evaluation-datasets(?:/[^/]+/[^/]+)?")) return "monitor";
        // 能力包仅匹配已登记入口；归属与调用权限仍由应用服务校验。
        if (path.matches(
                "/admin/capability-packages(?:/import|/[1-9][0-9]*(?:/bindings/resolve|/applications)?)?"))
            return "app";
        // 持久接入会话仅开放已登记步骤；资源归属、修订与发布门仍由应用服务核验。
        if (path.matches("/admin/onboarding-sessions(?:/[A-Za-z0-9_-]{1,80}"
                + "(?:/preview|/cancel|/steps/(?:IMPORT_SKILLS|ASSEMBLE|VERIFY|PUBLISH)/execute)?)?"))
            return "app";
        if (path.matches("/v1/apps/[A-Za-z0-9_-]+/input-schema")) return "app";
        // 对外体验投影：只读、脱敏，且由 app 服务按激活发布裁剪，因此提前于会话/运行前缀匹配。
        if (path.matches("/v1/apps/[A-Za-z0-9_-]+/experience")) return "app";
        // 会话所有权与固定版本由运行时校验，不能被后面的应用CRUD前缀吞掉。
        // H1-9：会话事件/控制/SSE 子路径显式接线（events、events/stream、control）。
        if (path.matches(
                "/(?:admin|v1)/apps/[A-Za-z0-9_-]+/sessions(?:/[A-Za-z0-9_-]+"
                    + "(?:/turns|/events(?:/stream)?|/control)?)?"))
            return "engine";
        if (path.matches("/(?:admin|v1)/apps/[^/]+/sessions(?:/.*)?"))
            throw new IllegalArgumentException("此会话入口不存在");
        if (path.matches("/admin/reproductions/[A-Za-z0-9_-]+")) return "engine";
        if (path.equals("/admin/artifacts")
                || path.matches("/admin/artifacts/[A-Za-z0-9_-]+/download")
                || path.matches("/v1/runs/[A-Za-z0-9_-]+/artifacts/[A-Za-z0-9_-]+/download"))
            return "skill";
        if (path.matches("/(?:admin|v1)/apps/[^/]+/runs")) return "engine";
        if (path.startsWith("/admin/apps")
                || path.startsWith("/admin/flows")
                || path.startsWith("/admin/prompt-templates")
                || path.startsWith("/admin/classifications")
                || path.startsWith("/admin/system")
                // 连接档案与环境绑定是配置面资源：虽然由 app 服务持有，但只有 ADMIN 能读写；
                // 这里只决定转发方向，不放宽调用权限（集成票据仍由 IntegrationPolicy 挡在外面）。
                || path.startsWith("/admin/connection-profiles")
                || path.startsWith("/admin/environment-bindings")
                // 体验描述、接入档案与配置发布同样是 app 持有的配置面资源，转发方向与权限分离。
                || path.startsWith("/admin/integration-profiles")
                || path.startsWith("/admin/config-releases")
                // 规则资产（A3-3）：/admin/apps/{code}/rules 随上方 /admin/apps 前缀进入 app；
                // 版本链与启停走独立 /admin/rules/{code}/... 前缀，同样只决定转发方向，不放权。
                || path.startsWith("/admin/rules")
                // 语义绑定目录与配置发布同属 app 持有的配置面资源；转发方向不等于放权。
                || path.startsWith("/admin/semantic-bindings")
                // 业务接入模板目录同属 app 持有的配置面资源；转发方向不等于放权。
                || path.startsWith("/admin/onboarding-templates")) return "app";
        if (path.startsWith("/admin/skills")) return "skill";
        // A3-2：插件治理数据面（来源/发现/版本/授权）指向 skill；GUIDE 双轨沿用上方 /admin/skills 前缀。
        // 只决定转发方向，登记/触发/授权仍由 skill 服务按 ADMIN 角色校验。
        if (path.startsWith("/admin/plugin-sources") || path.startsWith("/admin/plugins"))
            return "skill";
        if (path.startsWith("/admin/services")) return "skill";
        if (path.startsWith("/admin/model-providers")
                || path.startsWith("/admin/model-entries")
                || path.startsWith("/admin/model-health"))
            return "model";
        if (path.startsWith("/admin/debug-runs")
                || path.startsWith("/admin/runs")
                || path.startsWith("/v1/runs")) return "engine";
        if (path.startsWith("/admin/evaluations")
                || path.startsWith("/admin/approvals")
                || path.startsWith("/admin/statistics")
                || path.startsWith("/admin/operations")
                || path.startsWith("/admin/audit")) return "monitor";
        throw new IllegalArgumentException("此入口不存在");
    }
}
