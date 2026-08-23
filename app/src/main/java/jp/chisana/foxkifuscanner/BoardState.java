package jp.chisana.foxkifuscanner;

import java.util.Arrays;

public final class BoardState {
    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;
    private final byte[] cells;

    public BoardState(byte[] values) {
        if (values.length != 361) throw new IllegalArgumentException("19x19 required");
        cells = values.clone();
    }

    public byte at(int x, int y) { return cells[y * 19 + x]; }
    public byte[] copyCells() { return cells.clone(); }
    public int count(byte color) {
        int n = 0;
        for (byte cell : cells) if (cell == color) n++;
        return n;
    }

    @Override public boolean equals(Object value) {
        return value instanceof BoardState && Arrays.equals(cells, ((BoardState) value).cells);
    }
    @Override public int hashCode() { return Arrays.hashCode(cells); }
}
