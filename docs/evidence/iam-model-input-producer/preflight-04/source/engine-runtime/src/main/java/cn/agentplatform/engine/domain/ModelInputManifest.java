package cn.agentplatform.engine.domain;
import cn.agentplatform.common.authority.*;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
/** Durable metadata only; this record is never an authorization capability. */
public record ModelInputManifest(int schemaVersion,String producer,String profile,SubjectKey owner,
 ResourceBinding root,String runId,String acceptedSnapshotSha256,String requestSha256,long modelEntryId,
 JsonNode modelBindingRef,MaterialRef material,MaterialScopeV1 scope,List<MaterialScopeV1> parents,String manifestSha256) {
 public ModelInputManifest {modelBindingRef=modelBindingRef==null?null:modelBindingRef.deepCopy();parents=parents==null?null:List.copyOf(parents);}
 @Override public JsonNode modelBindingRef(){return modelBindingRef==null?null:modelBindingRef.deepCopy();}
}
