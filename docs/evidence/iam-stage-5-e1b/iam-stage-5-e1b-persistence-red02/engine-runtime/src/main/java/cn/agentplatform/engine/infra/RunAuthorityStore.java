package cn.agentplatform.engine.infra;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.Set;
public final class RunAuthorityStore {
 public RunAuthorityStore(DataSource source,Clock clock){}
 public long createPublished(RetainedAuthorization admission,JsonNode snapshot,JsonNode inputs,JsonNode refs,JsonNode budget,String mode,String requestId,Set<String> labels){throw new UnsupportedOperationException("E1B_NOT_IMPLEMENTED");}
}
