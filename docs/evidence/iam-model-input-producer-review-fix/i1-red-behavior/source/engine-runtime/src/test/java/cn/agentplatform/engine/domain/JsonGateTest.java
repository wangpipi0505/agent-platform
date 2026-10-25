package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

class JsonGateTest {
    @Test
    void requiredInputCannotDisappear() {
        assertThrows(
                RuntimeFault.class,
                () ->
                        JsonGate.validate(
                                Json.read("{\"type\":\"object\",\"required\":[\"x\"]}"),
                                Json.read("{}"),
                                "input",
                                "PARAM_ERROR"));
    }

    @Test
    void outputTypeCannotBeImplicitlyCoerced() {
        assertThrows(
                RuntimeFault.class,
                () ->
                        JsonGate.validate(
                                Json.read("{\"type\":\"number\"}"),
                                Json.tree("12"),
                                "output",
                                "OUTPUT_INVALID"));
    }

    @Test
    void validArrayRetainsAllElements() {
        JsonGate.validate(
                Json.read("{\"type\":\"array\",\"items\":{\"type\":\"number\"}}"),
                Json.read("[1,2]"),
                "output",
                "OUTPUT_INVALID");
    }
}
