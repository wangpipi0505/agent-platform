package cn.agentplatform.engine.application;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.engine.domain.ModelInputManifest;
import javax.sql.DataSource;
import java.time.Clock;
public class ModelInputMaterials {
 public ModelInputMaterials(DataSource ds,HttpCurrentAuthorityClient client,Clock clock){}
 public ModelInputManifest prepareInitial(long runId){return null;}
 public ModelInputManifest manifest(String id,String version,HttpCurrentAuthorityClient.RetainedAuthorization received){return null;}
}
