package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class BoardFrameFingerprintTest {
    private static final BoardAnalyzer.Region BOARD = new BoardAnalyzer.Region(0, 0, 1000, 1000);

    @Test public void identicalFramesHaveIdenticalFingerprints() {
        BoardFrameFingerprint first = BoardFrameFingerprint.capture(
                new SyntheticPixels(3, 3, BoardState.BLACK), BOARD);
        BoardFrameFingerprint second = BoardFrameFingerprint.capture(
                new SyntheticPixels(3, 3, BoardState.BLACK), BOARD);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test public void oneNewStoneChangesFingerprint() {
        BoardFrameFingerprint empty = BoardFrameFingerprint.capture(
                new SyntheticPixels(-1, -1, BoardState.EMPTY), BOARD);
        BoardFrameFingerprint played = BoardFrameFingerprint.capture(
                new SyntheticPixels(15, 15, BoardState.WHITE), BOARD);
        assertNotEquals(empty, played);
    }

    private static final class SyntheticPixels implements BoardAnalyzer.Pixels {
        private final int stoneX;
        private final int stoneY;
        private final byte color;

        SyntheticPixels(int stoneX, int stoneY, byte color) {
            this.stoneX = stoneX;
            this.stoneY = stoneY;
            this.color = color;
        }

        @Override public int width() { return 1000; }

        @Override public int height() { return 1000; }

        @Override public int argb(int x, int y) {
            double left = 39.0;
            double top = 33.0;
            double dx = (966.0 - left) / 18.0;
            double dy = (969.0 - top) / 18.0;
            double centerX = left + stoneX * dx;
            double centerY = top + stoneY * dy;
            double distance = Math.hypot(x - centerX, y - centerY);
            if (stoneX >= 0 && distance <= 28) {
                return color == BoardState.BLACK ? 0xFF161616 : 0xFFF2F2F2;
            }
            return 0xFFD9AE69;
        }
    }
}
