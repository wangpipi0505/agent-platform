package cn.agentplatform.common.authority;
import java.util.*;
public final class ClaimCeiling {
    public static Set<String> intersect(Set<String> left, Set<String> right) { return left == null ? Set.of() : left; }
    public static Map<String, Set<String>> attributes(Map<String, Set<String>> left, Map<String, Set<String>> right) { return left; }
    public static SubjectKey subject(SubjectKey left, SubjectKey right) { return left; }
}
