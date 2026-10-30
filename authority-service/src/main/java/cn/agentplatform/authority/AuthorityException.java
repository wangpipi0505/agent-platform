package cn.agentplatform.authority;

/**
 * 拒绝/失败的唯一载体：{@code status+code} 决定 HTTP 语义，{@code message} 是给调用方的
 * 稳定文案（不含内部细节），{@code auditReason} 只进审计日志。
 * <p>刻意不复用 admin-common 的 {@code PlatformException}：签发方需要按协议返回 400/401/403，
 * 而平台侧的 {@code PlatformErrors} 会把异常折成 {@code ApiResponse} 信封，两套契约不能混用。
 */
public final class AuthorityException extends RuntimeException {

    private final int status;
    private final String code;
    private final String auditReason;

    public AuthorityException(int status, String code, String message, String auditReason) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.auditReason = auditReason;
    }

    public static AuthorityException invalidRequest(String detail, String auditReason) {
        return new AuthorityException(400, "INVALID_REQUEST", detail, auditReason);
    }

    public static AuthorityException invalidClient(String auditReason) {
        return new AuthorityException(401, "SYS-AUTH-401", "调用方凭据无效", auditReason);
    }

    /**
     * 默认拒绝：目录未命中、mode 非 workload、票面不一致、operation 未实现都走这里。
     * 对外文案必须恒定——不能把"为什么拒绝"回显给调用方，那是探测面。
     */
    public static AuthorityException denied(String auditReason) {
        return new AuthorityException(403, "SYS-AUTH-403", "当前授权不允许此操作", auditReason);
    }

    /**
     * 已签发但已过期的委派决策。语义上是"过期"而不是"不允许"：调用方据此区分"重新判权"与"永久拒绝"，
     * 因此状态码走 409 而不是复用 403（卡片 EX-42 明确要求可区分）。
     */
    public static AuthorityException expired(String auditReason) {
        return new AuthorityException(409, "AUTHORITY_EXPIRED", "委派授权已过期", auditReason);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String auditReason() {
        return auditReason;
    }
}
