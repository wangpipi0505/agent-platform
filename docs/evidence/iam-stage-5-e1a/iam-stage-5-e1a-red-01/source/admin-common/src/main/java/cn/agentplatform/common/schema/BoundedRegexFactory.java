package cn.agentplatform.common.schema;

import com.google.re2j.Pattern;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressionFactory;

import java.util.*;

/**
 * networknt's existing extension, using fixed RE2/J, with bounded compilation and no JDK fallback.
 */
public final class BoundedRegexFactory implements RegularExpressionFactory {
    private final Map<String, Pattern> compiled = new HashMap<>();
    private long programs;

    public BoundedRegexFactory(Set<String> expressions) {
        if (expressions.size() > 32) throw new IllegalArgumentException("Schema正则数量超过32");
        for (String expression : expressions) {
            SchemaDeadline.check();
            admitCompilation(expression);
            final Pattern pattern;
            try {
                pattern = Pattern.compile(expression);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Schema正则不属于支持的RE2语法");
            }
            if (pattern.programSize() > 2048)
                throw new IllegalArgumentException("Schema正则指令超过2048");
            programs += pattern.programSize();
            compiled.put(expression, pattern);
            SchemaDeadline.check();
        }
    }

    public long programUnits() {
        return programs;
    }

    private static void admitCompilation(String expression) {
        if (expression.length() > 256) throw new IllegalArgumentException("Schema正则超过256字符");
        // RE2 compilation expands counted repetitions (Compiler has no length limit).
        // This deliberately conservative upper estimate precedes Pattern.compile;
        // it overcounts disjoint repeats rather than building a second regex parser.
        long repetitions = 1;
        boolean characterClass = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\\') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == 'Q')
                    throw new IllegalArgumentException("Schema正则不支持\\Q引用语法");
                i++;
                continue;
            }
            if (c == '[') characterClass = true;
            if (c == ']') characterClass = false;
            if (characterClass || c != '{') continue;
            int j = i + 1, maximum = 0;
            boolean digits = false;
            while (j < expression.length() && Character.isDigit(expression.charAt(j))) {
                digits = true;
                maximum = Math.min(4097, maximum * 10 + expression.charAt(j++) - '0');
            }
            if (!digits) continue;
            if (j < expression.length() && expression.charAt(j) == ',') {
                j++;
                int upper = 0;
                boolean bounded = false;
                while (j < expression.length() && Character.isDigit(expression.charAt(j))) {
                    bounded = true;
                    upper = Math.min(4097, upper * 10 + expression.charAt(j++) - '0');
                }
                maximum = bounded ? Math.max(maximum, upper) : maximum + 1;
            }
            if (j < expression.length() && expression.charAt(j) == '}') {
                repetitions = Math.min(4097, repetitions * Math.max(1, maximum));
                if (repetitions * Math.max(1, expression.length()) * 4 > 4096)
                    throw new IllegalArgumentException("Schema正则重复展开预算超过4096");
                i = j;
            }
        }
    }

    @Override
    public RegularExpression getRegularExpression(String expression) {
        Pattern pattern = compiled.get(expression);
        if (pattern == null) throw new IllegalArgumentException("Schema正则未通过前置预算");
        return value -> {
            SchemaDeadline.check();
            // Even a direct framework invocation cannot bypass the per-match work limit.
            if ((long) pattern.programSize() * (value.length() + 1L) > 4_000_000L)
                throw new IllegalArgumentException("Schema正则匹配工作量超过上限");
            boolean matched = pattern.matcher(value).find();
            SchemaDeadline.check();
            return matched;
        };
    }
}
