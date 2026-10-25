package cn.agentplatform.model.domain;

import cn.agentplatform.common.authority.*;
import java.util.List;

/** Untrusted wire metadata. Construction does not confer authority. */
public record EngineModelInputManifest(
    int schemaVersion,
    String producer,
    String profile,
    SubjectKey owner,
    ResourceBinding root,
    String runId,
    String acceptedSnapshotSha256,
    String requestSha256,
    long modelEntryId,
    ModelBindingRef modelBindingRef,
    MaterialRef material,
    MaterialScopeV1 scope,
    List<MaterialScopeV1> parents,
    String manifestSha256) {
  public EngineModelInputManifest {
    parents = parents == null ? null : List.copyOf(parents);
  }

  public record ModelBindingRef(long bindingId, String sha256) {}
}
