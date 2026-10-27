// PLAT-08a 兼容性抽查：对 .local/contracts 里真实技能契约的 inputSchema/outputSchema
// 做 normalize + validate，要求「归一无差异、无错误」。
// 运行（工程根目录，先 mvn -pl admin-common install 或确保 target/classes 已编译）：
//   jshell --execution local --class-path "admin-common/target/classes;<jackson-databind.jar>;<jackson-core.jar>;<jackson-annotations.jar>" docs/evidence/2026-10-27-PLAT-08a-dialect/compat-check.jsh
import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.SchemaDialect;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
int files = 0, schemas = 0, changed = 0, invalid = 0;
try (var dir = Files.newDirectoryStream(Path.of(".local", "contracts"), "*.json")) {
    for (Path p : dir) {
        files++;
        JsonNode contract = Json.read(Files.readString(p));
        for (String field : List.of("inputSchema", "outputSchema")) {
            if (!contract.has(field)) continue;
            schemas++;
            JsonNode s = contract.get(field);
            JsonNode n = SchemaDialect.normalize(s);
            if (!n.equals(s)) { changed++; System.out.println("CHANGED " + p.getFileName() + " " + field); }
            var errs = SchemaDialect.validate(n);
            if (!errs.isEmpty()) { invalid++; System.out.println("INVALID " + p.getFileName() + " " + field + " " + errs); }
        }
    }
}
System.out.println("contracts=" + files + " schemas=" + schemas + " changed=" + changed + " invalid=" + invalid);
