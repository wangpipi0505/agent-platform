package cn.agentplatform.engine.domain;

import java.util.Map;

/**
 * Runtime validation is a node failure with an inspectable path, never an implicit retry (§30.4.2).
 */
public final class RuntimeFault extends RuntimeException {
    public final String semantic, path;

    public RuntimeFault(String semantic, String path, String reason) {
        super(reason);
        this.semantic = semantic;
        this.path = path;
    }

    public Map<String, Object> error() {
        return Map.of(
                "semantic",
                semantic,
                "path",
                path,
                "reason",
                getMessage(),
                "message",
                getMessage());
    }
}
