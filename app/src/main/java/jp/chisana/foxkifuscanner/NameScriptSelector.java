package jp.chisana.foxkifuscanner;

/** Pure scoring rules for choosing a player-name OCR model. */
public final class NameScriptSelector {
    public enum OcrModel { JAPANESE, CHINESE }
    public enum ScriptKind { JAPANESE, LATIN, HAN, MIXED, UNKNOWN }

    private static final String SIMPLIFIED_CHINESE_HINTS =
            "汉龙凤华刘陈赵杨吴郑冯卫蒋韩许吕张谢邓罗陆叶钟谭苏萧龚贾赖齐卢乔"
                    + "邹顾严欧阳马云伟兰孙钱窦鲁韦费贺汤毕邬乐宁晓杰鹏飞";

    private NameScriptSelector() {}

    public static ScriptKind classify(String text) {
        if (text == null || text.isBlank()) return ScriptKind.UNKNOWN;
        boolean kana = false, han = false, latin = false, otherLetter = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) kana = true;
            else if (script == Character.UnicodeScript.HAN) han = true;
            else if (script == Character.UnicodeScript.LATIN) latin = true;
            else if (Character.isLetter(cp)) otherLetter = true;
        }
        if (kana) return ScriptKind.JAPANESE;
        if (han && (latin || otherLetter)) return ScriptKind.MIXED;
        if (han) return ScriptKind.HAN;
        if (latin && !otherLetter) return ScriptKind.LATIN;
        return ScriptKind.UNKNOWN;
    }

    /** Returns only the language/model bonus. */
    public static int score(String text, OcrModel model, float confidence,
                            String recognizedLanguage) {
        int score = Math.round(clamp01(confidence) * 160f);
        ScriptKind kind = classify(text);
        if (kind == ScriptKind.JAPANESE) {
            score += model == OcrModel.JAPANESE ? 210 : -180;
        } else if (kind == ScriptKind.LATIN) {
            score += model == OcrModel.JAPANESE ? 90 : -35;
        } else if (kind == ScriptKind.HAN) {
            score += model == OcrModel.JAPANESE ? 18 : 0;
            if (hasSimplifiedChineseHint(text)) {
                score += model == OcrModel.CHINESE ? 190 : -90;
            }
        } else if (kind == ScriptKind.MIXED) {
            score += model == OcrModel.JAPANESE ? 22 : 0;
        }

        String language = recognizedLanguage == null ? "" : recognizedLanguage.toLowerCase();
        if ((model == OcrModel.JAPANESE && language.startsWith("ja"))
                || (model == OcrModel.CHINESE && language.startsWith("zh"))) {
            score += 14;
        }
        return score;
    }

    /**
     * Scores the OCR text together with its visual span. A recognizer can label a
     * four-glyph name rectangle such as 和平卫士 as one kana. Confidence alone cannot
     * reject that candidate, but its text length is incompatible with the rectangle.
     */
    public static int score(String text, OcrModel model, float confidence,
                            String recognizedLanguage, int boxWidth, int boxHeight,
                            int widestPeerWidth) {
        return score(text, model, confidence, recognizedLanguage)
                + visualLengthScore(text, boxWidth, boxHeight)
                + lineCoverageScore(boxWidth, widestPeerWidth);
    }

    static int visualLengthScore(String text, int boxWidth, int boxHeight) {
        if (text == null || text.isBlank() || boxWidth <= 0 || boxHeight <= 0) return 0;
        double expectedAdvance = 0d;
        int visibleCharacters = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                expectedAdvance += .95d;
                visibleCharacters++;
            } else if (script == Character.UnicodeScript.LATIN || Character.isDigit(cp)) {
                expectedAdvance += .58d;
                visibleCharacters++;
            } else if (cp == '_' || cp == '-' || cp == '.') {
                expectedAdvance += .45d;
                visibleCharacters++;
            } else if (!Character.isWhitespace(cp)) {
                return 0;
            }
        }
        if (visibleCharacters == 0 || expectedAdvance <= 0d) return 0;

        double observedAdvance = boxWidth / (double) boxHeight;
        double error = Math.abs(observedAdvance - expectedAdvance);
        int fit = (int) Math.round(80d - error * 85d);
        fit = Math.max(-260, Math.min(80, fit));
        return fit + Math.min(visibleCharacters, 8) * 10;
    }

    static int lineCoverageScore(int boxWidth, int widestPeerWidth) {
        if (boxWidth <= 0 || widestPeerWidth <= 0) return 0;
        double coverage = Math.min(1d, boxWidth / (double) widestPeerWidth);
        if (coverage >= .80d) return 120;
        if (coverage >= .55d) return 35;
        if (coverage >= .35d) return -55;
        return -150;
    }

    private static boolean hasSimplifiedChineseHint(String text) {
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp <= Character.MAX_VALUE
                    && SIMPLIFIED_CHINESE_HINTS.indexOf((char) cp) >= 0) return true;
        }
        return false;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value < 0f) return 0f;
        return Math.min(1f, value);
    }
}
