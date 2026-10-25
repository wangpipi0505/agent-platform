package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class SkillAttemptPropagationTest {
    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "MCP", "ALGORITHM"})
    void invalidAttemptIsRejectedBeforeAnyBusinessAdapter(String type) {
        var algorithm = mock(AlgorithmAdapter.class);
        var async = mock(HttpAsyncAdapter.class);
        var adapters = new SkillAdapters(mock(ArtifactStore.class), algorithm, async);
        for (long invalid : new long[] {0, -1}) {
            var failure =
                    assertThrows(
                            SkillAdapters.Failure.class,
                            () ->
                                    adapters.invoke(
                                            Map.of("type", type),
                                            Map.of(),
                                            "key",
                                            1,
                                            false,
                                            "run",
                                            invalid));
            assertEquals("PARAM_ERROR", failure.semantic);
        }
        verifyNoInteractions(algorithm, async);
        assertThrows(
                SkillAdapters.Failure.class,
                () -> new McpAdapter().invoke(Map.of(), Map.of(), 1, "run", 0, adapters));
        assertThrows(
                SkillAdapters.Failure.class,
                () ->
                        new AlgorithmAdapter(null)
                                .invoke(Map.of(), Map.of(), "key", 1, false, adapters, "run", 0));
        assertThrows(
                SkillAdapters.Failure.class,
                () ->
                        new HttpAsyncAdapter(null)
                                .invoke(Map.of(), Map.of(), "key", 1, false, "run", 0, adapters));
    }

    @Test
    void explicitOriginalAttemptReachesAlgorithmAndAsyncWithoutReplacement() {
        var algorithm = mock(AlgorithmAdapter.class);
        var async = mock(HttpAsyncAdapter.class);
        var adapters = new SkillAdapters(mock(ArtifactStore.class), algorithm, async);
        var args = Map.<String, Object>of("region", "A");
        var algoContract = Map.<String, Object>of("type", "ALGORITHM");
        adapters.invoke(algoContract, args, "key", 4, true, "run", 37);
        verify(algorithm).invoke(algoContract, args, "key", 4, true, adapters, "run", 37);
        var httpContract = Map.<String, Object>of("type", "HTTP", "invocationKind", "SUBMIT_QUERY");
        adapters.invoke(httpContract, args, "key", 4, true, "run", 37);
        verify(async).invoke(httpContract, args, "key", 4, true, "run", 37, adapters);
    }

    @Test
    void businessEntryPointsHaveNoAttemptFreeOverload() {
        for (var type :
                List.of(
                        SkillAdapters.class,
                        HttpAsyncAdapter.class,
                        McpAdapter.class,
                        AlgorithmAdapter.class)) {
            var methods =
                    Arrays.stream(type.getDeclaredMethods())
                            .filter(m -> m.getName().equals("invoke"))
                            .toList();
            assertEquals(1, methods.size(), type.getSimpleName());
            assertEquals(
                    2,
                    Arrays.stream(methods.getFirst().getParameterTypes())
                            .filter(t -> t == long.class)
                            .count(),
                    type.getSimpleName());
        }
    }
}
