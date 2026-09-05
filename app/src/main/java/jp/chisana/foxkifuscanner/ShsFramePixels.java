package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;

import java.util.Objects;

/**
 * Reusable pixel cache for one SHS frame.
 *
 * <p>The board and the exact strip read by {@link ControlBarDetector#detectThumb} are copied with
 * two bulk {@link Bitmap#getPixels} calls. Coordinates outside those two regions deliberately
 * fall back to the source bitmap so callers observe the same full-screen coordinate system as
 * {@link BitmapPixels}.</p>
 *
 * <p>This class is worker-thread confined. Call {@link #releaseFrame()} before recycling the
 * bound bitmap, and {@link #release()} when the SHS session ends so the large arrays can be
 * collected.</p>
 */
final class ShsFramePixels implements BoardAnalyzer.Pixels {
    interface Source extends BoardAnalyzer.Pixels {
        void getPixels(int[] destination, int offset, int stride,
                       int x, int y, int width, int height);
    }

    private static final class BitmapSource implements Source {
        private final Bitmap bitmap;

        BitmapSource(Bitmap bitmap) {
            this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
        }

        @Override public int width() { return bitmap.getWidth(); }
        @Override public int height() { return bitmap.getHeight(); }
        @Override public int argb(int x, int y) { return bitmap.getPixel(x, y); }

        @Override public void getPixels(int[] destination, int offset, int stride,
                                        int x, int y, int width, int height) {
            bitmap.getPixels(destination, offset, stride, x, y, width, height);
        }
    }

    private Source source;
    private int screenWidth;
    private int screenHeight;

    private int[] boardPixels;
    private int boardLeft;
    private int boardTop;
    private int boardWidth;
    private int boardHeight;

    private int[] thumbPixels;
    private int thumbLeft;
    private int thumbTop;
    private int thumbWidth;
    private int thumbHeight;

    void bind(Bitmap bitmap, BoardAnalyzer.Region board, int controlY) {
        bind(new BitmapSource(bitmap), board, controlY);
    }

    void bind(Source nextSource, BoardAnalyzer.Region board, int controlY) {
        Objects.requireNonNull(nextSource, "source");
        Objects.requireNonNull(board, "board");
        releaseFrame();

        int width = nextSource.width();
        int height = nextSource.height();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }

        source = nextSource;
        screenWidth = width;
        screenHeight = height;
        try {
            boardLeft = clamp(board.left(), 0, width);
            boardTop = clamp(board.top(), 0, height);
            int boardRight = clamp(board.right(), boardLeft, width);
            int boardBottom = clamp(board.bottom(), boardTop, height);
            boardWidth = boardRight - boardLeft;
            boardHeight = boardBottom - boardTop;
            boardPixels = ensureCapacity(boardPixels, area(boardWidth, boardHeight));
            if (boardWidth > 0 && boardHeight > 0) {
                source.getPixels(boardPixels, 0, boardWidth,
                        boardLeft, boardTop, boardWidth, boardHeight);
            }

            BoardAnalyzer.Region thumb = ControlBarDetector.thumbSampleRegion(
                    width, height, controlY);
            thumbLeft = thumb.left();
            thumbTop = thumb.top();
            thumbWidth = thumb.width();
            thumbHeight = thumb.height();
            thumbPixels = ensureCapacity(thumbPixels, area(thumbWidth, thumbHeight));
            if (thumbWidth > 0 && thumbHeight > 0) {
                source.getPixels(thumbPixels, 0, thumbWidth,
                        thumbLeft, thumbTop, thumbWidth, thumbHeight);
            }
        } catch (RuntimeException | Error error) {
            release();
            throw error;
        }
    }

    @Override public int width() {
        requireBound();
        return screenWidth;
    }

    @Override public int height() {
        requireBound();
        return screenHeight;
    }

    @Override public int argb(int x, int y) {
        Source active = requireBound();
        if (contains(x, y, boardLeft, boardTop, boardWidth, boardHeight)) {
            return boardPixels[(y - boardTop) * boardWidth + x - boardLeft];
        }
        if (contains(x, y, thumbLeft, thumbTop, thumbWidth, thumbHeight)) {
            return thumbPixels[(y - thumbTop) * thumbWidth + x - thumbLeft];
        }
        return active.argb(x, y);
    }

    void releaseFrame() {
        source = null;
        screenWidth = 0;
        screenHeight = 0;
        boardLeft = 0;
        boardTop = 0;
        boardWidth = 0;
        boardHeight = 0;
        thumbLeft = 0;
        thumbTop = 0;
        thumbWidth = 0;
        thumbHeight = 0;
    }

    void release() {
        releaseFrame();
        boardPixels = null;
        thumbPixels = null;
    }

    int retainedPixelCapacity() {
        return (boardPixels == null ? 0 : boardPixels.length)
                + (thumbPixels == null ? 0 : thumbPixels.length);
    }

    private Source requireBound() {
        if (source == null) throw new IllegalStateException("no SHS frame is bound");
        return source;
    }

    private static boolean contains(int x, int y, int left, int top, int width, int height) {
        return width > 0 && height > 0
                && x >= left && x < left + width
                && y >= top && y < top + height;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int area(int width, int height) {
        long area = (long) width * height;
        if (area > Integer.MAX_VALUE) throw new IllegalArgumentException("pixel region is too large");
        return (int) area;
    }

    private static int[] ensureCapacity(int[] pixels, int required) {
        if (pixels == null || pixels.length < required) return new int[required];
        return pixels;
    }
}
