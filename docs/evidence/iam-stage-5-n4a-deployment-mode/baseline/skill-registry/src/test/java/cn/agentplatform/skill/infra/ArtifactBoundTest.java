package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;

class ArtifactBoundTest {
    @Test
    void countsEveryByteAndRejectsLimitOverflow() throws Exception {
        var stream = new ArtifactStore.BoundedStream(new ByteArrayInputStream(new byte[9]), 8);
        assertEquals(8, stream.readNBytes(8).length);
        assertThrows(IOException.class, stream::read);
    }

    @Test
    void parserCapsStructuralTokensBeforeCreatingUnboundedTree() {
        byte[] json =
                ("[" + "0,".repeat(250001) + "0]")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(
                IOException.class, () -> ArtifactStore.readJson(new ByteArrayInputStream(json)));
    }

    @Test
    void unavailableObjectStoreCannotYieldSuccessfulArtifact() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ArtifactStore(java.util.Map.of())
                                .offload(
                                        new byte[2],
                                        InputStream.nullInputStream(),
                                        java.util.Map.of("type", "object"),
                                        1));
    }
}
