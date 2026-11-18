package cn.agentplatform.engine.harness.context;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * harness.context 大结果外溢（spill，T2-10；机制规格=主方案 §5.4，字段=数据库稿 §4.5 V231）。
 *
 * <p>工具结果序列化后超过引擎阈值时，不再整块注入模型上下文、也不静默截断，而是把
 * 全文流式转存到对象存储，上下文回填 locator 形状 {@code {spilled:true, spillId, locator,
 * preview, bytes, sha256}} 替换原 result；{@code engine.spill_artifact} 作为索引落库
 * （spill_id 形状 {@code s_[a-f0-9]{32}}、kind=TOOL_RESULT、sha256=全文摘要、preview=前
 * 2KB、bytes=累计字节、storage_ref=对象存储引用、expires_at=保留策略到期时间）。
 *
 * <p><b>接收侧累计字节+有界缓冲（读取过程中转存，不先读全量）</b>：序列化经 Jackson
 * {@code JsonGenerator} 流式写出——先过一遍<b>丢弃式计数流</b>得到精确字节总量做阈值判定
 * （内存零滞留），超限后第二遍真正转存：正文经 64KB 有界窗口逐块写 sink，窗口满即冲刷，
 * 任一时刻内存中不超过一个窗口（外加 2KB 预览缓冲），绝不物化全量字节数组。sha256 在
 * 转存过程中随字节流增量计算（=实际落盘内容的全文摘要），bytes 按块累计。
 *
 * <p><b>阈值内零变化</b>（照 ToolOutputPolicy 既有口径 {@code actual <= maxBytes 原样返回}）：
 * 序列化总量未超阈值时不触碰 sink（零写入）、不落 spill_artifact 行，返回原 result 同一
 * 实例。超限但 sink 缺失时结构化拒绝（不静默整块放行）。
 *
 * <p>事务边界：对象存储写在前（sink.write/finish），索引行插入在后——转存成功但索引失败
 * 会留下待 {@code expires_at} 清理的孤儿对象（与"先事实后索引"的崩溃窗口一致，不回滚对象）。
 * 生产 sink 为 MinIO ArtifactStore（storage_ref=MinIO 受控路径）；本类只依赖
 * {@link StorageSink} 接口，不引入任何对象存储 SDK。
 */
public final class SpillStore {

  /** 序列化结果超阈值（严格大于）才 spill；等于阈值视为阈值内（零变化锚点）。 */
  public static final long DEFAULT_THRESHOLD_BYTES = 262144;
  /** 有界缓冲窗口（主方案 §5.4：如 64KB 窗口，逐块写 sink，不先读全量）。 */
  public static final int WINDOW_BYTES = 64 * 1024;
  /** 预览长度：前 2KB（V231 CHECK preview <= 4KB octets，留有余量）。 */
  public static final int PREVIEW_BYTES = 2048;
  /** kind：工具结果超限外溢（V231 CHECK 之 TOOL_RESULT 分支）。 */
  public static final String KIND_TOOL_RESULT = "TOOL_RESULT";
  /** 保留策略默认值：7 天到期，到期由保留策略清理（数据库稿 §4.5 expires_at）。 */
  public static final java.time.Duration DEFAULT_RETENTION =
          java.time.Duration.ofDays(7);

  /**
   * 对象存储接收侧接口。{@code write} 的 chunk 是复用的有界窗口——实现必须在返回前同步
   * 消费（拷贝/写出），不得留存引用；返回值为接收侧累计字节（主方案 §5.4 口径）。
   */
  public interface StorageSink {

    /** 追加一块（len <= chunk.length）；返回接收侧累计字节。 */
    long write(byte[] chunk, int len);

    /** 结束转存，返回对象存储引用（storage_ref / 上下文回填 locator）。 */
    String finish();
  }

  /**
   * 对象存储读取侧接口（R-15 取回链）。生产实现为 MinIO ArtifactStore 按 storage_ref GET；
   * 测试为内存映射。与 {@link StorageSink} 对偶：写侧 finish 返回的 locator 即读侧入参。
   */
  public interface StorageSource {

    /** 按对象存储引用读回全文；引用不可达时抛出异常（由调用方结构化转换）。 */
    byte[] read(String locator);
  }

  /**
   * 测试/开发适配器：内存累积 sink，记录每块长度供分块断言。<b>无真实 MinIO 依赖</b>；
   * 生产实现为 MinIO ArtifactStore（storage_ref=受控路径），接口契约与本适配器一致：
   * {@code write} 返回累计字节、{@code finish} 返回引用。
   */
  public static final class BufferedMemorySink implements StorageSink {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final List<Integer> chunkSizes = new ArrayList<>();
    private boolean finished;

    @Override
    public long write(byte[] chunk, int len) {
      if (finished) throw new IllegalStateException("sink 已 finish，禁止继续写入");
      bytes.write(chunk, 0, len);
      chunkSizes.add(len);
      return bytes.size();
    }

    @Override
    public String finish() {
      finished = true;
      return "mem://spill/" + UUID.randomUUID();
    }

    /** 已接收的完整正文（测试断言与消费侧回读用；生产 sink 无此方法）。 */
    public byte[] toByteArray() {
      return bytes.toByteArray();
    }

    /** 每块长度序列（分块行为观测；空=从未写入）。 */
    public List<Integer> chunkSizes() {
      return List.copyOf(chunkSizes);
    }

    /** sink 是否被写过（阈值内零 spill 的锚点断言）。 */
    public boolean touched() {
      return !chunkSizes.isEmpty();
    }
  }

  /** spill 结果：索引行字段 + 上下文回填节点（backfill 替换原 result 注入模型上下文）。 */
  public record Spilled(
          String spillId, String locator, long bytes, String sha256, String preview, JsonNode backfill) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final int windowBytes;

  public SpillStore(JdbcTemplate db, PlatformTransactionManager manager) {
    this(db, manager, WINDOW_BYTES);
  }

  /** 窗口大小可注入（测试观察多块行为用；生产用默认 64KB）。 */
  public SpillStore(JdbcTemplate db, PlatformTransactionManager manager, int windowBytes) {
    if (windowBytes < 1) throw new IllegalArgumentException("spill 窗口必须为正整数");
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(5);
    this.windowBytes = windowBytes;
  }

  /**
   * 大结果外溢主入口。阈值内返回原 result（同一实例，零变化）；超限流式转存全文、落
   * spill_artifact 索引行，返回回填节点替换模型上下文中的原 result。
   *
   * @param sessionId      会话 public_id（spill_artifact.session_id FK，不可空）
   * @param callId         工具调用 id（tool_call.id FK；非工具来源可空）
   * @param result         工具执行结果（拦截链处理后、注入模型上下文前的原样结果）
   * @param thresholdBytes 引擎阈值（严格大于才 spill）
   * @param sink           对象存储接收侧（生产=MinIO ArtifactStore；测试=BufferedMemorySink）
   */
  public JsonNode spill(
          String sessionId, Long callId, JsonNode result, long thresholdBytes, StorageSink sink) {
    if (sessionId == null || sessionId.isBlank())
      throw new PlatformException(400, "SPILL_SESSION_REQUIRED", "spill 必须绑定会话");
    if (result == null || result.isMissingNode() || result.isNull()) return result;
    long actual = serializedSize(result);
    if (actual <= thresholdBytes) return result; // 阈值内零变化（不触碰 sink、不落行）
    if (sink == null)
      throw new PlatformException(500, "SPILL_SINK_REQUIRED", "结果超限但对象存储接收侧缺失，拒绝整块注入");
    return transfer(sessionId, callId, result, sink);
  }

  /**
   * 丢弃式计数：流式序列化获取精确字节总量做阈值判定。字节经
   * {@link OutputStream#nullOutputStream()} 直接丢弃，内存零滞留（不先读全量）。
   */
  static long serializedSize(JsonNode result) {
    var counter = new CountingStream();
    generate(result, counter);
    return counter.count;
  }

  /** 转存主体：第二遍流式序列化经有界窗口逐块写 sink，随流增量摘要/计数/截取预览。 */
  private JsonNode transfer(String sessionId, Long callId, JsonNode result, StorageSink sink) {
    var stream = new TransferStream(sink);
    generate(result, stream);
    String ref = sink.finish();
    String spillId = "s_" + UUID.randomUUID().toString().replace("-", "");
    String preview = stream.previewText();
    // MessageDigest.digest() 返回后自复位——摘要只取一次并复用（行与回填必须同值）。
    String sha256 = stream.hexDigest();
    Instant expires = Instant.now().plus(DEFAULT_RETENTION);
    tx.execute(
            status ->
                    {
                      db.update(
                              "insert into engine.spill_artifact"
                                      + "(spill_id,session_id,call_id,kind,storage_ref,bytes,sha256,preview,expires_at)"
                                      + " values(?,?,?,?,?,?,?,?,?)",
                              spillId,
                              sessionId,
                              callId,
                              KIND_TOOL_RESULT,
                              ref,
                              stream.total,
                              sha256,
                              preview,
                              Timestamp.from(expires));
                      return null;
                    });
    var backfill = Json.object();
    backfill.put("spilled", true);
    backfill.put("spillId", spillId);
    backfill.put("locator", ref);
    backfill.put("preview", preview);
    backfill.put("bytes", stream.total);
    backfill.put("sha256", sha256);
    return backfill;
  }

  /**
   * R-15（spill 接入工具环）：按引用取回原文——后续轮次 PromptAssembler 回填的消费入口。
   *
   * <p>归属核验：spill_id+session_id 联合定位（引用不属于本会话=引用不存在，防跨会话
   * 取回，404 口径不区分泄露存在性）。全文经读取侧读回后 sha256 复核（与落库摘要不符
   * 即拒绝，不静默返回近似内容）；原文按 UTF-8 返回。
   *
   * @param sessionId 会话 public_id（引用归属；必须与落盘时一致）
   * @param spillId   外溢引用（回填节点 spillId / spill_artifact.spill_id）
   * @param source    对象存储读取侧（生产=MinIO GET；测试=内存映射）
   */
  public String read(String sessionId, String spillId, StorageSource source) {
    if (sessionId == null || sessionId.isBlank() || spillId == null || spillId.isBlank())
      throw new PlatformException(400, "SPILL_REF_INVALID", "取回必须给出会话与外溢引用");
    var rows =
            db.queryForList(
                    "select storage_ref, sha256 from engine.spill_artifact"
                            + " where spill_id=? and session_id=?",
                    spillId,
                    sessionId);
    if (rows.isEmpty())
      throw new PlatformException(404, "SPILL_REF_NOT_FOUND", "外溢引用不存在或不属于本会话");
    if (source == null)
      throw new PlatformException(500, "SPILL_SOURCE_REQUIRED", "外溢引用读取侧未装配，无法取回原文");
    var row = rows.getFirst();
    byte[] content = source.read(String.valueOf(row.get("storage_ref")));
    String digest = HexFormat.of().formatHex(sha256().digest(content));
    if (!digest.equals(String.valueOf(row.get("sha256"))))
      throw new PlatformException(
              500, "SPILL_DIGEST_MISMATCH", "外溢正文摘要与索引不符，拒绝回填疑似损坏内容");
    return new String(content, StandardCharsets.UTF_8);
  }

  /** 用与 Json.write 相同的 mapper 配置流式序列化（保证两遍字节一致、摘要可复核）。 */
  private static void generate(JsonNode result, OutputStream out) {
    try {
      var generator = Json.mapper().getFactory().createGenerator(out, JsonEncoding.UTF8);
      try {
        Json.mapper().writeValue(generator, result);
        generator.flush();
      } finally {
        generator.close();
      }
    } catch (IOException error) {
      throw new PlatformException(500, "SPILL_SERIALIZE_FAILED", "工具结果流式序列化失败：" + error.getMessage());
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 计数流（第一遍阈值判定；内容丢弃）。 */
  private static final class CountingStream extends OutputStream {
    long count;

    @Override
    public void write(int b) {
      count++;
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      count += len;
    }
  }

  /**
   * 转存流（第二遍）：字节随流增量进摘要与累计计数，经 {@code windowBytes} 有界窗口逐块
   * 写 sink；前 {@link #PREVIEW_BYTES} 字节另存为预览。任一时刻滞留内存 = 一个窗口 +
   * 预览缓冲，绝不物化全量。
   */
  private final class TransferStream extends OutputStream {
    private final StorageSink sink;
    private final byte[] window = new byte[windowBytes];
    private int windowLen;
    private final ByteArrayOutputStream preview = new ByteArrayOutputStream(PREVIEW_BYTES);
    private final MessageDigest digest = sha256();
    long total;

    TransferStream(StorageSink sink) {
      this.sink = sink;
    }

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      digest.update(buf, off, len);
      total += len;
      if (preview.size() < PREVIEW_BYTES)
        preview.write(buf, off, Math.min(len, PREVIEW_BYTES - preview.size()));
      while (len > 0) { // 逐块经窗口写 sink，窗口满即冲刷（接收侧累计字节由 sink 返回）
        int n = Math.min(len, windowBytes - windowLen);
        System.arraycopy(buf, off, window, windowLen, n);
        windowLen += n;
        off += n;
        len -= n;
        if (windowLen == windowBytes) flushWindow();
      }
    }

    private void flushWindow() {
      if (windowLen == 0) return;
      sink.write(window, windowLen);
      windowLen = 0;
    }

    @Override
    public void flush() {
      flushWindow();
    }

    @Override
    public void close() {
      flushWindow();
    }

    String hexDigest() {
      return HexFormat.of().formatHex(digest.digest());
    }

    String previewText() {
      return new String(preview.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
