package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Select only immutable app dependencies, never an invocation body's allowDisabled/entry fields.
 */
public final class FrozenModels {
    private FrozenModels() {}

    public static JsonNode reference(JsonNode snapshot, long entryId) {
        JsonNode selected = null;
        for (JsonNode item : snapshot.path("dependencyVersions").path("modelRefs"))
            if (item.path("modelEntryId").asLong() == entryId) {
                if (selected != null)
                    throw new PlatformException(409, "MODEL_BINDING_AMBIGUOUS", "模型冻结引用重复");
                JsonNode ref = item.path("modelBindingRef");
                if (ref.path("bindingId").asLong() < 1
                        || !ref.path("sha256").asText().matches("[a-f0-9]{64}")
                        || !Digest.sha256(Json.canonical(item.path("entry")))
                                .equals(ref.path("sha256").asText()))
                    throw new PlatformException(409, "MODEL_BINDING_TAMPERED", "模型冻结配置摘要不匹配");
                selected = ref;
            }
        if (selected == null)
            throw new PlatformException(409, "MODEL_BINDING_REQUIRED", "运行缺少精确冻结模型引用");
        return selected.deepCopy();
    }
}
