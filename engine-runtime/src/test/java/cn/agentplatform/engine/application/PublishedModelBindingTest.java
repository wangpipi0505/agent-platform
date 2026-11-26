package cn.agentplatform.engine.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishedModelBindingTest {
    @Test void missingFrozenReferenceHasAnActionableReason() {
        assertThatThrownBy(() -> FrozenModels.reference(Json.object(), 1))
                .isInstanceOfSatisfying(PlatformException.class, error ->
                        assertThat(error.code()).isEqualTo("BINDING_NOT_FROZEN"));
    }
}
