package cn.agentplatform.model.application;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.model.application.FrozenModelBindingVerifier.VerifiedFrozenModelBinding;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.HexFormat;

/** Private semantic-input estimate. It conveys no admission or provider-wire permission. */
final class FrozenInitialInputMeter {
  private FrozenInitialInputMeter() {}
  static Measurement measure(VerifiedFrozenModelBinding binding, byte[] capturedCanonicalUtf8) {
    long started = System.nanoTime(); checkTime(started);
    if (binding == null) throw new PlatformException(403, "MATERIAL_SCOPE_MISSING_OR_CHANGED", "材料范围缺失或已改变");
    binding.requireVerified();
    if (capturedCanonicalUtf8 == null || capturedCanonicalUtf8.length < 1 || capturedCanonicalUtf8.length > 2097152)
      throw new PlatformException(400, "MODEL_INPUT_INVALID", "模型输入无效");
    byte[] bytes = capturedCanonicalUtf8.clone();
    String sha = binding.requestSha256();
    try {
      if (!sha.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
        throw new PlatformException(403, "MATERIAL_SCOPE_MISSING_OR_CHANGED", "材料范围缺失或已改变");
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    long entry = binding.modelEntryId(), id = binding.bindingId();
    String bindingSha = binding.bindingSha256();
    var metadata = binding.frozenMetadata();
    if (!metadata.path("tokenizerUrl").asText().isBlank())
      throw new PlatformException(409, "MODEL_TOKENIZER_REQUIRES_EGRESS", "模型计量需要独立外发校验");
    String canonical;
    try { canonical = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
    catch (CharacterCodingException e) { throw new PlatformException(400, "MODEL_INPUT_INVALID", "模型输入无效"); }
    checkTime(started); binding.requireVerified();
    int tokens;
    try { tokens = ModelCalls.countLocalTokens(metadata.path("modelName").asText(), canonical); }
    catch (IllegalArgumentException e) { throw new PlatformException(409, "MODEL_TOKENIZER_UNSUPPORTED", "模型尚不支持本地计量"); }
    checkTime(started); binding.requireVerified();
    return new Measurement(sha, entry, id, bindingSha, tokens, "JTOKKIT_WITH_CONSERVATIVE_STRUCTURE", "ACCEPTED_CANONICAL_INPUT", false);
  }
  private static void checkTime(long started) {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= 2_000_000_000L)
      throw new PlatformException(503, "SYS-MODEL-503", "模型计量暂不可用");
  }
  record Measurement(String requestSha256, long modelEntryId, long bindingId, String bindingSha256,
      int inputTokens, String method, String basis, boolean exact) {}
}
