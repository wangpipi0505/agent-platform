package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.model.domain.*;
import cn.agentplatform.model.infra.FrozenModelBindingStore;
import cn.agentplatform.model.infra.FrozenModelBindingStore.BindingRow;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Explicit, request-local prerequisite. No bean, credential, tokenization or outbound-send path. */
public final class FrozenModelBindingVerifier {
  private static final long ENVELOPE_NANOS = 8_000_000_000L;
  private static final long TRANSACTION_NANOS = 3_000_000_000L;
  private static final ObjectMapper STRICT = new ObjectMapper(
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
              .maxStringLength(262144).maxNumberLength(64).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final FrozenModelBindingStore store;
  private final TransactionTemplate tx;

  public FrozenModelBindingVerifier(DataSource source) {
    store = new FrozenModelBindingStore(Objects.requireNonNull(source));
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setReadOnly(true);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(3);
  }

  public VerifiedFrozenModelBinding verify(InitialModelInputVerifier.VerifiedInitialModelInput input) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("CURRENT_NETWORK_REQUIRES_NO_TRANSACTION");
    long started = System.nanoTime();
    checkTime(started);
    if (input == null) throw missing();
    input.requireVerified();
    // All old-frame facts are captured before the final current refresh retires that mapping.
    byte[] canonical = input.acceptedCanonicalRequestUtf8();
    JsonNode request = input.request();
    EngineModelInputManifest manifest = input.manifest();
    RetainedAuthorization captured = input.authorityForBinding();
    AuthorityContext.requireBound(captured);
    purpose(captured);
    var before = captured.current();
    Actor initial = captured.requireInitialCeiling();
    String origin = captured.requireOriginAuthenticationId();
    String initialSha = MaterialAuthorization.ceilingSha256(initial);
    same(manifest.owner(), new SubjectKey(initial.tenantId(), initial.principalId()));
    same(manifest.root(), before.call().resource());
    same(initialSha, manifest.scope().initialCeilingSha256());
    String requestSha = sha256(canonical);
    same(manifest.requestSha256(), requestSha);
    same(manifest.modelEntryId(), positive(request.get("modelEntryId"), Long.MAX_VALUE));
    fields(request.get("modelBindingRef"), "bindingId", "sha256");
    same(manifest.modelBindingRef().bindingId(), positive(request.path("modelBindingRef").get("bindingId"), Long.MAX_VALUE));
    same(manifest.modelBindingRef().sha256(), text(request.path("modelBindingRef").get("sha256"),64));
    if (!manifest.modelBindingRef().sha256().matches("[0-9a-f]{64}")) throw missing();
    checkTime(started);
    // Start conservatively before acquisition: Spring's holder covers the actual transaction,
    // while this monotonic budget also rejects slow begin/commit/cleanup before final IAM.
    long transactionStarted = System.nanoTime();
    BindingRow row;
    try {
      row = tx.execute(status -> {
        checkTransactionTime(started, transactionStarted);
        AuthorityContext.requireBound(captured);
        captured.requireVerified();
        var found = store.read(initial.tenantId(), manifest.modelBindingRef().bindingId());
        checkTransactionTime(started, transactionStarted);
        captured.requireVerified();
        AuthorityContext.requireBound(captured);
        checkTransactionTime(started, transactionStarted);
        return found;
      });
    } catch (PlatformException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable();
    }
    checkTransactionTime(started, transactionStarted);
    JsonNode metadata = metadata(row, manifest);
    checkTime(started);
    // TransactionTemplate has completed cleanup, including returning its connection, before IAM.
    RetainedAuthorization fresh = AuthorityContext.requireInitialFor(before.actor(), before.call().resource());
    checkTime(started);
    purpose(fresh);
    var current = fresh.current();
    same(before.caller(), current.caller());
    same(before.call(), current.call());
    same(before.operation(), current.operation());
    same(manifest.root(), current.operation().resource());
    same(manifest.owner(), current.operation().subject());
    same(initial, fresh.requireInitialCeiling());
    same(initialSha, MaterialAuthorization.ceilingSha256(fresh.requireInitialCeiling()));
    same(origin, fresh.requireOriginAuthenticationId());
    // Retained proof enforces freshness with its trusted verification clock. MaterialAuthorization
    // then validates the immutable scope at that same signed observation instant; it does not
    // substitute a second local clock for retained proof lifetime checks.
    Clock observation = Clock.fixed(current.checkedAt(), ZoneOffset.UTC);
    var labels = new HashSet<String>();
    addLabels(labels, strings(request.get("requiredLabels"),256,256));
    addLabels(labels, initial.attributes().getOrDefault("dataLabels",Set.of()));
    addLabels(labels, current.actor().attributes().getOrDefault("dataLabels",Set.of()));
    if (manifest.parents().size() != 3) throw missing();
    for (var parent : manifest.parents()) {
      fresh.requireVerified();
      AuthorityContext.requireBound(fresh);
      same(manifest.owner(), parent.owner());
      same(initialSha, parent.initialCeilingSha256());
      MaterialAuthorization.require(parent,current,observation);
      fresh.requireVerified();
      AuthorityContext.requireBound(fresh);
      addLabels(labels,parent.requiredDataLabels());
    }
    fresh.requireVerified();
    AuthorityContext.requireBound(fresh);
    MaterialAuthorization.require(manifest.scope(), current, observation);
    fresh.requireVerified();
    AuthorityContext.requireBound(fresh);
    addLabels(labels,manifest.scope().requiredDataLabels());
    try { ModelPolicy.checkLabels(labels,strings(metadata.path("provider").get("trustLabels"),256,256)); }
    catch (IllegalArgumentException e) { throw new PlatformException(403,"SYS-EGRESS-4031","端点不满足数据所需信任标签"); }
    capabilities(request, metadata);
    checkTime(started);
    fresh.requireVerified();
    AuthorityContext.requireBound(fresh);
    return new VerifiedFrozenModelBinding(requestSha, manifest, metadata, fresh, Set.copyOf(labels));
  }

  private static void purpose(RetainedAuthorization proof) {
    proof.requireVerified();
    var a = proof.current();
    var owner = new SubjectKey(a.actor().tenantId(), a.actor().principalId());
    if (!"engine".equals(a.caller()) || !"model".equals(a.call().target())
        || !"model.chat-stream".equals(a.call().action())
        || !"app-version".equals(a.call().resource().kind())
        || a.operation() == null || !"DELEGATED".equals(a.operation().sourceKind())
        || !"PORTAL".equals(a.operation().originKind())) throw missing();
    same(a.call().resource(), a.operation().resource());
    same(owner, a.operation().subject());
    same(owner, new SubjectKey(proof.requireInitialCeiling().tenantId(),proof.requireInitialCeiling().principalId()));
  }

  private static JsonNode metadata(BindingRow row, EngineModelInputManifest manifest) {
    if (row == null || row.snapshotJson() == null || row.snapshotJson().getBytes(StandardCharsets.UTF_8).length > 262144)
      throw missing();
    JsonNode n;
    try { n = STRICT.readTree(row.snapshotJson()); }
    catch (Exception e) { throw missing(); }
    // Whole strict tree, before projection or array-to-set conversion. This hash has no relation
    // to app candidate/run snapshot/accepted request/manifest/provider-wire digest domains.
    same(row.sha256(), manifest.modelBindingRef().sha256());
    same(row.sha256(), Digest.sha256(Json.canonical(n)));
    same(row.id(),manifest.modelBindingRef().bindingId());
    same(row.modelEntryId(),manifest.modelEntryId());
    fields(n,"id","providerId","modelName","displayName","contextWindow","maxOutputTokens","capabilities","provider","protocol","baseUrl","tokenizerUrl");
    same(row.modelEntryId(),positive(n.get("id"),Long.MAX_VALUE));
    same(row.providerId(),positive(n.get("providerId"),Long.MAX_VALUE));
    text(n.get("modelName"),128); text(n.get("displayName"),128);
    positive(n.get("contextWindow"),Integer.MAX_VALUE); positive(n.get("maxOutputTokens"),Integer.MAX_VALUE);
    strings(n.get("capabilities"),64,64);
    var provider=n.get("provider"); fields(provider,"id","code","name","trustLabels");
    same(row.providerId(),positive(provider.get("id"),Long.MAX_VALUE));
    text(provider.get("code"),64);text(provider.get("name"),128);strings(provider.get("trustLabels"),256,256);
    same("OPENAI_COMPATIBLE",text(n.get("protocol"),32));
    String base=text(n.get("baseUrl"),4096), tokenizer=nullableEmptyText(n.get("tokenizerUrl"),4096);
    try {
      var endpoint=ModelPolicy.endpoint(base);
      if (!tokenizer.isEmpty()) {
        var tokenEndpoint=ModelPolicy.endpoint(tokenizer);
        if (!endpoint.getScheme().equals(tokenEndpoint.getScheme())
            || !endpoint.getAuthority().equalsIgnoreCase(tokenEndpoint.getAuthority())) throw missing();
      }
    } catch (IllegalArgumentException e) { throw missing(); }
    return n;
  }

  private static void capabilities(JsonNode request, JsonNode metadata) {
    var caps=strings(metadata.get("capabilities"),64,64);
    if (!caps.contains("chat") || !request.path("toolDefinitions").isEmpty() && !caps.contains("tools")
        || "json".equals(request.path("params").path("output").path("format").path("type").asText()) && !caps.contains("json_mode"))
      throw incapable();
    var params=request.path("params");
    JsonNode output=params.path("output");
    JsonNode requested=output.has("maxTokens")?output.get("maxTokens"):
        params.has("maxOutputTokens")?params.get("maxOutputTokens"):metadata.get("maxOutputTokens");
    if (requested == null || !requested.isIntegralNumber() || !requested.canConvertToInt()
        || requested.intValue()<1 || requested.intValue()>metadata.path("maxOutputTokens").intValue()) throw incapable();
  }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static void checkTransactionTime(long started, long transactionStarted) {
    checkTime(started);
    if (System.nanoTime() - transactionStarted >= TRANSACTION_NANOS) throw unavailable();
  }

  private static void checkTime(long started) {
    if (Thread.currentThread().isInterrupted() || System.nanoTime()-started>=ENVELOPE_NANOS) throw unavailable();
  }
  private static void addLabels(Set<String> into, Collection<String> labels) {
    for (String value:labels) if(value==null || value.isBlank() || value.length()>256)throw missing();
    into.addAll(labels); if(into.size()>256)throw missing();
  }
  private static void fields(JsonNode n,String... names) {
    if(n==null || !n.isObject() || n.size()!=names.length)throw missing();
    for(String name:names)if(!n.hasNonNull(name))throw missing();
  }
  private static long positive(JsonNode n,long max) {
    if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<1||n.longValue()>max)throw missing();
    return n.longValue();
  }
  private static String text(JsonNode n,int max) {
    String value=nullableEmptyText(n,max); if(value.isBlank())throw missing();return value;
  }
  private static String nullableEmptyText(JsonNode n,int max) {
    if(n==null||!n.isTextual()||n.textValue().length()>max)throw missing();return n.textValue();
  }
  private static Set<String> strings(JsonNode n,int count,int width) {
    if(n==null||!n.isArray()||n.size()>count)throw missing();
    var result=new HashSet<String>();for(var item:n)if(!result.add(text(item,width)))throw missing();return result;
  }
  private static void same(Object expected,Object actual){if(!Objects.equals(expected,actual))throw missing();}
  private static PlatformException missing(){return new PlatformException(403,"MATERIAL_SCOPE_MISSING_OR_CHANGED","材料范围缺失或已改变");}
  private static PlatformException unavailable(){return new PlatformException(503,"SYS-MODEL-503","模型绑定暂不可用");}
  private static PlatformException incapable(){return new PlatformException(409,"SYS-MODEL-4002","模型能力或输出上限不满足请求");}

  /** No public constructor, serialization surface, request body, refresh or durable permission. */
  public static final class VerifiedFrozenModelBinding {
    private final String requestSha;
    private final EngineModelInputManifest manifest;
    private final JsonNode metadata;
    private final RetainedAuthorization mapping;
    private final Set<String> requiredLabels;
    private VerifiedFrozenModelBinding(String requestSha,EngineModelInputManifest manifest,JsonNode metadata,RetainedAuthorization mapping,Set<String> labels) {
      this.requestSha=requestSha;this.manifest=manifest;this.metadata=metadata.deepCopy();this.mapping=mapping;this.requiredLabels=labels;
    }
    public void requireVerified(){mapping.requireVerified();AuthorityContext.requireBound(mapping);}
    public String requestSha256(){requireVerified();return requestSha;}
    public long modelEntryId(){requireVerified();return manifest.modelEntryId();}
    public long bindingId(){requireVerified();return manifest.modelBindingRef().bindingId();}
    public String bindingSha256(){requireVerified();return manifest.modelBindingRef().sha256();}
    public JsonNode frozenMetadata(){requireVerified();return metadata.deepCopy();}
    @Override public String toString(){return "VerifiedFrozenModelBinding[request-bound]";}
  }
}
