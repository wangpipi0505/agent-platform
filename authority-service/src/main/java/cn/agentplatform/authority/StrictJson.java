package cn.agentplatform.authority;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 所有外部 JSON 载荷（决策请求、票据头/载荷）的唯一解析入口。
 * <p>两个必须的严格性：重复键拒绝（否则 {@code request_sha256} 的语义取决于解析器实现），
 * 以及尾随内容拒绝（否则可以拼出"合法对象 + 垃圾字节"绕过大小/形状校验）。
 */
public final class StrictJson {

    private static final ObjectReader READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictJson() {
    }

    public static JsonNode read(byte[] bytes) throws IOException {
        return READER.readTree(bytes);
    }
}
