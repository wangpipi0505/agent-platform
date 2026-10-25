package cn.agentplatform.engine.application;

import static cn.agentplatform.engine.infra.ModelInputMaterialStore.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.domain.ModelInputManifest;
import cn.agentplatform.engine.infra.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Engine-owned preparation only. Public CHAT/model-bearing admission remains closed. */
@Component
public class ModelInputMaterials {
  private final DataSource ds;
  private final HttpCurrentAuthorityClient client;
  private final Clock clock;
  private final ModelInputMaterialStore store;

  @Autowired
  public ModelInputMaterials(DataSource ds, ObjectProvider<HttpCurrentAuthorityClient> clients) {
    this(ds, clients.getIfAvailable(), Clock.systemUTC());
  }

  public ModelInputMaterials(DataSource ds, HttpCurrentAuthorityClient client, Clock clock) {
    this.ds = Objects.requireNonNull(ds);
    this.client = client;
    this.clock = Objects.requireNonNull(clock);
    store = new ModelInputMaterialStore(ds, clock);
  }

  public ModelInputManifest prepareInitial(long runId) {
    if (client == null) throw missing();
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("CURRENT_NETWORK_REQUIRES_NO_TRANSACTION");
    // Existing retained continuation performs online work and recordCheck CAS before the write tx.
    store.preflightInitialMetadata(runId);
    RetainedAuthorization proof;
    try {
      proof =
          new RunAuthority(new RunAuthorityStore(ds, clock), client, clock, Duration.ofSeconds(1))
              .currentRetained(runId);
    } catch (PlatformException e) {
      if (e.code().equals("RUN_AUTHORITY_DENIED")) throw missing();
      throw e;
    }
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    var result =
        tx.execute(
            status -> {
              try {
                proof.requireVerified();
                var initial = store.lockInitial(runId, proof);
                var request = assemble(initial, proof);
                var m = store.insertInitial(initial, request, proof);
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void beforeCommit(boolean readOnly) {
                        try {
                          proof.requireVerified();
                        } catch (RuntimeException | Error e) {
                          status.setRollbackOnly();
                          throw e;
                        }
                      }
                    });
                proof.requireVerified();
                return m;
              } catch (RuntimeException | Error e) {
                status.setRollbackOnly();
                throw e;
              }
            });
    // Time/IAM and PostgreSQL commit are not atomic. A late failure can leave an immutable fact,
    // but never returns permission to send, and a future GET needs its own current authorization.
    proof.requireVerified();
    return result;
  }

  public ModelInputManifest manifest(String id, String version, RetainedAuthorization received) {
    if (client == null) throw missing();
    return store.readManifest(id, version, received);
  }

  /** Specific deterministic assembly. Public data records cannot confer write authority. */
  public static JsonNode assemble(
      ModelInputMaterialStore.InitialSource source, RetainedAuthorization proof) {
    var snapshot = (JsonNode) source.snapshot().payload();
    var inputs = (JsonNode) source.inputs().payload();
    var config = snapshot.path("config");
    if (!"CHAT".equals(snapshot.path("type").asText()) || !config.isObject() || !inputs.isObject())
      throw missing();
    for (String key :
        List.of(
            "materialRefs", "historyRefs", "history", "sessionId", "chatTurn", "sessionHistory"))
      if (inputs.has(key) || snapshot.hasNonNull(key)) throw missing();
    var allowed =
        Set.of(
            "modelEntryId",
            "modelBinding",
            "prompt",
            "systemPrompt",
            "params",
            "sampling",
            "output",
            "context",
            "timeout",
            "budgets",
            "requiredLabels",
            "maxOutputTokens");
    config
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k)) throw missing();
            });
    for (String k : List.of("prompt", "systemPrompt"))
      if (config.has(k) && !config.path(k).isTextual()) throw missing();
    for (String k : List.of("params", "sampling", "output", "context", "timeout", "budgets"))
      if (config.has(k) && !config.path(k).isObject()) throw missing();
    rejectExternal(config);
    if (config.has("maxOutputTokens")) intLimit(config.path("maxOutputTokens"));
    if (config.path("output").has("maxTokens")) intLimit(config.path("output").path("maxTokens"));
    for (String k : List.of("temperature", "topP"))
      if (config.path("sampling").has(k) && !config.path("sampling").path(k).isNumber())
        throw missing();
    if (config.has("modelBinding")
        && (!config.path("modelBinding").isObject()
            || config.path("modelBinding").size() != 1
            || !config.path("modelBinding").has("modelEntryId"))) throw missing();
    long entry =
        positive(
            config.has("modelEntryId")
                ? config.path("modelEntryId")
                : config.path("modelBinding").path("modelEntryId"));
    if (config.has("modelBinding")
        && entry != positive(config.path("modelBinding").path("modelEntryId"))) throw missing();
    var params = config.path("params");
    params
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!Set.of(
                      "maxOutputTokens",
                      "temperature",
                      "topP",
                      "topK",
                      "seed",
                      "stop",
                      "frequencyPenalty",
                      "presencePenalty")
                  .contains(k)) throw missing();
            });
    if (!snapshot.path("dependencyVersions").path("modelRefs").isArray()) throw missing();
    for (var item : snapshot.path("dependencyVersions").path("modelRefs")) {
      if (!item.isObject()) throw missing();
      positive(item.path("modelEntryId"));
    }
    JsonNode binding;
    try {
      binding = FrozenModels.reference(snapshot, entry);
    } catch (PlatformException e) {
      throw missing();
    }
    if (!binding.isObject() || binding.size() != 2) throw missing();
    var labels = new TreeSet<>(requiredLabels(source.binding(), proof));
    if (config.has("requiredLabels")) {
      var configured = config.path("requiredLabels");
      if (!configured.isArray() || configured.size() > 256) throw missing();
      for (var n : configured) {
        if (!n.isTextual() || n.textValue().isBlank() || n.textValue().length() > 256)
          throw missing();
        labels.add(n.textValue());
      }
    }
    if (labels.size() > 256) throw missing();
    var request = new ContextAssembler().assemble(config, inputs, List.of(), List.of(), List.of());
    request.put("appCode", snapshot.path("appCode").textValue());
    request.put("runId", source.binding().publicId());
    request.put("callKind", "FORMAL");
    request.put("modelBindingRef", binding);
    request.put("invocationRef", "material:" + source.materialId() + ":MAIN");
    request.put("requiredLabels", labels);
    // The assembler's account/trim names are diagnostic legacy names, not a tokenizer or trim call.
    var result = Json.tree(request);
    bounded(result, REQUEST_BYTES);
    return result;
  }

  private static void intLimit(JsonNode value) {
    if (positive(value) > Integer.MAX_VALUE) throw missing();
  }

  private static void rejectExternal(JsonNode value) {
    if (value.isObject()) {
      var fields = value.fields();
      while (fields.hasNext()) {
        var f = fields.next();
        if (Set.of(
                "tools",
                "toolDefinitions",
                "dataSources",
                "history",
                "sessionHistory",
                "historyRefs",
                "materialRefs",
                "frozenPrompts",
                "modelOverride",
                "overrides",
                "summary",
                "summarizer",
                "retry",
                "compressedMaterials")
            .contains(f.getKey())) throw missing();
        rejectExternal(f.getValue());
      }
    } else if (value.isArray()) for (var child : value) rejectExternal(child);
  }
}
