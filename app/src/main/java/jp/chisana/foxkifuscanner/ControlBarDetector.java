package jp.chisana.foxkifuscanner;

/** Finds the replay controls only inside the protected strip at the screen bottom. */
public final class ControlBarDetector {
    public record Result(int y, int safeTop, int safeBottom, int trackRun) {}
    public record Thumb(int centerX, int score) {}

    private ControlBarDetector() {}

    public static Result detect(BoardAnalyzer.Pixels pixels, BoardAnalyzer.Region board) {
        int width = pixels.width();
        int height = pixels.height();
        int safeTop = safeTop(height, board);
        int safeBottom = Math.min(height - 1, (int) Math.round(height * 0.985));
        if (safeTop >= safeBottom) {
            throw new IllegalStateException("手数操作バーの安全領域を確保できません");
        }

        int x0 = (int) Math.round(width * 0.035);
        int x1 = (int) Math.round(width * 0.495);
        int bestY = -1;
        int bestRun = 0;
        for (int y = safeTop; y <= safeBottom; y++) {
            int run = 0;
            int longest = 0;
            int gap = 0;
            for (int x = x0; x <= x1; x++) {
                if (isReplayBlue(pixels.argb(x, y))) {
                    run++;
                    gap = 0;
                    if (run > longest) longest = run;
                } else if (run > 0 && gap < 2) {
                    run++;
                    gap++;
                } else {
                    run = 0;
                    gap = 0;
                }
            }
            if (longest > bestRun) {
                bestRun = longest;
                bestY = y;
            }
        }

        int requiredRun = Math.max(48, (int) Math.round(width * 0.10));
        if (bestY < 0 || bestRun < requiredRun) {
            throw new IllegalStateException("画面最下部の手数操作バーを検出できません");
        }
        return new Result(bestY, safeTop, safeBottom, bestRun);
    }

    public static int safeTop(int screenHeight, BoardAnalyzer.Region board) {
        int byScreen = (int) Math.round(screenHeight * 0.875);
        int remainingBelowBoard = Math.max(0, screenHeight - board.bottom());
        int byBoard = board.bottom() + (int) Math.round(remainingBelowBoard * 0.64);
        return Math.max(byScreen, byBoard);
    }

    public static Thumb detectThumb(BoardAnalyzer.Pixels pixels, int controlY) {
        int width = pixels.width();
        int height = pixels.height();
        int x0 = (int) Math.round(width * 0.02);
        int x1 = (int) Math.round(width * 0.50);
        int radius = Math.max(12, (int) Math.round(height * 0.020));
        int y0 = Math.max(0, controlY - radius);
        int y1 = Math.min(height - 1, controlY + radius);
        int[] scores = new int[x1 - x0 + 1];
        int best = 0;
        for (int x = x0; x <= x1; x++) {
            int score = 0;
            for (int y = y0; y <= y1; y++) {
                if (isReplayBlue(pixels.argb(x, y))) score++;
            }
            scores[x - x0] = score;
            if (score > best) best = score;
        }
        if (best < 5) return new Thumb(-1, best);

        int threshold = Math.max(5, (int) Math.round(best * 0.55));
        int left = x1;
        int right = x0;
        for (int x = x0; x <= x1; x++) {
            if (scores[x - x0] >= threshold) {
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        if (right < left) return new Thumb(-1, best);
        return new Thumb((left + right) / 2, best);
    }

    private static boolean isReplayBlue(int color) {
        int red = (color >> 16) & 255;
        int green = (color >> 8) & 255;
        int blue = color & 255;
        return blue >= 58 && blue - red >= 18 && blue - green >= 10
                && red < 155 && green < 170;
    }
}
