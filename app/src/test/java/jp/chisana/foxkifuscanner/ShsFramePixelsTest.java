package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class ShsFramePixelsTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 400;
    private static final int CONTROL_Y = 360;
    private static final BoardAnalyzer.Region BOARD =
            new BoardAnalyzer.Region(10, 20, 190, 200);

    @Test public void bulkCachePreservesBoardAndThumbResultsWithoutFallbackReads() {
        FakeSource source = new FakeSource();
        BoardAnalyzer.Detection expectedBoard = BoardAnalyzer.detect(source, BOARD);
        ControlBarDetector.Thumb expectedThumb =
                ControlBarDetector.detectThumb(source, CONTROL_Y);
        source.resetCounts();

        ShsFramePixels cache = new ShsFramePixels();
        cache.bind(source, BOARD, CONTROL_Y);
        BoardAnalyzer.Detection actualBoard = BoardAnalyzer.detect(cache, BOARD);
        ControlBarDetector.Thumb actualThumb =
                ControlBarDetector.detectThumb(cache, CONTROL_Y);

        assertEquals(expectedBoard.board(), actualBoard.board());
        assertEquals(expectedBoard.state(), actualBoard.state());
        assertEquals(expectedBoard.confidence(), actualBoard.confidence(), 0.0);
        assertEquals(expectedThumb, actualThumb);
        assertEquals(2, source.bulkReads);
        assertEquals(0, source.fallbackReads);

        int outside = cache.argb(0, 300);
        assertEquals(source.rawArgb(0, 300), outside);
        assertEquals(1, source.fallbackReads);
    }

    @Test public void arraysAreReusedAcrossFramesAndReleasedAtSessionEnd() {
        FakeSource source = new FakeSource();
        ShsFramePixels cache = new ShsFramePixels();

        cache.bind(source, BOARD, CONTROL_Y);
        int[] firstBoard = source.destinations.get(0);
        int[] firstThumb = source.destinations.get(1);
        int retained = cache.retainedPixelCapacity();
        cache.releaseFrame();

        cache.bind(source, BOARD, CONTROL_Y);
        assertSame(firstBoard, source.destinations.get(2));
        assertSame(firstThumb, source.destinations.get(3));
        assertEquals(retained, cache.retainedPixelCapacity());

        cache.release();
        assertEquals(0, cache.retainedPixelCapacity());
    }

    @Test(expected = IllegalStateException.class)
    public void releasedFrameCannotReadRecycledSource() {
        ShsFramePixels cache = new ShsFramePixels();
        cache.bind(new FakeSource(), BOARD, CONTROL_Y);
        cache.releaseFrame();
        cache.argb(BOARD.left(), BOARD.top());
    }

    @Test public void thumbSampleRegionKeepsOriginalInclusiveCoordinates() {
        BoardAnalyzer.Region region = ControlBarDetector.thumbSampleRegion(
                WIDTH, HEIGHT, CONTROL_Y);
        assertEquals((int) Math.round(WIDTH * 0.02), region.left());
        assertEquals((int) Math.round(WIDTH * 0.50) + 1, region.right());
        int radius = Math.max(12, (int) Math.round(HEIGHT * 0.020));
        assertEquals(Math.max(0, CONTROL_Y - radius), region.top());
        assertEquals(Math.min(HEIGHT - 1, CONTROL_Y + radius) + 1, region.bottom());
        assertTrue(region.width() > 0);
        assertTrue(region.height() > 0);
    }

    private static final class FakeSource implements ShsFramePixels.Source {
        final List<int[]> destinations = new ArrayList<>();
        int bulkReads;
        int fallbackReads;

        @Override public int width() { return WIDTH; }
        @Override public int height() { return HEIGHT; }

        @Override public int argb(int x, int y) {
            fallbackReads++;
            return rawArgb(x, y);
        }

        @Override public void getPixels(int[] destination, int offset, int stride,
                                        int x, int y, int width, int height) {
            bulkReads++;
            destinations.add(destination);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    destination[offset + row * stride + column] = rawArgb(
                            x + column, y + row);
                }
            }
        }

        int rawArgb(int x, int y) {
            if (x >= 77 && x <= 83 && y >= CONTROL_Y - 12 && y <= CONTROL_Y + 12) {
                return 0xFF183C88;
            }
            return 0xFFD9AE69;
        }

        void resetCounts() {
            bulkReads = 0;
            fallbackReads = 0;
            destinations.clear();
        }
    }
}
