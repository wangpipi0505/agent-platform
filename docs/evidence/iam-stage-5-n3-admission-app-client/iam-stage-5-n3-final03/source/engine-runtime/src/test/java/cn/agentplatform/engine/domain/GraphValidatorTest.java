package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

class GraphValidatorTest {
    @Test
    void sharedAnyRequiresExplicitMode() {
        var graph =
                Json.read(
                        "{\"entry\":\"a\",\"nodes\":[{\"id\":\"a\",\"type\":\"START\"},{\"id\":\"b\",\"type\":\"CODE\"},{\"id\":\"j\",\"type\":\"END\"},{\"id\":\"k\",\"type\":\"CODE\"}],\"edges\":[{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"a\",\"to\":\"j\"},{\"from\":\"b\",\"to\":\"j\"},{\"from\":\"b\",\"to\":\"k\"}],\"joins\":[{\"nodeId\":\"j\",\"semantics\":\"ANY\"}]}");
        assertTrue(
                new GraphValidator()
                        .validate(graph).stream()
                                .anyMatch(e -> e.get("code").equals("ANY_SHARED_UPSTREAM")));
    }

    @Test
    void invalidEntryIsRejected() {
        assertFalse(
                new GraphValidator()
                        .validate(Json.read("{\"entry\":\"missing\",\"nodes\":[],\"edges\":[]}"))
                        .isEmpty());
    }
}
