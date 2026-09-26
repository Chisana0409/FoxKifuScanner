package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TabletBoardDetectionTest {
    @Test public void detectsInsetTabletBoardAcrossShortGridLineGaps() {
        BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
            @Override public int width() { return 800; }
            @Override public int height() { return 1280; }

            @Override public int argb(int x, int y) {
                boolean inside = x >= 80 && x < 720 && y >= 180 && y < 820;
                if (!inside) return 0xffeeeeee;
                boolean grid = Math.floorMod(x - 105, 33) <= 1
                        || Math.floorMod(y - 200, 33) <= 1;
                return grid ? 0xff6b4b28 : 0xffd9aa64;
            }
        };

        BoardAnalyzer.Detection detection = BoardAnalyzer.detect(pixels);
        assertTrue(detection.board().left() >= 75 && detection.board().left() <= 90);
        assertTrue(detection.board().right() >= 710 && detection.board().right() <= 725);
        assertTrue(detection.board().top() >= 175 && detection.board().top() <= 195);
        assertTrue(detection.board().bottom() >= 810 && detection.board().bottom() <= 825);
        assertEquals(0, detection.state().count(BoardState.BLACK));
        assertEquals(0, detection.state().count(BoardState.WHITE));
    }

    @Test public void detectsInsetBoardWhenStonesObscureSeveralRows() {
        BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
            @Override public int width() { return 800; }
            @Override public int height() { return 1280; }

            @Override public int argb(int x, int y) {
                boolean inside = x >= 80 && x < 720 && y >= 180 && y < 820;
                if (!inside) return 0xffeeeeee;
                boolean obscured = (y >= 290 && y < 312)
                        || (y >= 520 && y < 548)
                        || (y >= 680 && y < 706);
                return obscured ? 0xff202020 : 0xffd9aa64;
            }
        };

        BoardAnalyzer.Region board = BoardAnalyzer.findWoodBoard(pixels);
        assertTrue(board.left() >= 75 && board.left() <= 90);
        assertTrue(board.right() >= 710 && board.right() <= 725);
        assertTrue(board.top() >= 175 && board.top() <= 195);
        assertTrue(board.bottom() >= 810 && board.bottom() <= 825);
    }
}
