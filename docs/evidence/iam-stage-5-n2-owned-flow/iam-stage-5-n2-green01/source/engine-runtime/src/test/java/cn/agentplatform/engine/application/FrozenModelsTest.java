package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FrozenModelsTest {
    @Test
    void selectsEffectiveEntryAndPreservesAuthoritativeReference() {
        var entry = Json.read("{\"modelEntryId\":7,\"modelName\":\"frozen\"}");
        var sha = Digest.sha256(Json.canonical(entry));
        var snapshot =
                Json.tree(
                        Map.of(
                                "dependencyVersions",
                                Map.of(
                                        "modelRefs",
                                        List.of(
                                                Map.of(
                                                        "modelEntryId",
                                                        7,
                                                        "entry",
                                                        entry,
                                                        "modelBindingRef",
                                                        Map.of("bindingId", 33, "sha256", sha))))));
        assertEquals(33, FrozenModels.reference(snapshot, 7).path("bindingId").asLong());
    }

    @Test
    void forgedOrAmbiguousSnapshotReferenceIsRejected() {
        var entry = Json.read("{\"modelEntryId\":7}");
        var ref =
                Map.of(
                        "modelEntryId",
                        7,
                        "entry",
                        entry,
                        "modelBindingRef",
                        Map.of("bindingId", 33, "sha256", "0".repeat(64)));
        assertThrows(
                PlatformException.class,
                () ->
                        FrozenModels.reference(
                                Json.tree(
                                        Map.of(
                                                "dependencyVersions",
                                                Map.of("modelRefs", List.of(ref)))),
                                7));
        assertThrows(PlatformException.class, () -> FrozenModels.reference(Json.read("{}"), 7));
    }
}
