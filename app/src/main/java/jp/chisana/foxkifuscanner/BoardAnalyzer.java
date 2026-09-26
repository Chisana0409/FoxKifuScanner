package jp.chisana.foxkifuscanner;

public final class BoardAnalyzer {
    public interface Pixels {
        int width();
        int height();
        int argb(int x, int y);
    }

    public record Region(int left, int top, int right, int bottom) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    public record Detection(Region board, BoardState state, double confidence) {}

    private BoardAnalyzer() {}

    public static Detection detect(Pixels pixels) {
        Region board = findWoodBoard(pixels);
        if (board == null) throw new IllegalStateException("19路盤の木目領域を検出できません");
        return detect(pixels, board);
    }

    public static Detection detect(Pixels pixels, Region board) {
        int w = board.width(), h = board.height();
        double left = board.left + w * 0.039;
        double right = board.right - w * 0.034;
        double top = board.top + h * 0.033;
        double bottom = board.bottom - h * 0.031;
        double dx = (right - left) / 18.0;
        double dy = (bottom - top) / 18.0;
        int radius = Math.max(3, (int) Math.round(Math.min(dx, dy) * 0.34));
        byte[] cells = new byte[361];
        double certainty = 0;
        for (int y = 0; y < 19; y++) {
            for (int x = 0; x < 19; x++) {
                Sample s = sample(pixels, (int) Math.round(left + x * dx), (int) Math.round(top + y * dy), radius);
                byte value = BoardState.EMPTY;
                double c;
                if (s.blackRatio > 0.34 && s.meanLuma < 145) {
                    value = BoardState.BLACK;
                    c = Math.min(1, s.blackRatio * 1.7);
                } else if (s.meanChroma < 49
                        && ((s.whiteRatio > 0.31 && s.meanLuma > 158)
                        || (s.whiteRatio > 0.52 && s.meanLuma > 125))) {
                    value = BoardState.WHITE;
                    c = Math.min(1, s.whiteRatio * 1.55);
                } else {
                    c = Math.max(0.45, 1.0 - Math.max(s.blackRatio, s.whiteRatio));
                }
                cells[y * 19 + x] = value;
                certainty += c;
            }
        }
        return new Detection(board, new BoardState(cells), certainty / 361.0);
    }

    static Region findWoodBoard(Pixels p) {
        int w = p.width(), h = p.height();
        int startY = (int) (h * 0.10), endY = (int) (h * 0.78);
        boolean[] rows = new boolean[h];
        for (int y = startY; y < endY; y += 2) {
            int warm = 0, total = 0;
            for (int x = 0; x < w; x += 3) {
                if (isWood(p.argb(x, y))) warm++;
                total++;
            }
            boolean ok = warm > total * 0.38;
            rows[y] = ok;
            if (y + 1 < h) rows[y + 1] = ok;
        }
        int bestStart = -1, bestEnd = -1, run = -1, lastWarm = -1;
        int allowedGap = Math.max(8, (int) Math.round(w * 0.04));
        for (int y = startY; y <= endY; y++) {
            if (y < endY && rows[y]) {
                if (run < 0) run = y;
                lastWarm = y;
            } else if (run >= 0 && (y == endY || y - lastWarm > allowedGap)) {
                int candidateEnd = lastWarm + 1;
                if (bestStart < 0 || candidateEnd - run > bestEnd - bestStart) {
                    bestStart = run;
                    bestEnd = candidateEnd;
                }
                run = -1;
                lastWarm = -1;
            }
        }
        if (bestStart < 0 || bestEnd - bestStart < w * 0.62) return fallbackBoard(p);

        boolean[] cols = new boolean[w];
        for (int x = 0; x < w; x += 2) {
            int warm = 0, total = 0;
            for (int y = bestStart; y < bestEnd; y += 3) {
                if (isWood(p.argb(x, y))) warm++;
                total++;
            }
            boolean ok = warm > total * 0.28;
            cols[x] = ok;
            if (x + 1 < w) cols[x + 1] = ok;
        }
        int left = 0, right = w;
        while (left < w && !cols[left]) left++;
        while (right > left && !cols[right - 1]) right--;
        int horizontalSpan = right - left;
        int verticalSpan = bestEnd - bestStart;
        int side = Math.abs(horizontalSpan - verticalSpan) <= w * 0.10
                ? Math.max(horizontalSpan, verticalSpan)
                : Math.min(horizontalSpan, verticalSpan);
        if (side < w * 0.62) return fallbackBoard(p);
        int cx = (left + right) / 2, cy = (bestStart + bestEnd) / 2;
        return new Region(Math.max(0, cx - side / 2), Math.max(0, cy - side / 2),
                Math.min(w, cx + side / 2), Math.min(h, cy + side / 2));
    }

    private static Region fallbackBoard(Pixels p) {
        int side = p.width();
        int top = (int) Math.round(p.height() * 0.1777);
        if (top + side > p.height()) return null;
        return new Region(0, top, side, top + side);
    }

    private static boolean isWood(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 145 && g > 100 && b < 205 && r - g > 8 && g - b > 8;
    }

    private record Sample(double blackRatio, double whiteRatio, double meanLuma, double meanChroma) {}

    private static Sample sample(Pixels p, int cx, int cy, int radius) {
        int black = 0, white = 0, count = 0;
        double luma = 0, chroma = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy > radius * radius) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= p.width() || y >= p.height()) continue;
                int c = p.argb(x, y);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                int chr = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (lum < 92) black++;
                if (lum > 176 && chr < 48) white++;
                luma += lum; chroma += chr; count++;
            }
        }
        return new Sample(black / (double) count, white / (double) count, luma / count, chroma / count);
    }

    public static boolean isInitial(BoardState state, int handicap) {
        if (handicap <= 1) return state.count(BoardState.BLACK) == 0 && state.count(BoardState.WHITE) == 0;
        if (state.count(BoardState.BLACK) != handicap || state.count(BoardState.WHITE) != 0) return false;
        int[][] stars = {{3,3},{9,3},{15,3},{3,9},{9,9},{15,9},{3,15},{9,15},{15,15}};
        for (int y = 0; y < 19; y++) for (int x = 0; x < 19; x++) {
            if (state.at(x,y) != BoardState.BLACK) continue;
            boolean star = false;
            for (int[] s : stars) if (s[0] == x && s[1] == y) { star = true; break; }
            if (!star) return false;
        }
        return true;
    }
}
