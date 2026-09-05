package jp.chisana.foxkifuscanner;

import java.util.ArrayDeque;

/** Minimal board transition rules used to reject partially rendered capture frames. */
final class GoBoardRules {
    private static final int SIZE = 19;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DY = {0, 0, 1, -1};

    private GoBoardRules() {}

    static BoardState apply(BoardState before, Move move) {
        if (before == null || move == null || move.pass()) return null;
        if (!inside(move.x(), move.y()) || before.at(move.x(), move.y()) != BoardState.EMPTY) {
            return null;
        }
        byte color = move.color();
        if (color != BoardState.BLACK && color != BoardState.WHITE) return null;

        byte opponent = color == BoardState.BLACK ? BoardState.WHITE : BoardState.BLACK;
        byte[] cells = before.copyCells();
        cells[index(move.x(), move.y())] = color;

        boolean[] checkedOpponent = new boolean[cells.length];
        for (int direction = 0; direction < DX.length; direction++) {
            int x = move.x() + DX[direction];
            int y = move.y() + DY[direction];
            if (!inside(x, y) || cells[index(x, y)] != opponent
                    || checkedOpponent[index(x, y)]) continue;
            Group group = group(cells, x, y, checkedOpponent);
            if (!group.hasLiberty) {
                for (int point : group.points) cells[point] = BoardState.EMPTY;
            }
        }

        Group own = group(cells, move.x(), move.y(), new boolean[cells.length]);
        if (!own.hasLiberty) return null;
        return new BoardState(cells);
    }

    private static Group group(byte[] cells, int startX, int startY, boolean[] visited) {
        byte color = cells[index(startX, startY)];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        ArrayDeque<Integer> points = new ArrayDeque<>();
        int start = index(startX, startY);
        queue.add(start);
        visited[start] = true;
        boolean liberty = false;

        while (!queue.isEmpty()) {
            int point = queue.removeFirst();
            points.add(point);
            int x = point % SIZE;
            int y = point / SIZE;
            for (int direction = 0; direction < DX.length; direction++) {
                int nextX = x + DX[direction];
                int nextY = y + DY[direction];
                if (!inside(nextX, nextY)) continue;
                int next = index(nextX, nextY);
                if (cells[next] == BoardState.EMPTY) {
                    liberty = true;
                } else if (cells[next] == color && !visited[next]) {
                    visited[next] = true;
                    queue.add(next);
                }
            }
        }
        return new Group(points.stream().mapToInt(Integer::intValue).toArray(), liberty);
    }

    private static int index(int x, int y) {
        return y * SIZE + x;
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
    }

    private record Group(int[] points, boolean hasLiberty) {}
}
