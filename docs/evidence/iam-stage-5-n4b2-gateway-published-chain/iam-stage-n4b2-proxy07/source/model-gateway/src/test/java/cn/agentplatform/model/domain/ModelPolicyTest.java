package cn.agentplatform.model.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Set;

class ModelPolicyTest {
    @Test
    void localEndpointStillNeedsRequiredLabels() {
        assertThatThrownBy(() -> ModelPolicy.checkLabels(Set.of("SECRET"), Set.of("LOCAL")))
                .isInstanceOf(IllegalArgumentException.class);
        ModelPolicy.checkLabels(Set.of("SECRET"), Set.of("LOCAL", "SECRET"));
    }

    @Test
    void unclassifiedNeverDefaultsToPublic() {
        assertThatThrownBy(() -> ModelPolicy.checkLabels(Set.of(), Set.of("CLOUD")))
                .isInstanceOf(IllegalArgumentException.class);
        ModelPolicy.checkLabels(Set.of(), Set.of("LOCAL"));
    }

    @Test
    void admissionRejectsInsufficientContext() {
        assertThatThrownBy(() -> ModelPolicy.checkBudget(32000, 31000, 2048, 512))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
