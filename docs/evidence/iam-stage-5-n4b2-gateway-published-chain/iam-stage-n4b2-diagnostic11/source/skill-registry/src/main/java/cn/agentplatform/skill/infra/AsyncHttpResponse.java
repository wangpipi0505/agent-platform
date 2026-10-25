package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.nio.file.*;

/**
 * Separate untrusted protocol metadata from the streamed result before applying the normal output
 * gate.
 */
public final class AsyncHttpResponse implements AutoCloseable {
    final Path resultFile;
    JsonNode metadata;
    private int metadataBytes;
    private boolean resultSeen;

    private AsyncHttpResponse(Path path) {
        resultFile = path;
    }

    public static AsyncHttpResponse read(InputStream input, String resultPath) throws IOException {
        var response = new AsyncHttpResponse(Files.createTempFile("agent-async-result-", ".json"));
        var factory = Json.mapper().getFactory().copy();
        factory.setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxDocumentLength(ArtifactStore.MAX_ARTIFACT_BYTES)
                        .maxTokenCount(250000)
                        .maxStringLength(8 * 1024 * 1024)
                        .maxNestingDepth(100)
                        .maxNameLength(1024)
                        .build());
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (var parser = factory.createParser(input)) {
            parser.nextToken();
            response.metadata = response.readNode(parser, "$", resultPath);
            if (parser.nextToken() != null) throw new IOException("trailing JSON");
            return response;
        } catch (Exception ex) {
            response.close();
            throw ex;
        }
    }

    private JsonNode readNode(JsonParser parser, String current, String resultPath)
            throws IOException {
        if (current.equals(resultPath)) {
            if (resultSeen) throw new IOException("duplicate result");
            resultSeen = true;
            // The result is spooled with a hard disk bound; it is never expanded to a whole JSON
            // tree.
            try (var output =
                            new FilterOutputStream(Files.newOutputStream(resultFile)) {
                                long size;

                                @Override
                                public void write(int value) throws IOException {
                                    if (++size > ArtifactStore.MAX_ARTIFACT_BYTES)
                                        throw new IOException("result too large");
                                    out.write(value);
                                }

                                @Override
                                public void write(byte[] bytes, int offset, int length)
                                        throws IOException {
                                    size += length;
                                    if (size > ArtifactStore.MAX_ARTIFACT_BYTES)
                                        throw new IOException("result too large");
                                    out.write(bytes, offset, length);
                                }
                            };
                    var writer = Json.mapper().getFactory().createGenerator(output)) {
                writer.copyCurrentStructure(parser);
            }
            return Json.tree(null);
        }
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            var node = Json.mapper().createObjectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("invalid object");
                String name = parser.currentName();
                budget(name.length());
                parser.nextToken();
                node.set(name, readNode(parser, current + "." + name, resultPath));
            }
            return node;
        }
        if (parser.currentToken() == JsonToken.START_ARRAY) {
            var node = Json.mapper().createArrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY)
                node.add(readNode(parser, current + "[]", resultPath));
            return node;
        }
        if (parser.currentToken() == null || !parser.currentToken().isScalarValue())
            throw new IOException("invalid scalar");
        budget(parser.getTextLength() + 8);
        return Json.mapper().readTree(parser);
    }

    private void budget(int size) throws IOException {
        metadataBytes += size;
        if (metadataBytes > 65536) throw new IOException("async metadata too large");
    }

    public JsonNode metadata() {
        return metadata;
    }

    public InputStream result() throws IOException {
        if (!resultSeen) throw new IOException("missing async result");
        return Files.newInputStream(resultFile);
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(resultFile);
    }
}
