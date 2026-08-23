package jp.chisana.foxkifuscanner;

import java.util.ArrayList;
import java.util.List;

public final class MoveDetector {
    public record Result(Move move, boolean legal, String reason) {}

    private MoveDetector() {}

    public static Result between(BoardState before, BoardState after, byte expectedColor) {
        List<Integer> added = new ArrayList<>();
        for (int y = 0; y < 19; y++) {
            for (int x = 0; x < 19; x++) {
                byte a = before.at(x, y), b = after.at(x, y);
                if (a == BoardState.EMPTY && b != BoardState.EMPTY) {
                    if (b != expectedColor) return new Result(null, false, "unexpected stone color");
                    added.add(y * 19 + x);
                } else if (a != BoardState.EMPTY && b == BoardState.EMPTY) {
                    if (a == expectedColor) return new Result(null, false, "own stone disappeared");
                } else if (a != b) {
                    return new Result(null, false, "stone changed color");
                }
            }
        }
        if (added.size() != 1) return new Result(null, false, "added stones=" + added.size());
        int p = added.get(0);
        return new Result(new Move(expectedColor, p % 19, p / 19, false), true, "ok");
    }
}
