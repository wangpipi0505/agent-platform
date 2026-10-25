package cn.agentplatform.common.authority;
import java.util.*;
import cn.agentplatform.common.PlatformException;

/** Permissions shrink by intersection; sensitivity obligations accumulate by union. */
public final class ClaimCeiling {
    private ClaimCeiling() {}

    public static Set<String> intersect(Set<String> left, Set<String> right) {
        var result = new HashSet<>(left == null ? Set.of() : left);
        result.retainAll(right == null ? Set.of() : right);
        return Set.copyOf(result);
    }

    public static Map<String, Set<String>> attributes(
            Map<String, Set<String>> left, Map<String, Set<String>> right) {
        left = left == null ? Map.of() : left;
        right = right == null ? Map.of() : right;
        var result = new HashMap<String, Set<String>>();
        // Only region has a defined permission-set meaning in this stage. Unknown keys add no rights.
        if (left.containsKey("region") || right.containsKey("region"))
            result.put("region", intersect(left.get("region"), right.get("region")));
        var labels = new HashSet<>(left.getOrDefault("dataLabels", Set.of()));
        labels.addAll(right.getOrDefault("dataLabels", Set.of()));
        if (left.containsKey("dataLabels") || right.containsKey("dataLabels"))
            result.put("dataLabels", Set.copyOf(labels));
        for (String key : List.of("bizSystemCode", "userId")) {
            if (!left.containsKey(key) && !right.containsKey(key)) continue;
            var a = left.get(key); var b = right.get(key);
            if (a == null || b == null || a.size() != 1 || !a.equals(b)) throw denied();
            String owner = a.iterator().next();
            if (owner == null || owner.isBlank()) throw denied();
            result.put(key, Set.copyOf(a));
        }
        return Map.copyOf(result);
    }

    public static SubjectKey subject(SubjectKey left, SubjectKey right) {
        if (left == null || !left.equals(right)) throw denied();
        return left;
    }

    private static PlatformException denied() {
        return new PlatformException(403, "SYS-AUTH-403", "授权主体或归属不一致");
    }
}
