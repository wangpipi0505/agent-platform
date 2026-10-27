package cn.agentplatform.skill.infra.localspike;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * spike 端口工具：空闲端口选择与占用预检。
 *
 * <p>设计输入（给 AP-S05）：托管器启动子进程前必须做端口冲突预检，
 * 否则算法进程会以"端口被占"静默失败退出，平台只能靠就绪超时兜底，定位成本高。
 */
public final class LocalSpikePorts {
    private LocalSpikePorts() {}

    /** 探测端口在本机是否可绑定。显式关掉 reuseAddress，避免误报已占用端口为空闲。 */
    public static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在 [from, to] 区间找第一个空闲端口；全满抛 IllegalStateException。 */
    public static int findFree(int from, int to) {
        for (int port = from; port <= to; port++) {
            if (isPortFree(port)) {
                return port;
            }
        }
        throw new IllegalStateException("区间 [" + from + "," + to + "] 内无空闲端口");
    }
}
