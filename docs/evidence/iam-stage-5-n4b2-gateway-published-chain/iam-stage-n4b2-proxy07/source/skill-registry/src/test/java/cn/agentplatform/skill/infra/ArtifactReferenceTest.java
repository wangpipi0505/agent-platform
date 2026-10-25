package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class ArtifactReferenceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "https://external.invalid/private.json",
                "s3://private-bucket/key",
                "pg://table/1",
                "file:///private/key",
                "../private/key"
            })
    void externalFileReferenceCannotBecomeTrustedArtifactByProtocolAlone(String external) {
        var adapter = new SkillAdapters(new ArtifactStore(Map.of()), null);
        var contract =
                Map.<String, Object>of(
                        "outputMaxBytes",
                        1024,
                        "outputSchema",
                        Map.of("type", "object"),
                        "returnSpec",
                        Map.of("fileRefPaths", List.of("$.file")));
        byte[] response = Json.write(Map.of("file", external)).getBytes(StandardCharsets.UTF_8);
        assertThrows(
                SkillAdapters.Failure.class,
                () -> adapter.read(new ByteArrayInputStream(response), contract, 1));
    }
}
