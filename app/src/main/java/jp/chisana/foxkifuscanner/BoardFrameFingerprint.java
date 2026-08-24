package jp.chisana.foxkifuscanner;

import java.util.Arrays;

/**
 * Lightweight 19x19 board fingerprint used while waiting for the next frame.
 *
 * <p>This deliberately samples only points around each intersection.  It is not
 * used to decide a move; a changed fingerprint merely triggers one complete
 * {@link BoardAnalyzer} pass after the image has become stable.</p>
 */
public final class BoardFrameFingerprint {
    private final byte[] cells;

    private BoardFrameFingerprint(byte[] cells) {
        this.cells = cells;
    }

    public static BoardFrameFingerprint capture(BoardAnalyzer.Pixels pixels,
                                                BoardAnalyzer.Region board) {
        int width = board.width();
        int height = board.height();
        double left = board.left() + width * 0.039;
        double right = board.right() - width * 0.034;
        double top = board.top() + height * 0.033;
        double bottom = board.bottom() - height * 0.031;
        double dx = (right - left) / 18.0;
        double dy = (bottom - top) / 18.0;
        int radius = Math.max(2, (int) Math.round(Math.min(dx, dy) * 0.20));
        int[][] offsets = {
                {radius, radius}, {radius, -radius}, {-radius, radius}, {-radius, -radius},
                {2 * radius, radius}, {2 * radius, -radius},
                {-2 * radius, radius}, {-2 * radius, -radius},
                {radius, 2 * radius}, {-radius, 2 * radius},
                {radius, -2 * radius}, {-radius, -2 * radius}
        };

        byte[] cells = new byte[19 * 19];
        for (int y = 0; y < 19; y++) {
            for (int x = 0; x < 19; x++) {
                int centerX = (int) Math.round(left + x * dx);
                int centerY = (int) Math.round(top + y * dy);
                cells[y * 19 + x] = classify(pixels, centerX, centerY, offsets);
            }
        }
        return new BoardFrameFingerprint(cells);
    }

    private static byte classify(BoardAnalyzer.Pixels pixels, int centerX, int centerY,
                                 int[][] offsets) {
        int dark = 0;
        int white = 0;
        int count = 0;
        double luma = 0;
        double chroma = 0;
        for (int[] offset : offsets) {
            int x = centerX + offset[0];
            int y = centerY + offset[1];
            if (x < 0 || y < 0 || x >= pixels.width() || y >= pixels.height()) continue;
            int argb = pixels.argb(x, y);
            int red = (argb >> 16) & 255;
            int green = (argb >> 8) & 255;
            int blue = argb & 255;
            double luminosity = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
            int colorSpread = Math.max(red, Math.max(green, blue))
                    - Math.min(red, Math.min(green, blue));
            if (luminosity < 98) dark++;
            if (luminosity > 172 && colorSpread < 52) white++;
            luma += luminosity;
            chroma += colorSpread;
            count++;
        }
        if (count == 0) return BoardState.EMPTY;
        double meanLuma = luma / count;
        double meanChroma = chroma / count;
        if (dark >= 7 && meanLuma < 150) return BoardState.BLACK;
        if (white >= 6 && meanLuma > 148 && meanChroma < 58) return BoardState.WHITE;
        return BoardState.EMPTY;
    }

    @Override public boolean equals(Object other) {
        return other instanceof BoardFrameFingerprint fingerprint
                && Arrays.equals(cells, fingerprint.cells);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(cells);
    }
}
