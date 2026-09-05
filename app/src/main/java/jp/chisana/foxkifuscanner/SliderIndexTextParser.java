package jp.chisana.foxkifuscanner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an exact, human-readable replay index exposed by an accessibility node.
 *
 * <p>The complete input must be one supported current/total expression. This deliberately
 * avoids fishing numbers out of unrelated accessibility text such as clocks, ranks, results,
 * addresses, or labels which happen to contain digits.</p>
 */
public final class SliderIndexTextParser {
    private static final int MAX_MOVES = 1000;
    private static final String SPACE = "[\\s\\p{Z}]*";
    private static final String NUMBER = "(0|[1-9][0-9]{0,3})";
    private static final String SLASH = "[/／]";

    private static final Pattern[] SUPPORTED_PATTERNS = {
            Pattern.compile("^" + SPACE + NUMBER + SPACE + SLASH + SPACE
                    + NUMBER + SPACE + "$"),
            Pattern.compile("^" + SPACE + NUMBER + SPACE + "手" + SPACE + SLASH + SPACE
                    + NUMBER + SPACE + "手" + SPACE + "$"),
            Pattern.compile("^" + SPACE + "第" + SPACE + NUMBER + SPACE + "手" + SPACE
                    + SLASH + SPACE + "共" + SPACE + NUMBER + SPACE + "手" + SPACE + "$")
    };

    private SliderIndexTextParser() {
    }

    /**
     * Returns a validated slider snapshot, or {@code null} when {@code text} is not an exact,
     * unambiguous current/total move expression.
     */
    public static Snapshot parse(String text) {
        if (text == null) return null;

        for (Pattern pattern : SUPPORTED_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.matches()) continue;

            int current = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            if (total > MAX_MOVES || current > total) return null;
            return new Snapshot(current, total);
        }
        return null;
    }

    public record Snapshot(int current, int total) {
    }
}
