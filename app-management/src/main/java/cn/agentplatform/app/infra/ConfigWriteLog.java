package cn.agentplatform.app.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 配置类写入的 requestId 幂等登记（int-01/02 引入的 app.config_write_request）。
 *
 * <p>约定：同一个 requestId 只产生一次写入；同号不同载荷属于调用方错误（409），同号同载荷回放上次响应
 * 并标记 {@code replayed=true}。指针始终与业务写入同事务，因此登记表不会出现"记了但没写"的行。
 */
@Component
public class ConfigWriteLog {

  private final JdbcTemplate db;

  public ConfigWriteLog(JdbcTemplate db) {
    this.db = db;
  }

  /** 命中已登记请求时返回上次响应（调用方补 replayed=true）；同号异载荷或未命中返回 null。 */
  public Map<String, Object> replay(String tenantId, String operation, String target, String requestId,
      String requestSha) {
    var rows = db.queryForList("""
        select request_sha256, response::text from app.config_write_request
         where tenant_id = ? and request_id = ? and operation = ? and target = ?
        """, tenantId, requestId, operation, target);
    if (rows.isEmpty()) {
      return null;
    }
    String stored = String.valueOf(rows.get(0).get("request_sha256"));
    if (!stored.equals(requestSha)) {
      throw new PlatformException(409, "CONFIG_REV_CONFLICT",
          "同一 requestId 已用于不同的写入内容: " + requestId);
    }
    Object response = rows.get(0).get("response");
    return Json.read(response == null ? "{}" : response.toString(), Map.class);
  }

  /** 记录本次写入；同 requestId 并发重复时由主键吞掉，不会覆盖既有响应。 */
  public void remember(String tenantId, String operation, String target, String requestId,
      String requestSha, Map<String, Object> response) {
    db.update("""
        insert into app.config_write_request(tenant_id, request_id, operation, target, request_sha256, response)
        values (?, ?, ?, ?, ?, ?::jsonb) on conflict(tenant_id, request_id) do nothing
        """, tenantId, requestId, operation, target, requestSha, Json.write(response));
  }
}
