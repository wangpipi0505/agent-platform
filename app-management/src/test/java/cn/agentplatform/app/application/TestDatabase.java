package cn.agentplatform.app.application;

/**
 * DB 集成测试的连接坐标。
 *
 * <p>默认值与仓库既有测试完全一致（{@code 127.0.0.1:25432} / {@code agent_v12} / {@code agent_platform_v12}），
 * 因此在既有开发机上行为不变；被显式覆盖时才切到别的实例：
 *
 * <pre>
 * AGENT_DB_URL     完整 JDBC URL（含库名）
 * AGENT_DB_USER    账号
 * AGENT_DB_PASSWORD 口令（必填，缺失时整类跳过，沿用既有 @EnabledIfEnvironmentVariable）
 * </pre>
 *
 * <p>只做坐标解析，不建库、不建账号、不改 schema 归属；测试仍要求一个已独立准备好的 PostgreSQL。
 */
final class TestDatabase {

    private TestDatabase() {}

    /** 默认库名保持 {@code agent_platform_v12}，仅在未给 URL 时使用。 */
    static String url() {
        String explicit = System.getenv("AGENT_DB_URL");
        if (explicit != null && !explicit.isBlank()) return explicit;
        return "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12";
    }

    static String user() {
        String explicit = System.getenv("AGENT_DB_USER");
        if (explicit != null && !explicit.isBlank()) return explicit;
        return "agent_v12";
    }

    /** 当前 URL 指向的库名；仅供夹具断言“连到了约定的库”，不用于拼库名或建库。 */
    static String database() {
        String url = url();
        int slash = url.lastIndexOf('/');
        int query = url.indexOf('?', slash);
        String name = query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
        return name.isBlank() ? null : name;
    }

    static String password() {
        return System.getenv("AGENT_DB_PASSWORD");
    }
}
