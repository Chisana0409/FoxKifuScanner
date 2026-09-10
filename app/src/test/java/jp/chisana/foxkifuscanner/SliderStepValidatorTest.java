package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SliderStepValidatorTest {
    @Test public void acceptsOrdinaryMove() {
        SliderStepValidator.Result result = SliderStepValidator.validate(
                board(), board(stone(BoardState.BLACK, 3, 4)), BoardState.BLACK, null);

        assertTrue(result.valid());
        assertEquals(new Move(BoardState.BLACK, 3, 4, false), result.move());
        assertEquals("ok", result.reason());
    }

    @Test public void treatsUnchangedBoardAsPass() {
        BoardState position = board(stone(BoardState.BLACK, 3, 4));

        SliderStepValidator.Result result = SliderStepValidator.validate(
                position, position, BoardState.WHITE, null);

        assertTrue(result.valid());
        assertEquals(Move.pass(BoardState.WHITE), result.move());
        assertEquals("pass", result.reason());
    }

    @Test public void allowsConsecutivePassPositionDespiteTwoPlyMatch() {
        BoardState position = board(stone(BoardState.BLACK, 3, 4));

        SliderStepValidator.Result result = SliderStepValidator.validate(
                position, position, BoardState.BLACK, position);

        assertTrue(result.valid());
        assertEquals(Move.pass(BoardState.BLACK), result.move());
        assertEquals("pass", result.reason());
    }

    @Test public void acceptsCompleteCapture() {
        BoardState before = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1));
        BoardState observed = board(
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1),
                stone(BoardState.BLACK, 1, 2));

        SliderStepValidator.Result result = SliderStepValidator.validate(
                before, observed, BoardState.BLACK, null);

        assertTrue(result.valid());
        assertEquals(new Move(BoardState.BLACK, 1, 2, false), result.move());
    }

    @Test public void rejectsPartiallyRenderedCapture() {
        BoardState before = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1));
        BoardState partial = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1),
                stone(BoardState.BLACK, 1, 2));

        SliderStepValidator.Result result = SliderStepValidator.validate(
                before, partial, BoardState.BLACK, null);

        assertFalse(result.valid());
        assertNotNull(result.move());
        assertEquals("board transition mismatch", result.reason());
    }

    @Test public void rejectsWrongTurnColor() {
        SliderStepValidator.Result result = SliderStepValidator.validate(
                board(), board(stone(BoardState.WHITE, 3, 4)), BoardState.BLACK, null);

        assertFalse(result.valid());
        assertTrue(result.reason().contains("unexpected stone color"));
    }

    @Test public void rejectsTwoMovesSkippedBySlider() {
        SliderStepValidator.Result result = SliderStepValidator.validate(
                board(),
                board(stone(BoardState.BLACK, 3, 4), stone(BoardState.WHITE, 10, 10)),
                BoardState.BLACK,
                null);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("move detection failed:"));
    }

    @Test public void rejectsImmediateKoRecapture() {
        BoardState twoPliesAgo = board(
                stone(BoardState.WHITE, 1, 1),
                stone(BoardState.WHITE, 2, 0),
                stone(BoardState.WHITE, 3, 1),
                stone(BoardState.WHITE, 2, 2),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 1, 2));
        BoardState beforeRecapture = board(
                stone(BoardState.WHITE, 2, 0),
                stone(BoardState.WHITE, 3, 1),
                stone(BoardState.WHITE, 2, 2),
                stone(BoardState.BLACK, 0, 1),
                stone(BoardState.BLACK, 1, 0),
                stone(BoardState.BLACK, 2, 1),
                stone(BoardState.BLACK, 1, 2));

        SliderStepValidator.Result result = SliderStepValidator.validate(
                beforeRecapture, twoPliesAgo, BoardState.WHITE, twoPliesAgo);

        assertFalse(result.valid());
        assertEquals("immediate ko", result.reason());
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
