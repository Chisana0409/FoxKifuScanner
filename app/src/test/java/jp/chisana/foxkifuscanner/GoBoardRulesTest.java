package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class GoBoardRulesTest {
    @Test public void appliesOrdinaryMove() {
        BoardState before = board();
        BoardState after = GoBoardRules.apply(before,
                new Move(BoardState.BLACK, 3, 4, false));

        assertEquals(BoardState.BLACK, after.at(3, 4));
    }

    @Test public void removesCapturedGroupBeforeFastAcceptance() {
        BoardState before = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1));

        BoardState after = GoBoardRules.apply(before,
                new Move(BoardState.BLACK, 1, 2, false));

        assertEquals(BoardState.EMPTY, after.at(1, 1));
        assertEquals(BoardState.BLACK, after.at(1, 2));
    }

    @Test public void rejectsSuicide() {
        BoardState before = board(
                stone(BoardState.WHITE, 0, 1),
                stone(BoardState.WHITE, 1, 0),
                stone(BoardState.WHITE, 2, 1),
                stone(BoardState.WHITE, 1, 2));

        assertNull(GoBoardRules.apply(before,
                new Move(BoardState.BLACK, 1, 1, false)));
    }

    @Test public void removesCapturedChain() {
        BoardState before = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.WHITE, 1, 2),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1),
                stone(BoardState.BLACK, 0, 2),
                stone(BoardState.BLACK, 1, 3));

        BoardState after = GoBoardRules.apply(before,
                new Move(BoardState.BLACK, 2, 2, false));

        assertNotNull(after);
        assertEquals(BoardState.EMPTY, after.at(1, 1));
        assertEquals(BoardState.EMPTY, after.at(1, 2));
    }

    @Test public void removesTwoGroupsWithOneMove() {
        BoardState before = board(
                stone(BoardState.WHITE, 0, 1),
                stone(BoardState.WHITE, 1, 0),
                stone(BoardState.BLACK, 0, 0),
                stone(BoardState.BLACK, 0, 2),
                stone(BoardState.BLACK, 2, 0));

        BoardState after = GoBoardRules.apply(before,
                new Move(BoardState.BLACK, 1, 1, false));

        assertNotNull(after);
        assertEquals(BoardState.EMPTY, after.at(0, 1));
        assertEquals(BoardState.EMPTY, after.at(1, 0));
    }

    @Test public void captureCreatesLibertyForPlayedStone() {
        BoardState before = board(
                stone(BoardState.WHITE, 1, 0),
                stone(BoardState.BLACK, 2, 0),
                stone(BoardState.BLACK, 1, 1));

        BoardState after = GoBoardRules.apply(before,
                new Move(BoardState.BLACK, 0, 0, false));

        assertNotNull(after);
        assertEquals(BoardState.BLACK, after.at(0, 0));
        assertEquals(BoardState.EMPTY, after.at(1, 0));
    }

    @Test public void partialCaptureFrameDoesNotMatchRuleResult() {
        BoardState before = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1));
        Move move = new Move(BoardState.BLACK, 1, 2, false);
        BoardState partialRender = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1),
                stone(BoardState.BLACK, 1, 2));

        assertNotEquals(partialRender, GoBoardRules.apply(before, move));
    }

    private static BoardState board(Stone... stones) {
        byte[] cells = new byte[361];
        for (Stone stone : stones) cells[stone.y * 19 + stone.x] = stone.color;
        return new BoardState(cells);
    }

    private static Stone stone(byte color, int x, int y) {
        return new Stone(color, x, y);
    }

    private record Stone(byte color, int x, int y) {}
}
