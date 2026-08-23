package jp.chisana.foxkifuscanner;

/** Detects the large white modal that blocks the Go board. */
public final class BlockingDialogDetector {
    private BlockingDialogDetector() {}

    public static boolean isPointShortageText(String text) {
        if (text == null) return false;
        String normalized = text.replaceAll("[\\s。、？?]", "");
        return normalized.contains("絶芸ポイントが不足しています")
                && normalized.contains("取得しに行きますか");
    }

    public static boolean hasDialog(BoardAnalyzer.Pixels pixels, BoardAnalyzer.Region board) {
        int width = pixels.width();
        int y0 = board.top() + (int) Math.round(board.height() * 0.30);
        int y1 = Math.min(board.bottom(), board.top() + (int) Math.round(board.height() * 0.96));
        int x0 = (int) Math.round(width * 0.04);
        int x1 = (int) Math.round(width * 0.96);
        int requiredRun = (int) Math.round(width * 0.62);
        int wideRows = 0;

        for (int y = y0; y <= y1; y += 2) {
            int run = 0;
            int longest = 0;
            int smallGap = 0;
            for (int x = x0; x <= x1; x += 2) {
                if (isModalWhite(pixels.argb(x, y))) {
                    run += 2;
                    smallGap = 0;
                    if (run > longest) longest = run;
                } else if (run > 0 && smallGap < 2) {
                    run += 2;
                    smallGap++;
                } else {
                    run = 0;
                    smallGap = 0;
                }
            }
            if (longest >= requiredRun) wideRows++;
        }
        return wideRows >= Math.max(20, (int) Math.round(board.height() * 0.055));
    }

    private static boolean isModalWhite(int color) {
        int red = (color >> 16) & 255;
        int green = (color >> 8) & 255;
        int blue = color & 255;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        return red >= 232 && green >= 232 && blue >= 232 && maximum - minimum <= 14;
    }
}
