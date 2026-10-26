package cn.agentplatform.model.infra;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Json;
import cn.agentplatform.model.application.FixturePolicy;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * AP-S06 出口守卫：生产/默认模式（agent.demo-mode=false）下，fixture 演示供应商不得参与
 * 真实调用选择。这是数据内容级的门禁，不是代码 seed——演示行早已躺在 PG 里（由 smoke 脚本经
 * 管理 API 写入），唯一可靠的边界是每次真实调用进入前的请求体检查。
 *
 * <p>覆盖四类进入点：/internal/model/chat、/internal/model/measure（engine 发起的真实模型调用
 * 与计量）、/internal/model-bindings/freeze|validate（发布前的冻结与校验），以及管理面
 * POST /admin/model-providers、/admin/model-entries（非演示模式禁止再注入新 fixture 行）。
 * 读取端点与连接测试（/{id}/test）不受限：管理员应能验证 fixture 端点连通性但不用于运行。</p>
 *
 * <p>有意限制：请求体在这里整包缓存后交给下游（chat/stream 请求体为小 JSON，无流式上行）；
 * 未识别的请求形状一律放行，由下游既有校验兜底，守卫只负责“已知形状 + fixture 命中”这一条
 * 确定性拒绝，避免误拦未来新增的合法调用形态。</p>
 */
public class FixtureEgressGuard extends OncePerRequestFilter {
    /** 请求体中直接携带 modelEntryId 的进入点。 */
    private static final Set<String> ENTRY_ID_PATHS =
            Set.of(
                    "/internal/model/chat",
                    "/internal/model/measure",
                    "/internal/model-bindings/freeze",
                    "/internal/model-bindings/validate");
    /** 管理面创建入口：非演示模式禁止注入新的 fixture 行。 */
    private static final Set<String> CREATE_PATHS = Set.of("/admin/model-providers", "/admin/model-entries");

    private final JdbcTemplate db;
    private final boolean demoMode;

    public FixtureEgressGuard(JdbcTemplate db, boolean demoMode) {
        this.db = db;
        this.demoMode = demoMode;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean relevant =
                ("POST".equals(request.getMethod()))
                        && (ENTRY_ID_PATHS.contains(uri)
                                || CREATE_PATHS.contains(uri)
                                || "/internal/model/chat/stream".equals(uri));
        if (relevant && !demoMode) {
            String body = readBody(request);
            JsonNode json = body.isBlank() ? null : Json.read(body);
            String violation = violation(request.getMethod(), uri, json);
            if (violation != null) {
                reject(response, violation);
                return;
            }
            chain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 返回拒绝原因；null 表示放行。所有判定只看编码约定，不依赖数据来源标记。 */
    private String violation(String method, String uri, JsonNode json) {
        if (json == null) return null;
        if (CREATE_PATHS.contains(uri)) {
            if ("/admin/model-providers".equals(uri)
                    && FixturePolicy.isFixtureProviderCode(json.path("code").asText("")))
                return "演示 fixture 供应商只能在演示模式（AGENT_DEMO_MODE=true）下注册；"
                        + "生产/默认模式禁止注入演示数据";
            if ("/admin/model-entries".equals(uri)
                    && isFixtureEntry(json.path("providerId").asLong(0)))
                return "演示 fixture 供应商名下不能新建模型条目；请改用托管供应商或启用演示模式";
            return null;
        }
        if (json.hasNonNull("modelEntryId") && isFixtureEntry(json.path("modelEntryId").asLong()))
            return "模型条目属于演示 fixture 供应商，生产/默认模式不参与真实调用；"
                    + "请切换供应商或启用演示模式";
        var ref = json.path("modelBindingRef");
        if (ref.isObject()
                && isFixtureBinding(
                        ref.path("bindingId").asLong(0), ref.path("sha256").asText("")))
            return "模型冻结引用指向演示 fixture 供应商，生产/默认模式不参与真实调用";
        return null;
    }

    private boolean isFixtureEntry(long entryId) {
        if (entryId <= 0) return false;
        var rows =
                db.queryForList(
                        "select p.code from model.entry e join model.provider p on p.id=e.provider_id"
                                + " where e.id=?",
                        entryId);
        return !rows.isEmpty()
                && FixturePolicy.isFixtureProviderCode(rows.getFirst().get("code").toString());
    }

    private boolean isFixtureBinding(long bindingId, String sha256) {
        if (bindingId <= 0 || sha256.isBlank()) return false;
        var rows =
                db.queryForList(
                        "select provider_id, model_entry_id from model.binding_snapshot"
                                + " where id=? and sha256=?",
                        bindingId,
                        sha256);
        if (rows.isEmpty()) return false;
        long providerId = ((Number) rows.getFirst().get("provider_id")).longValue();
        var code =
                db.queryForList("select code from model.provider where id=?", providerId);
        return !code.isEmpty()
                && FixturePolicy.isFixtureProviderCode(code.getFirst().get("code").toString());
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        // 守卫先于 DispatcherServlet 生效，必须按统一响应形状自行输出业务错误。
        response.setStatus(409);
        response.setContentType("application/json;charset=UTF-8");
        response
                .getWriter()
                .write(
                        Json.write(
                                new ApiResponse<>(
                                        "SYS-MODEL-FIXTURE", message, null, MDC.get("traceId"))));
    }

    private String readBody(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 请求体整包缓存；守卫读完后下游仍拿到完整流，chat/stream 的 SSE 响应不受影响。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, String body) {
            super(request);
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ServletInputStream getInputStream() {
            var buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("同步缓存流不支持读监听");
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
