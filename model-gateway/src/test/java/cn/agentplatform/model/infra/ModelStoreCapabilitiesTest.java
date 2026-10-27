package cn.agentplatform.model.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

class ModelStoreCapabilitiesTest {
    @Test
    void canonicalizesHistoricalUppercaseAndToolAliases() {
        assertThat(Json.write(ModelStore.capabilities(Json.read("[\"CHAT\",\"TOOL_CALL\"]"))))
                .isEqualTo("[\"chat\",\"tools\"]");
        assertThat(Json.write(ModelStore.capabilities(Json.read("[\"CHAT\",\"JSON\",\"FUNCTION_CALLING\"]"))))
                .isEqualTo("[\"chat\",\"json_mode\",\"tools\"]");
    }

    @Test
    void requiresChatAndAValidCapabilityArray() {
        assertThatThrownBy(() -> ModelStore.capabilities(Json.read("[\"tools\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat");
        assertThatThrownBy(() -> ModelStore.capabilities(Json.read("\"chat\"")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelStore.capabilities(Json.read("[\"embedding\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding");
    }

    @Test
    void storedRowsAreNormalizedWithoutBreakingUnknownHistoricalValues() {
        assertThat(Json.write(ModelStore.storedCapabilities(Json.read("[\"CHAT\",\"CUSTOM\"]"))))
                .isEqualTo("[\"chat\",\"custom\"]");
        assertThat(Json.write(ModelStore.storedCapabilities(null))).isEqualTo("[\"chat\"]");
        assertThat(Json.write(ModelStore.storedCapabilities(Json.read("[]")))).isEqualTo("[\"chat\"]");
    }
}
