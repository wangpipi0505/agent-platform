package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.model.infra.ModelStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Own mutable setup, followed by a genuine new freeze; inherited setup binding is not selected. */
final class FrozenInitialInputMeterFixture implements AutoCloseable {
  final FrozenModelBindingConsumerFixture setup = new FrozenModelBindingConsumerFixture();
  final JsonNode selected;

  FrozenInitialInputMeterFixture() throws Exception { this("gpt-4o-mini", false); }
  FrozenInitialInputMeterFixture(String model, boolean remote) throws Exception {
    var store = new ModelStore(setup.db);
    var actor = new Actor("tenant-a", "alice", Set.of("ADMIN"), Map.of());
    store.updateEntry(setup.entry, Json.tree(Map.of("modelName", model, "tokenizerUrl",
        remote ? setup.consumer.origin() + "/tokenizer" : "")), actor);
    selected = Json.tree(store.freeze(setup.entry, actor));
    var request = (ObjectNode) setup.consumer.request;
    request.set("modelBindingRef", Json.tree(Map.of("bindingId", selected.path("bindingId").longValue(),
        "sha256", selected.path("sha256").asText())));
    rebuild();
  }
  void rebuild() {
    setup.consumer.rebuildManifest();
    setup.consumer.manifest.put("modelEntryId", setup.entry);
    setup.consumer.refreshResponse();
    setup.ds.reset();
  }
  <T> T accepted(java.util.function.BiFunction<FrozenModelBindingVerifier.VerifiedFrozenModelBinding, byte[], T> action) throws Exception {
    return setup.accepted(input -> {
      byte[] bytes = input.acceptedCanonicalRequestUtf8();
      var proof = new FrozenModelBindingVerifier(setup.ds).verify(input);
      var identity = Map.of("requestSha256", proof.requestSha256(), "modelEntryId", proof.modelEntryId(),
          "bindingId", proof.bindingId(), "bindingSha256", proof.bindingSha256(), "selectedBindingId", selected.path("bindingId").longValue());
      try { return action.apply(proof, bytes); }
      finally {
        try { Files.writeString(Path.of(System.getProperty("binding.fixture")).resolve("local-meter-proof-observations.jsonl"),
            Json.write(identity) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
      }
    });
  }
  @Override public void close() { setup.close(); }
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(System.getProperty("binding.fixture"));
    try (var owned = new FrozenInitialInputMeterFixture()) {
      Files.writeString(dir.resolve("binding.json"), Json.write(owned.selected));
      Files.writeString(dir.resolve("model-seed-ready.json"), Json.write(Map.of("pid", ProcessHandle.current().pid(),
          "providerOrigin", owned.setup.consumer.origin().toString(), "bindingId", owned.selected.path("bindingId").longValue())));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(12);
      while (!Files.exists(dir.resolve("model-stop")) && System.nanoTime() < deadline) Thread.sleep(100);
      if (!Files.exists(dir.resolve("model-stop"))) throw new AssertionError("MODEL_STOP_DEADLINE");
      Files.writeString(dir.resolve("model-seed-closed.json"), Json.write(Map.of("pid", ProcessHandle.current().pid(),
          "providerHttp", owned.setup.providerHttp.get(), "tokenizerHttp", owned.setup.tokenizerHttp.get(),
          "borrowed", owned.setup.ds.borrowed.get(), "selectedBindingId", owned.selected.path("bindingId").longValue())));
    }
  }
}
