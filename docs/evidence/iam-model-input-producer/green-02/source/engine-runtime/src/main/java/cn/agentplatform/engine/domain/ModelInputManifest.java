package cn.agentplatform.engine.domain;
import cn.agentplatform.common.authority.*;
import java.util.List;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
/** Durable metadata only; this record is never an authorization capability. */
@JsonSerialize(using=ModelInputManifest.Serializer.class)
public record ModelInputManifest(int schemaVersion,String producer,String profile,SubjectKey owner,
 ResourceBinding root,String runId,String acceptedSnapshotSha256,String requestSha256,long modelEntryId,
 JsonNode modelBindingRef,MaterialRef material,MaterialScopeV1 scope,List<MaterialScopeV1> parents,String manifestSha256) {
 public ModelInputManifest {modelBindingRef=modelBindingRef==null?null:modelBindingRef.deepCopy();parents=parents==null?null:List.copyOf(parents);}
 @Override public JsonNode modelBindingRef(){return modelBindingRef==null?null:modelBindingRef.deepCopy();}
 /** Set ordering must survive a JVM restart; canonical JSON preserves array order. */
 public Map<String,Object> unsigned(){
  var m=new LinkedHashMap<String,Object>();m.put("schemaVersion",schemaVersion);m.put("producer",producer);m.put("profile",profile);m.put("owner",owner);m.put("root",root);m.put("runId",runId);m.put("acceptedSnapshotSha256",acceptedSnapshotSha256);m.put("requestSha256",requestSha256);m.put("modelEntryId",modelEntryId);m.put("modelBindingRef",modelBindingRef());m.put("material",material);m.put("scope",scopeFields(scope));m.put("parents",parents.stream().map(ModelInputManifest::scopeFields).toList());return m;
 }
 private static Map<String,Object> scopeFields(MaterialScopeV1 s){
  var m=new LinkedHashMap<String,Object>();m.put("schemaVersion",s.schemaVersion());m.put("material",s.material());m.put("owner",s.owner());m.put("sourceDecisionId",s.sourceDecisionId());m.put("sourceAuthorizationVersion",s.sourceAuthorizationVersion());m.put("initialCeilingSha256",s.initialCeilingSha256());m.put("requiredRoles",new TreeSet<>(s.requiredRoles()));var e=new TreeMap<String,Set<String>>();s.requiredEntitlements().forEach((k,v)->e.put(k,new TreeSet<>(v)));m.put("requiredEntitlements",e);m.put("requiredDataLabels",new TreeSet<>(s.requiredDataLabels()));m.put("parentScopeSha256",s.parentScopeSha256());m.put("scopeSha256",s.scopeSha256());return m;
 }
 public static final class Serializer extends JsonSerializer<ModelInputManifest>{
  @Override public void serialize(ModelInputManifest value,JsonGenerator out,SerializerProvider provider)throws IOException {var m=value.unsigned();m.put("manifestSha256",value.manifestSha256());out.writeObject(m);}
 }
}
