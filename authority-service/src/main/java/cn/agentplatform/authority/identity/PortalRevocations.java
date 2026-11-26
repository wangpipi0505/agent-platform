package cn.agentplatform.authority.identity;

import cn.agentplatform.authority.AuthorityException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Shared revocation state across issuer instances. Only a non-replayable login fingerprint is stored. */
@Component
public final class PortalRevocations implements AutoCloseable {
    private final HikariDataSource pool;

    public PortalRevocations(Environment environment, IdentityProperties identity) {
        String url = environment.getProperty("AGENT_AUTHORITY_REVOCATION_DB", "");
        if (url.isBlank()) {
            if (identity.enabled() && !environment.acceptsProfiles(Profiles.of("dev", "test")))
                throw new IllegalStateException("AGENT_AUTHORITY_REVOCATION_DB_REQUIRED");
            pool = null;
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(environment.getProperty("AGENT_DB_USER"));
        config.setPassword(environment.getProperty("AGENT_DB_PASSWORD"));
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(2500);
        pool = new HikariDataSource(config);
        try {
            Flyway.configure().dataSource(pool).schemas("iam_revocation").locations("classpath:authority-db")
                    .cleanDisabled(true).load().migrate();
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
    }

    public void requireActive(String origin) {
        if (pool == null || origin == null) return;
        try (var connection = pool.getConnection();
             var read = connection.prepareStatement("select 1 from iam_revocation.portal_session where origin_id=?")) {
            read.setString(1, origin);
            try (var rows = read.executeQuery()) {
                if (rows.next()) throw new AuthorityException(401, "SYS-AUTH-401", "门户票据已撤销", "portal_session_revoked");
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    public void revoke(String origin, Instant expiry) {
        if (pool == null) throw unavailable();
        try (var connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try (var purge = connection.prepareStatement("delete from iam_revocation.portal_session where expires_at < now() - interval '1 day'");
                 var insert = connection.prepareStatement("insert into iam_revocation.portal_session(origin_id,expires_at)"
                         + " values(?,?) on conflict (origin_id) do nothing")) {
                purge.executeUpdate();
                insert.setString(1, origin);
                insert.setTimestamp(2, Timestamp.from(expiry));
                insert.executeUpdate();
                connection.commit();
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private static AuthorityException unavailable() {
        return new AuthorityException(503, "IAM_AUTHORITY_UNAVAILABLE", "门户撤销状态暂不可用", "revocation_store_unavailable");
    }

    @Override public void close() {
        if (pool != null) pool.close();
    }
}
