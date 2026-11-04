package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AlgorithmDatabasePortTest {
    @Test
    void preservesLegacyDefaultAndUsesTheExplicitDevInstance() {
        String name = "agent_platform_skill_algorithm_test";
        assertEquals("jdbc:postgresql://127.0.0.1:25432/" + name,
                AlgorithmTestDatabase.jdbcUrl(name, null));
        assertEquals("jdbc:postgresql://127.0.0.1:35432/" + name,
                AlgorithmTestDatabase.jdbcUrl(name, "35432"));
    }

    @Test
    void refusesSceneDatabaseAndOtherTestFamilies() {
        for (String name : new String[] {"agent_platform_dev_v12", "agent_platform_test", "postgres"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AlgorithmTestDatabase.jdbcUrl(name, "35432"));
        }
    }

    @Test
    void refusesInvalidPortWithoutFallingBackToTheLegacyInstance() {
        for (String port : new String[] {"", "0", "65536", "035432", "host:35432", "35432/path"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AlgorithmTestDatabase.jdbcUrl("agent_platform_skill_algorithm_test", port));
        }
    }
}
