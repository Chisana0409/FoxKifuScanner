package jp.chisana.foxkifuscanner;

public record Move(byte color, int x, int y, boolean pass) {
    public static Move pass(byte color) { return new Move(color, -1, -1, true); }
}
