package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetadataReader {
    private static final Pattern RANK = Pattern.compile(
            "([0-9０-９一二三四五六七八九十IOIlｌ｜丨]+[級段])");
    private static final Pattern NUMBER_RESULT = Pattern.compile(
            "(黒|白).*?([0-9]+(?:\\.[0-9]+)?)(?:と?([0-9]+)/([0-9]+))?(子|目)");
    private static final Pattern NAME_TOKEN = Pattern.compile("[\\p{L}\\p{N}_.-]{1,32}");
    private static final String REJECTED_NAME_WORDS =
            "昇降級戦|互先|定先|先手|譲る|中盤|投了|時間切れ|不戦|勝ち|黒番|白番|"
                    + "絶芸|復盤|ポイント|形勢|研究|アイコン|キャンセル|確認|棋譜";

    private MetadataReader() {}

    public record UiText(String text, Rect bounds) {}
    private record Tagged(UiText value, int priority) {}
    private record Player(String name, String rank) {}
    private record Scored(String value, int score) {}

    public static GameMetadata read(Bitmap screen, BoardAnalyzer.Region board,
                                    List<UiText> accessibilityTexts) {
        return read(screen, board, accessibilityTexts, List.of());
    }

    public static GameMetadata read(Bitmap screen, BoardAnalyzer.Region board,
                                    List<UiText> accessibilityTexts,
                                    List<UiText> ocrTexts) {
        int width = screen.getWidth();
        int top = board.top();
        ArrayList<Tagged> header = new ArrayList<>();
        addHeader(header, accessibilityTexts, 3, top);
        addHeader(header, ocrTexts, 2, top);

        Player left = parsePlayer(header, width, top, true);
        Player right = parsePlayer(header, width, top, false);
        boolean leftBlack = indicatorDarkness(screen, (int) (width * .050),
                top - (int) (width * .134), (int) (width * .019))
                > indicatorDarkness(screen, (int) (width * .950),
                top - (int) (width * .134), (int) (width * .019));

        String all = join(header, width, false);
        String center = join(header, width, true);
        return assemble(left, right, leftBlack, all, center);
    }

    private static void addHeader(List<Tagged> out, List<UiText> source,
                                  int priority, int boardTop) {
        if (source == null) return;
        for (UiText item : source) {
            if (item == null || item.text() == null || item.text().isBlank()
                    || item.bounds() == null || item.bounds().isEmpty()) continue;
            if (item.bounds().centerY() <= boardTop + 8) out.add(new Tagged(item, priority));
        }
    }

    private static String join(List<Tagged> items, int width, boolean centerOnly) {
        StringBuilder out = new StringBuilder();
        for (Tagged tagged : items) {
            Rect bounds = tagged.value().bounds();
            if (centerOnly && (bounds.centerX() < width * .34 || bounds.centerX() > width * .66)) {
                continue;
            }
            out.append(tagged.value().text().trim()).append('\n');
        }
        return normalize(out.toString());
    }

    private static Player parsePlayer(List<Tagged> header, int width, int boardTop,
                                      boolean leftSide) {
        int rowTop = boardTop - (int) (width * .180);
        int rowBottom = boardTop - (int) (width * .005);
        int expectedNameY = boardTop - (int) (width * .114);
        int expectedRankY = boardTop - (int) (width * .054);
        Scored bestName = new Scored("", Integer.MIN_VALUE);
        Scored bestRank = new Scored("", Integer.MIN_VALUE);
        StringBuilder sideText = new StringBuilder();

        for (Tagged tagged : header) {
            UiText item = tagged.value();
            Rect bounds = item.bounds();
            int cx = bounds.centerX(), cy = bounds.centerY();
            boolean onSide = leftSide ? cx >= width * .075 && cx < width * .485
                    : cx > width * .515 && cx <= width * .925;
            if (!onSide || cy < rowTop || cy > rowBottom) continue;

            String text = normalize(item.text()).trim();
            if (text.isEmpty()) continue;
            sideText.append(text).append('\n');
            Matcher rankMatcher = RANK.matcher(text);
            while (rankMatcher.find()) {
                String rank = canonicalRank(rankMatcher.group(1));
                int score = tagged.priority() * 1000 + 260 - Math.abs(cy - expectedRankY) * 3;
                if (text.replaceAll("\\s+", "").equals(rankMatcher.group(1))) score += 80;
                if (!rank.isBlank() && score > bestRank.score()) bestRank = new Scored(rank, score);
            }

            String withoutRank = RANK.matcher(text).replaceAll(" ");
            for (String candidate : nameCandidates(withoutRank)) {
                int score = tagged.priority() * 1000 + 300 - Math.abs(cy - expectedNameY) * 3;
                if (withoutRank.trim().equals(candidate)) score += 90;
                if (bounds.width() < width * .40) score += 25;
                if (score > bestName.score()) bestName = new Scored(candidate, score);
            }
        }

        Player fallback = parsePlayerRaw(sideText.toString());
        String name = bestName.value().isBlank() ? fallback.name() : bestName.value();
        String rank = bestRank.value().isBlank() ? fallback.rank() : bestRank.value();
        return new Player(name, rank);
    }

    private static List<String> nameCandidates(String raw) {
        String value = raw.replace('：', ' ').replace(':', ' ').replace('｜', ' ')
                .replace('|', ' ').replaceAll("[（）()【】\\[\\],，、。]", " ").trim();
        ArrayList<String> result = new ArrayList<>();
        for (String part : value.split("\\s+")) addNameCandidate(result, part);
        String joined = value.replaceAll("\\s+", "");
        addNameCandidate(result, joined);
        return result;
    }

    private static void addNameCandidate(List<String> result, String candidate) {
        String value = candidate.trim();
        if (!NAME_TOKEN.matcher(value).matches()) return;
        if (!value.matches(".*\\p{L}.*")) return;
        if (value.matches(".*(" + REJECTED_NAME_WORDS + ").*")) return;
        if (value.equals("黒") || value.equals("白") || value.equals("級") || value.equals("段")) return;
        if (!result.contains(value)) result.add(value);
    }

    private static Player parsePlayerRaw(String raw) {
        String text = normalize(raw);
        Matcher rankMatch = RANK.matcher(text);
        String rank = rankMatch.find() ? canonicalRank(rankMatch.group(1)) : "";
        String best = "";
        for (String line : text.split("\\n")) {
            String withoutRank = RANK.matcher(line).replaceAll(" ");
            for (String value : nameCandidates(withoutRank)) {
                if (value.length() > best.length()) best = value;
            }
        }
        return new Player(best, rank);
    }

    private static GameMetadata assemble(Player left, Player right, boolean leftBlack,
                                         String all, String center) {
        GameMetadata meta = new GameMetadata();
        Player black = leftBlack ? left : right;
        Player white = leftBlack ? right : left;
        meta.blackName = black.name().isBlank() ? "黒番不明" : black.name();
        meta.blackRank = black.rank();
        meta.whiteName = white.name().isBlank() ? "白番不明" : white.name();
        meta.whiteRank = white.rank();
        meta.handicapText = parseHandicap(all);
        meta.handicap = handicapCount(meta.handicapText);
        meta.result = parseResult(center);
        if (meta.result.isBlank()) meta.result = parseResult(all);
        meta.place = "野狐囲碁";
        return meta;
    }

    static GameMetadata readTextForTest(String left, String center, String right,
                                        boolean leftBlack) {
        return assemble(parsePlayerRaw(left), parsePlayerRaw(right), leftBlack,
                normalize(left + "\n" + center + "\n" + right), normalize(center));
    }

    private static String canonicalRank(String source) {
        if (source == null || source.length() < 2) return "";
        String value = normalizeDigits(source).replace('丨', '1').replace('｜', '1')
                .replace('I', '1').replace('l', '1').replace('ｌ', '1').replace('O', '0');
        char suffix = value.charAt(value.length() - 1);
        String number = value.substring(0, value.length() - 1);
        if (!number.matches("[0-9]+")) number = japaneseInteger(number);
        if (!number.matches("[0-9]+")) return "";
        return number + suffix;
    }

    private static String japaneseInteger(String source) {
        String digits = "一二三四五六七八九";
        if (source.equals("十")) return "10";
        int ten = source.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digits.indexOf(source.charAt(0)) + 1;
            int ones = ten == source.length() - 1 ? 0 : digits.indexOf(source.charAt(ten + 1)) + 1;
            if (tens > 0 && ones >= 0) return Integer.toString(tens * 10 + ones);
        }
        if (source.length() == 1) {
            int value = digits.indexOf(source.charAt(0)) + 1;
            if (value > 0) return Integer.toString(value);
        }
        return source;
    }

    private static String parseHandicap(String source) {
        String text = normalize(source).replaceAll("\\s+", "");
        if (text.contains("定先") || text.contains("先手を譲る") || text.contains("先手譲る")) {
            return "定先";
        }
        if (text.contains("互先")) return "互先";
        Matcher matcher = Pattern.compile("([2-9２-９二三四五六七八九])子").matcher(text);
        return matcher.find() ? japaneseInteger(normalizeDigits(matcher.group(1))) + "子" : "互先";
    }

    private static int handicapCount(String text) {
        Matcher matcher = Pattern.compile("([2-9])子").matcher(normalizeDigits(text));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    static String parseResult(String source) {
        String text = normalize(source).replaceAll("\\s+", "")
                .replace('／', '/').replace("¼", "1/4").replace("½", "1/2")
                .replace("¾", "3/4");
        if (text.contains("持碁") || text.contains("引き分け")) return "0";

        Matcher resigned = Pattern.compile("(黒|白).*?(?:中盤|投了).*?勝").matcher(text);
        if (resigned.find()) return winner(resigned.group(1)) + "+R";
        Matcher timed = Pattern.compile("(黒|白).*?時間切れ.*?勝").matcher(text);
        if (timed.find()) return winner(timed.group(1)) + "+T";
        Matcher forfeit = Pattern.compile("(黒|白).*?不戦.*?勝").matcher(text);
        if (forfeit.find()) return winner(forfeit.group(1)) + "+F";

        Matcher halfPoint = Pattern.compile("(黒|白).*?半目.*?勝").matcher(text);
        if (halfPoint.find()) return winner(halfPoint.group(1)) + "+0.5";
        Matcher half = Pattern.compile("(黒|白).*?([0-9]+)目半.*?勝").matcher(text);
        if (half.find()) {
            double amount = Double.parseDouble(half.group(2)) + .5;
            return winner(half.group(1)) + "+" + formatAmount(amount);
        }

        Matcher numeric = NUMBER_RESULT.matcher(text);
        if (!numeric.find()) return "";
        double amount = Double.parseDouble(numeric.group(2));
        if (numeric.group(3) != null) {
            amount += Double.parseDouble(numeric.group(3))
                    / Double.parseDouble(numeric.group(4));
        }
        if (numeric.group(5).equals("子")) amount *= 2.0;
        return winner(numeric.group(1)) + "+" + formatAmount(amount);
    }

    private static String winner(String value) {
        return value.equals("黒") ? "B" : "W";
    }

    private static String formatAmount(double amount) {
        if (amount == Math.rint(amount)) return Long.toString(Math.round(amount));
        return String.format(Locale.ROOT, "%.3f", amount).replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static double indicatorDarkness(Bitmap bitmap, int cx, int cy, int radius) {
        int dark = 0, count = 0;
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) continue;
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > radius * radius) continue;
                int color = bitmap.getPixel(x, y);
                int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
                if ((red + green + blue) / 3 < 75) dark++;
                count++;
            }
        }
        return dark / (double) Math.max(1, count);
    }

    private static String normalize(String source) {
        if (source == null) return "";
        return normalizeDigits(source).replace("級級", "級");
    }

    private static String normalizeDigits(String source) {
        String full = "０１２３４５６７８９", ascii = "0123456789";
        for (int i = 0; i < 10; i++) source = source.replace(full.charAt(i), ascii.charAt(i));
        return source;
    }
}
