package jp.chisana.foxkifuscanner;

/** Detects the Android gesture navigation indicator so replay taps never enter its hit area. */
public final class HomeIndicatorDetector {
    public record Result(int top, int bottom, int centerX, int width) {}

    private HomeIndicatorDetector() {}

    /**
     * Looks only in the bottom 3.5% of the screenshot. The indicator is a short, bright,
     * low-chroma horizontal pill; ordinary replay controls are above this band on the target
     * app. A null result is intentional for three-button navigation and for screenshots whose
     * bottom system area is cropped.
     */
    public static Result detect(BoardAnalyzer.Pixels pixels) {
        int width = pixels.width();
        int height = pixels.height();
        if (width <= 0 || height <= 0) return null;
        int y0 = Math.max(0, (int) Math.round(height * 0.965));
        int minRun = Math.max(48, (int) Math.round(width * 0.055));
        int maxRun = Math.max(minRun, (int) Math.round(width * 0.34));
        Result best = null;
        for (int y = y0; y < height; y++) {
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean bright = x < width && isIndicatorPixel(pixels.argb(x, y));
                if (bright && runStart < 0) runStart = x;
                if ((!bright || x == width) && runStart >= 0) {
                    int runEnd = x;
                    int runWidth = runEnd - runStart;
                    int center = (runStart + runEnd - 1) / 2;
                    if (runWidth >= minRun && runWidth <= maxRun
                            && center >= width * 0.25 && center <= width * 0.75
                            && (best == null || runWidth > best.width())) {
                        best = new Result(y, Math.min(height, y + 1), center, runWidth);
                    }
                    runStart = -1;
                }
            }
        }
        return best;
    }

    private static boolean isIndicatorPixel(int color) {
        int r = (color >> 16) & 255;
        int g = (color >> 8) & 255;
        int b = color & 255;
        int min = Math.min(r, Math.min(g, b));
        int max = Math.max(r, Math.max(g, b));
        return min >= 238 && max - min <= 24;
    }
}
