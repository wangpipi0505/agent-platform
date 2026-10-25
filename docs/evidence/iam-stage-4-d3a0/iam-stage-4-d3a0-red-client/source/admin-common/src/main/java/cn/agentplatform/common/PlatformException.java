package cn.agentplatform.common;

/** 可向调用方展示的业务错误；外部异常必须先归一，禁止直接回显SQL/密钥。 */
public class PlatformException extends RuntimeException {
    private final int status;
    private final String code;

    public PlatformException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
