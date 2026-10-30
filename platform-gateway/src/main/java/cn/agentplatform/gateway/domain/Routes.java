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
        if (path.matches("/v1/apps/[A-Za-z0-9_-]+/input-schema")) return "app";
        // 会话所有权与固定版本由运行时校验，不能被后面的应用CRUD前缀吞掉。
        if (path.matches(
                "/(?:admin|v1)/apps/[A-Za-z0-9_-]+/sessions(?:/[A-Za-z0-9_-]+(?:/turns)?)?"))
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
                || path.startsWith("/admin/system")) return "app";
        if (path.startsWith("/admin/skills")) return "skill";
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
