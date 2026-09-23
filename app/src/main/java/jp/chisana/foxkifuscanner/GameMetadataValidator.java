package jp.chisana.foxkifuscanner;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Validation used by the editor before values are written into an SGF. */
public final class GameMetadataValidator {
    private GameMetadataValidator() {}

    public static String validate(GameMetadata meta) {
        if (meta == null) return "対局情報がありません";
        String error = required(meta.date, "対局日時");
        if (error != null) return error;
        try {
            LocalDate.parse(meta.date.trim());
        } catch (DateTimeParseException e) {
            return "対局日時は yyyy-MM-dd 形式で入力してください";
        }
        error = required(meta.blackName, "黒番名");
        if (error != null) return error;
        error = required(meta.whiteName, "白番名");
        if (error != null) return error;
        error = required(meta.rule, "ルール");
        if (error != null) return error;
        error = required(meta.komi, "コミ");
        if (error != null) return error;
        try {
            double komi = Double.parseDouble(meta.komi.trim());
            if (!Double.isFinite(komi) || komi < 0) return "コミは0以上の数値で入力してください";
        } catch (NumberFormatException e) {
            return "コミは0以上の数値で入力してください";
        }
        error = required(meta.result, "勝敗");
        if (error != null) return error;
        if (!meta.result.trim().matches("(?:0|[BW]\\+(?:R|T|F|[0-9]+(?:\\.[0-9]+)?))")) {
            return "勝敗は B+R、W+8.5、0 などのSGF形式で入力してください";
        }
        if (!isSgfText(meta.event) || !isSgfText(meta.place)
                || !isSgfText(meta.blackName) || !isSgfText(meta.blackRank)
                || !isSgfText(meta.whiteName) || !isSgfText(meta.whiteRank)
                || !isSgfText(meta.rule)) {
            return "対局情報に制御文字を入力できません";
        }
        return "";
    }

    private static String required(String value, String label) {
        return value == null || value.isBlank() ? label + "は必須です" : null;
    }

    private static boolean isSgfText(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }
}
