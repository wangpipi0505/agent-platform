import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.output.ValidateResult;
import org.postgresql.ds.PGSimpleDataSource;

/** Evidence main only. Never starts Spring or invokes a migration operation. */
public final class V222HistoryValidate {
    private static final String RESOURCE = "db/migration/V222__run_context_pack.sql";
    private static final String SHA = "419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90";

    private static final class Rejected extends Exception {
        final String code;
        Rejected(String code) { super(code); this.code = code; }
    }

    private static void require(boolean valid, String code) throws Rejected {
        if (!valid) throw new Rejected(code);
    }

    private static String password() throws Exception {
        byte[] input = System.in.readNBytes(32769);
        byte[] decoded = null;
        try {
            require(input.length <= 32768, "INPUT_TOO_LARGE");
            String text = new String(input, StandardCharsets.US_ASCII);
            var match = java.util.regex.Pattern.compile(
                    "\\AV222-HISTORY-VALIDATE/1\\n([A-Za-z0-9+/]+={0,2})\\n\\z").matcher(text);
            require(match.matches(), "INPUT_PROTOCOL_REJECTED");
            decoded = Base64.getDecoder().decode(match.group(1));
            require(decoded.length > 0 && decoded.length <= 16384, "PASSWORD_INPUT_REJECTED");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        } finally {
            Arrays.fill(input, (byte) 0);
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    private static void resource(Path classes) throws Exception {
        var urls = V222HistoryValidate.class.getClassLoader().getResources(RESOURCE);
        require(urls.hasMoreElements(), "RESOURCE_MISSING");
        var url = urls.nextElement();
        require(!urls.hasMoreElements() && url.getProtocol().equals("file"), "RESOURCE_ORIGIN_REJECTED");
        require(Path.of(url.toURI()).toRealPath().equals(classes.resolve(RESOURCE).toRealPath()),
                "RESOURCE_ROOT_REJECTED");
        try (var stream = url.openStream()) {
            byte[] bytes = stream.readNBytes(287);
            require(bytes.length == 286 && HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)).equals(SHA), "RESOURCE_BYTES_REJECTED");
        }
    }

    /** Each connection is checked on acquisition and immediately before closing. */
    private static final class ReadOnlyDataSource implements DataSource, AutoCloseable {
        private final PGSimpleDataSource delegate = new PGSimpleDataSource();
        private final List<Connection> connections = new ArrayList<>();
        int opened, before, after, closed;
        volatile boolean healthy = true;
        private boolean finished;

        ReadOnlyDataSource(String secret, String runId) throws SQLException {
            delegate.setServerNames(new String[] {"127.0.0.1"});
            delegate.setPortNumbers(new int[] {25432});
            delegate.setDatabaseName("agent_platform_engine_test");
            delegate.setCurrentSchema("engine");
            delegate.setUser("agent_v12");
            delegate.setPassword(secret);
            delegate.setOptions("-c default_transaction_read_only=on -c statement_timeout=5000");
            delegate.setReadOnly(true);
            delegate.setReadOnlyMode("always");
            delegate.setConnectTimeout(5);
            delegate.setSocketTimeout(10);
            delegate.setApplicationName("ex27-v222-validate-" + runId);
            delegate.setLogWriter(null);
        }

        private void verify(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (ResultSet row = statement.executeQuery(
                        "select current_database(),current_user,current_schema(),"
                        + "current_setting('transaction_read_only'),"
                        + "current_setting('default_transaction_read_only'),current_setting('statement_timeout')")) {
                    if (!connection.isReadOnly() || !row.next()
                            || !"agent_platform_engine_test".equals(row.getString(1))
                            || !"agent_v12".equals(row.getString(2)) || !"engine".equals(row.getString(3))
                            || !"on".equals(row.getString(4)) || !"on".equals(row.getString(5))
                            || !"5s".equals(row.getString(6)) || row.next()) {
                        throw new SQLException("CONNECTION_READONLY_SCOPE_REJECTED");
                    }
                }
            } catch (SQLException error) {
                healthy = false;
                throw error;
            }
        }

        @Override public synchronized Connection getConnection() throws SQLException {
            if (finished) throw new SQLException("DATASOURCE_ALREADY_FINISHED");
            Connection raw = delegate.getConnection();
            opened++;
            try {
                raw.setReadOnly(true);
                verify(raw);
                before++;
            } catch (SQLException error) {
                healthy = false;
                try { raw.close(); } finally { closed++; }
                throw error;
            }
            AtomicBoolean done = new AtomicBoolean();
            Connection proxy = (Connection) Proxy.newProxyInstance(
                    V222HistoryValidate.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (self, method, args) -> {
                        String name = method.getName();
                        if (name.equals("close")) {
                            if (done.compareAndSet(false, true)) {
                                synchronized (ReadOnlyDataSource.this) {
                                    try { verify(raw); after++; }
                                    finally {
                                        try { raw.close(); }
                                        catch (SQLException error) { healthy = false; throw error; }
                                        finally { closed++; }
                                    }
                                }
                            }
                            return null;
                        }
                        if (name.equals("setReadOnly") && Boolean.FALSE.equals(args[0])) {
                            healthy = false;
                            throw new SQLException("READWRITE_SWITCH_REJECTED");
                        }
                        if (name.equals("unwrap")) {
                            if (args[0] == Connection.class) return self;
                            throw new SQLFeatureNotSupportedException("RAW_CONNECTION_UNWRAP_REJECTED");
                        }
                        if (name.equals("isWrapperFor")) return args[0] == Connection.class;
                        try { return method.invoke(raw, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            connections.add(proxy);
            return proxy;
        }

        @Override public synchronized void close() {
            finished = true;
            for (Connection connection : connections) {
                try { connection.close(); } catch (SQLException error) { healthy = false; }
            }
            delegate.setPassword(null);
        }
        synchronized boolean passed() { return healthy && opened > 0 && opened == before && opened == after && opened == closed; }
        @Override public Connection getConnection(String user, String secret) throws SQLException {
            throw new SQLFeatureNotSupportedException("CREDENTIAL_OVERRIDE_REJECTED");
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException {
            throw new SQLFeatureNotSupportedException("LOG_WRITER_REJECTED");
        }
        @Override public int getLoginTimeout() { return 5; }
        @Override public void setLoginTimeout(int seconds) throws SQLException {
            throw new SQLFeatureNotSupportedException("TIMEOUT_OVERRIDE_REJECTED");
        }
        @Override public Logger getParentLogger() { return Logger.getLogger("ex27.v222.validate"); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLFeatureNotSupportedException("DATASOURCE_UNWRAP_REJECTED");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }

    private static String safeCode(Object value) {
        String text = value == null ? "NONE" : value.toString();
        return text.matches("[A-Z0-9_]{1,80}") ? text : "UNRECOGNIZED_ERROR_CODE";
    }

    public static void main(String[] args) {
        PrintStream output = System.out;
        // Third-party diagnostics and exception strings never enter collector logs.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        ReadOnlyDataSource source = null;
        boolean valid = false, pinned = false, details = true;
        int count = -1, invalid = -1;
        String code = "VALIDATION_NOT_COMPLETED";
        List<String> errorCodes = new ArrayList<>();
        try {
            require(args.length == 2 && args[0].matches("[a-z0-9-]{1,80}"), "ARGUMENTS_REJECTED");
            resource(Path.of(args[1]));
            pinned = true;
            String secret = password();
            source = new ReadOnlyDataSource(secret, args[0]);
            secret = null;
            Flyway flyway = Flyway.configure(V222HistoryValidate.class.getClassLoader())
                    .dataSource(source).schemas("engine").defaultSchema("engine")
                    .locations("classpath:db/migration").createSchemas(false).cleanDisabled(true)
                    .skipDefaultCallbacks(true).callbacks(new Callback[0])
                    .ignoreMigrationPatterns(new String[0]).validateMigrationNaming(true).load();
            ValidateResult result = flyway.validateWithResult();
            valid = result.validationSuccessful;
            count = result.validateCount;
            invalid = result.invalidMigrations == null ? -1 : result.invalidMigrations.size();
            details = result.errorDetails != null;
            if (details) errorCodes.add(safeCode(result.errorDetails.errorCode));
            if (result.invalidMigrations != null) {
                for (var migration : result.invalidMigrations) {
                    if (migration.errorDetails != null) errorCodes.add(safeCode(migration.errorDetails.errorCode));
                }
            }
            code = valid && invalid == 0 && !details ? "VALIDATED" : "FLYWAY_VALIDATION_REJECTED";
        } catch (Rejected error) {
            code = error.code;
        } catch (Throwable error) {
            code = error instanceof SQLException ? "SQL_OR_CONNECTION_FAILURE" : "VALIDATOR_FAILURE";
        } finally {
            if (source != null) source.close();
        }
        boolean guards = source != null && source.passed();
        if (!guards && code.equals("VALIDATED")) code = "CONNECTION_GUARD_FAILED";
        boolean passed = pinned && valid && invalid == 0 && !details && guards && code.equals("VALIDATED");
        String errors = String.join(",", errorCodes.stream().map(s -> "\"" + s + "\"").toList());
        output.println("{\"schema\":\"EX27-V222-history-validator-v1\",\"passed\":" + passed
                + ",\"safeCode\":\"" + code + "\",\"validationSuccessful\":" + valid
                + ",\"validateCount\":" + count + ",\"invalidMigrationCount\":" + invalid
                + ",\"errorDetailsPresent\":" + details + ",\"errorCodes\":[" + errors + "]"
                + ",\"openedConnections\":" + (source == null ? 0 : source.opened)
                + ",\"verifiedBeforeConnections\":" + (source == null ? 0 : source.before)
                + ",\"verifiedAfterConnections\":" + (source == null ? 0 : source.after)
                + ",\"closedConnections\":" + (source == null ? 0 : source.closed)
                + ",\"connectionGuardsPassed\":" + guards + ",\"resourcePinned\":" + pinned
                + ",\"ddlInvoked\":false,\"applicationStarted\":false,\"wholeCardClosed\":false}");
        System.exit(passed ? 0 : 1);
    }
}
