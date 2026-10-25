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
    return prepare(runId, false);
  }

  /** Genuine CHAT lifecycle only; the private TASK profile keeps its original entry point. */
  public ModelInputManifest prepareFirstChat(long runId) {
    return prepare(runId, true);
  }

  private ModelInputManifest prepare(long runId, boolean firstChat) {
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
                var initial = firstChat ? store.lockFirstChat(runId, proof) : store.lockInitial(runId, proof);
                var request = assemble(initial, proof);
                var m = firstChat ? store.insertFirstChat(initial, request, proof) : store.insertInitial(initial, request, proof);
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

  public ModelInputManifest preparedInitial(long run, RetainedAuthorization current) {
    if (client == null) throw missing();
    return store.initialManifest(run, current);
  }

  public byte[] initialRequestUtf8(long run, ModelInputManifest manifest, RetainedAuthorization current) {
    if (client == null) throw missing();
    var bytes = store.initialRequestUtf8(run, manifest, current);
    initialOutputPolicy(Json.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).path("params").path("output"));
    return bytes;
  }

  /** Declaration admission is independent of validation of an already paid response. */
  public static cn.agentplatform.engine.domain.OutputPolicy initialOutputPolicy(JsonNode output) {
    var policy = new cn.agentplatform.engine.domain.OutputPolicy(output);
    // The legacy policy defaults maxRetries to two; only an explicitly enabled retry is unsupported here.
    if (policy.onInvalid().equals("RETRY") || (output.has("maxRetries") && policy.maxRetries() != 0)) throw missing();
    var declaration = output.path("format").path("schema");
    if (!declaration.isMissingNode()) validateInitialSchema(declaration);
    return policy;
  }

  private static void validateInitialSchema(JsonNode declaration) {
    try (var ignored = cn.agentplatform.common.schema.SchemaDeadline.until(System.nanoTime() + 2_000_000_000L)) {
      cn.agentplatform.common.schema.SchemaWorkBudget.inspect(declaration);
      String root = "https://json-schema.org/draft/2020-12/";
      var resources = new java.util.HashMap<String, byte[]>();
      var expressions = new java.util.HashSet<String>();
      long units = 0;
      for (String name : java.util.List.of("schema", "meta/core", "meta/applicator", "meta/unevaluated",
          "meta/validation", "meta/meta-data", "meta/format-annotation", "meta/content")) {
        String path = "draft/2020-12/" + name;
        byte[] bytes;
        try (var stream = com.networknt.schema.JsonSchemaFactory.class.getResourceAsStream("/" + path)) {
          if (stream == null) throw missing();
          bytes = stream.readAllBytes();
        }
        resources.put(root + name, bytes);
        resources.put("classpath:" + path, bytes);
        resources.put("classpath:/" + path, bytes);
        var pending = new java.util.ArrayDeque<JsonNode>();
        pending.push(Json.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        while (!pending.isEmpty()) {
          cn.agentplatform.common.schema.SchemaDeadline.check();
          var value = pending.pop(); units++;
          if (value.isObject() && value.path("pattern").isTextual()) expressions.add(value.path("pattern").textValue());
          if (value.isContainerNode()) value.forEach(pending::push);
        }
      }
      var regex = new cn.agentplatform.common.schema.BoundedRegexFactory(expressions);
      // The declaration is the meta-schema's instance. Admit all comparison categories,
      // including quadratic uniqueItems, using the complete fixed vocabulary size; the
      // user's own schema flags would undercount this work. This is deliberately conservative.
      new cn.agentplatform.common.schema.SchemaWorkBudget.Plan(java.util.List.of(), java.util.Set.of(),
          units, true, true, true, true).admitInstance(declaration, regex.programUnits());
      var config = new com.networknt.schema.SchemaValidatorsConfig();
      config.setFailFast(true);
      config.setFormatAssertionsEnabled(false);
      config.setRegularExpressionFactory(regex);
      config.setPreloadJsonSchemaRefMaxNestingDepth(64);
      var factory = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012,
          builder -> builder.enableSchemaCache(false).schemaLoaders(loaders -> loaders.add(iri -> {
            byte[] bytes = resources.get(iri.toString());
            if (bytes == null) throw missing();
            return () -> new java.io.ByteArrayInputStream(bytes);
          })));
      var meta = factory.getSchema(com.networknt.schema.SchemaLocation.of(root + "schema"), config);
      meta.initializeValidators();
      if (!meta.validate(declaration).isEmpty()) throw missing();
      cn.agentplatform.common.schema.SchemaDeadline.check();
    } catch (Exception invalid) {
      throw new cn.agentplatform.engine.domain.RuntimeFault("OUTPUT_POLICY_INVALID", "output", "Schema声明无效或超过校验预算");
    }
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
            "materialRefs", "historyRefs", "history", "sessionId", "chatSession", "chatTurn", "sessionHistory"))
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
    initialOutputPolicy(config.path("output"));
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
    // Parameters are immutable request facts: validate shape before assembly or INSERT.
    // No provider-specific numerical ranges are inferred by this initial-input profile.
    var parameterFields = params.fields();
    while (parameterFields.hasNext()) {
      var field = parameterFields.next();
      var value = field.getValue();
      switch (field.getKey()) {
        case "maxOutputTokens" -> intLimit(value);
        case "temperature", "topP", "frequencyPenalty", "presencePenalty" -> {
          if (!value.isNumber()) throw missing();
        }
        case "topK", "seed" -> {
          if (!value.isIntegralNumber()) throw missing();
        }
        case "stop" -> {
          if (!value.isTextual() && !value.isArray()) throw missing();
          if (value.isArray()) for (var item : value) if (!item.isTextual()) throw missing();
        }
        default -> throw missing();
      }
    }
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
    guardPromptExpansion(config, inputs);
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

  /**
   * Finite-profile preflight for Values' exact whole-value/ordered replace semantics. Each
   * replacement is measured as a JSON string before String.replace can allocate it. This is a
   * per-prompt 2 MiB allocation ceiling under the PG locks, not a whole-JVM budget; the complete
   * canonical request, sources and depth still have their independent bounds.
   */
  private static void guardPromptExpansion(JsonNode config, JsonNode inputs) {
    String text = config.path("systemPrompt").asText(config.path("prompt").asText("你是一个助手。"));
    var scope = Json.tree(Map.of("input", inputs));
    var matches = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(text);
    if (matches.matches()) {
      var budget = new PromptBudget();
      String value = cn.agentplatform.engine.domain.Values.path(scope, matches.group(1)).asText();
      budget.add(value, 0, value.length());
      budget.finish();
      return;
    }
    while (matches.find()) {
      String token = matches.group();
      int first = text.indexOf(token);
      if (first < 0) continue;
      String replacement =
          cn.agentplatform.engine.domain.Values.path(scope, matches.group(1)).asText("null");
      var budget = new PromptBudget();
      int from = 0;
      for (int at = first; at >= 0; at = text.indexOf(token, from)) {
        budget.add(text, from, at);
        budget.add(replacement, 0, replacement.length());
        from = at + token.length();
      }
      budget.add(text, from, text.length());
      budget.finish();
      text = text.replace(token, replacement);
    }
  }

  /** Counts escaped UTF-8 without materializing the hypothetical expanded string. */
  private static final class PromptBudget {
    private long bytes = 2; // JSON's enclosing quotes.
    private boolean highSurrogate;

    void add(String text, int start, int end) {
      for (int i = start; i < end; i++) {
        char c = text.charAt(i);
        if (highSurrogate) {
          highSurrogate = false;
          if (Character.isLowSurrogate(c)) {
            bytes += 4;
            check();
            continue;
          }
          bytes++; // String.getBytes(UTF_8) replaces an unpaired surrogate with '?'.
        }
        if (Character.isHighSurrogate(c)) highSurrogate = true;
        else if (Character.isLowSurrogate(c)) bytes++;
        else if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r'
            || c == '\t') bytes += 2;
        else if (c < 32) bytes += 6;
        else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
        check();
      }
    }

    void finish() {
      if (highSurrogate) bytes++;
      check();
    }

    private void check() {
      if (bytes > REQUEST_BYTES) throw missing();
    }
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
