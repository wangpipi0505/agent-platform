package cn.agentplatform.model.infra;

import cn.agentplatform.common.PlatformException;
import java.sql.*;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Ordinary row data only. The application verifier owns authority and the transaction. */
public final class FrozenModelBindingStore {
  private final DataSource dataSource;
  public FrozenModelBindingStore(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }
  public BindingRow read(String tenantId, long bindingId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("FROZEN_BINDING_REQUIRES_READ_ONLY_TRANSACTION");
    long started = System.nanoTime();
    checkTime(started);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (var setup = connection.createStatement()) {
      timeout(setup, started);
      setup.execute("set local statement_timeout='2000ms'");
      timeout(setup, started);
      setup.execute("set local lock_timeout='1000ms'");
      try (var query = connection.prepareStatement(
          "select id,model_entry_id,provider_id,sha256,case when octet_length(snapshot::text)<=262144 then snapshot::text else null end as snapshot_json from model.binding_snapshot where tenant_id=? and id=?")) {
        timeout(query, started); query.setMaxRows(2);
        query.setString(1, tenantId); query.setLong(2, bindingId);
        try (var rows = query.executeQuery()) {
          boolean present = rows.next();
          checkTime(started);
          if (!present) throw missing();
          var row = new BindingRow(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getString(4), rows.getString(5));
          checkTime(started);
          boolean extra = rows.next();
          checkTime(started);
          if (extra || row.snapshotJson() == null) throw missing();
          return row;
        }
      }
    } catch (SQLException e) {
      throw new PlatformException(503,"SYS-MODEL-503","模型绑定暂不可用");
    } finally { DataSourceUtils.releaseConnection(connection,dataSource); }
  }
  private void timeout(Statement statement, long started) throws SQLException {
    checkTime(started);
    // Spring reads/validates the holder deadline per execution. Its whole-second TTL rounds up,
    // so retain the independent 2s cap and reject elapsed work again after reads/callback/cleanup.
    DataSourceUtils.applyTimeout(statement, dataSource, 2);
    statement.setQueryTimeout(Math.min(2, statement.getQueryTimeout()));
    checkTime(started);
  }
  private void checkTime(long started) {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= 3_000_000_000L)
      throw new PlatformException(503,"SYS-MODEL-503","模型绑定暂不可用");
    var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
    if (holder != null && holder.hasTimeout()) holder.getTimeToLiveInMillis();
  }
  private static PlatformException missing() { return new PlatformException(403,"MATERIAL_SCOPE_MISSING_OR_CHANGED","材料范围缺失或已改变"); }
  public record BindingRow(long id,long modelEntryId,long providerId,String sha256,String snapshotJson) {}
}
