package jp.chisana.foxkifuscanner;

/**
 * Validates the board observed after seeking a game slider by exactly one move.
 *
 * <p>The validator deliberately accepts only complete, rule-correct transitions. This keeps
 * partially rendered captures and slider jumps from entering the SGF move list.</p>
 */
public final class SliderStepValidator {
    public record Result(boolean valid, Move move, String reason) {}

    private SliderStepValidator() {}

    public static Result validate(BoardState before, BoardState observed, byte expectedColor,
                                  BoardState twoPliesAgo) {
        if (before == null || observed == null) {
            return invalid(null, "missing board");
        }
        if (expectedColor != BoardState.BLACK && expectedColor != BoardState.WHITE) {
            return invalid(null, "invalid expected color");
        }

        if (observed.equals(before)) {
            return new Result(true, Move.pass(expectedColor), "pass");
        }
        // Ko applies only to a stone-playing transition. Checking unchanged positions here
        // allows consecutive passes even when the position also equals the one two plies ago.
        if (twoPliesAgo != null && observed.equals(twoPliesAgo)) {
            return invalid(null, "immediate ko");
        }

        MoveDetector.Result detected = MoveDetector.between(before, observed, expectedColor);
        if (!detected.legal() || detected.move() == null) {
            return invalid(detected.move(), "move detection failed: " + detected.reason());
        }

        BoardState expectedBoard = GoBoardRules.apply(before, detected.move());
        if (expectedBoard == null) {
            return invalid(detected.move(), "illegal move");
        }
        if (!expectedBoard.equals(observed)) {
            return invalid(detected.move(), "board transition mismatch");
        }
        return new Result(true, detected.move(), "ok");
    }

    private static Result invalid(Move move, String reason) {
        return new Result(false, move, reason);
    }
}
