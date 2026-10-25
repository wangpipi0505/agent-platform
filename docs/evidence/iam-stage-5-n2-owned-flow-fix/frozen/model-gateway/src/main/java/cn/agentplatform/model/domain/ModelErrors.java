package cn.agentplatform.model.domain;

import cn.agentplatform.common.PlatformException;

/** 上游错误正文可能携带地址/凭据；只保留归一错误码与固定安全提示。 */
public final class ModelErrors {
    private ModelErrors() {}

    public static PlatformException status(int status) {
        if (status == 401 || status == 403)
            return new PlatformException(401, "SYS-MODEL-401", "模型凭据无效，请管理员检查凭据引用");
        if (status == 429) return new PlatformException(429, "SYS-MODEL-429", "模型供应商限流，请稍后重试");
        if (status == 413) return new PlatformException(413, "SYS-MODEL-413", "模型上下文超限，请缩减输入");
        if (status == 408 || status == 504)
            return new PlatformException(504, "SYS-MODEL-504", "模型供应商响应超时");
        return new PlatformException(503, "SYS-MODEL-503", "模型供应商暂时不可用");
    }

    public static PlatformException normalize(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PlatformException platform) return platform;
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) return status(504);
        }
        return status(503);
    }
}
