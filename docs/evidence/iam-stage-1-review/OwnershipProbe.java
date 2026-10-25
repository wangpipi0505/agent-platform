import cn.agentplatform.common.authority.ClaimCeiling;
import java.util.Map;
import java.util.Set;

/** Review-only bounded probe: existing tests omit blank ownership singleton values. */
class OwnershipProbe {
    public static void main(String[] args) {
        for (String key : Set.of("bizSystemCode", "userId")) {
            for (String value : new String[] {"", " "}) {
                var attributes = Map.of(key, Set.of(value));
                var result = ClaimCeiling.attributes(attributes, attributes);
                if (!result.equals(attributes)) throw new AssertionError("Unexpected result");
                System.out.println(key + ": accepted equal blank singleton, length=" + value.length());
            }
        }
    }
}
