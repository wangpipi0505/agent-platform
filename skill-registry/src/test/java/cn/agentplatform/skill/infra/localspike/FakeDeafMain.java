package cn.agentplatform.skill.infra.localspike;

/**
 * IN-09 spike 测试用假进程：活着但从不监听任何端口。
 * 用于"启动超时"失败路径——模拟算法进程卡死（JVM 起来了但健康端点永远不通）。
 * 由测试 JVM 自带的 java 以 -cp 方式拉起，随后主线程永久休眠。
 */
public final class FakeDeafMain {
    private FakeDeafMain() {}

    public static void main(String[] args) throws Exception {
        System.out.println("FAKE_DEAF_MAIN_STARTED (永不监听端口)");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
