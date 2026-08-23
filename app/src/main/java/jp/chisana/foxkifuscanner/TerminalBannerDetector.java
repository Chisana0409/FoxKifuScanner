package jp.chisana.foxkifuscanner;

/** Detects the dark-blue "already at the final move" banner without native OCR. */
public final class TerminalBannerDetector {
    private TerminalBannerDetector() {}

    public static boolean hasBanner(BoardAnalyzer.Pixels pixels, BoardAnalyzer.Region board) {
        int x0 = board.left() + (int) (board.width() * 0.08);
        int x1 = board.right() - (int) (board.width() * 0.08);
        int y0 = board.top() + (int) (board.height() * 0.20);
        int y1 = board.top() + (int) (board.height() * 0.92);
        int requiredRun = (int) (board.width() * 0.42);
        int wideRows = 0;
        for (int y = y0; y < y1; y += 2) {
            int run = 0;
            int longest = 0;
            for (int x = x0; x < x1; x += 2) {
                int color = pixels.argb(x, y);
                int red = (color >> 16) & 255;
                int green = (color >> 8) & 255;
                int blue = color & 255;
                boolean darkBlue = blue >= 58 && blue - red >= 18 && blue - green >= 12
                        && red < 112 && green < 132;
                if (darkBlue) {
                    run += 2;
                    if (run > longest) longest = run;
                } else {
                    run = 0;
                }
            }
            if (longest >= requiredRun) wideRows++;
        }
        return wideRows >= Math.max(6, (int) (board.height() * 0.018));
    }
}
