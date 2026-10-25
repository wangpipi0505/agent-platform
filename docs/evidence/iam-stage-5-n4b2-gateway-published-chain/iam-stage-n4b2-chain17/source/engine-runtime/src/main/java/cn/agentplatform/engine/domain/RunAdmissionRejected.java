package cn.agentplatform.engine.domain;

import cn.agentplatform.common.PlatformException;

public final class RunAdmissionRejected extends PlatformException {
    public RunAdmissionRejected(boolean draining) {
        super(
                draining ? 503 : 429,
                draining ? "ENGINE_DRAINING" : "ENGINE_BUSY",
                draining ? "运行服务正在停止受理，请稍后重试" : "交互运行已满，请稍后重试");
    }

    public int retryAfterSeconds() {
        return 1;
    }
}
