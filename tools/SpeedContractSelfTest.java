import jp.chisana.foxkifuscanner.BoardAnalyzer;
import jp.chisana.foxkifuscanner.BoardFrameFingerprint;
import jp.chisana.foxkifuscanner.BoardState;

public final class SpeedContractSelfTest {
    public static void main(String[] args) {
        BoardAnalyzer.Region board = new BoardAnalyzer.Region(0, 0, 1000, 1000);
        BoardFrameFingerprint empty = BoardFrameFingerprint.capture(new Pixels(-1, -1), board);
        BoardFrameFingerprint sameEmpty = BoardFrameFingerprint.capture(new Pixels(-1, -1), board);
        BoardFrameFingerprint blackMove = BoardFrameFingerprint.capture(new Pixels(3, 3), board);
        require(empty.equals(sameEmpty), "stable frames");
        require(empty.hashCode() == sameEmpty.hashCode(), "stable hash");
        require(!empty.equals(blackMove), "stone change");
        System.out.println("SpeedContractSelfTest: all checks passed");
    }

    private record Pixels(int stoneX, int stoneY) implements BoardAnalyzer.Pixels {
        @Override public int width() { return 1000; }
        @Override public int height() { return 1000; }
        @Override public int argb(int x, int y) {
            double centerX = 39.0 + stoneX * ((966.0 - 39.0) / 18.0);
            double centerY = 33.0 + stoneY * ((969.0 - 33.0) / 18.0);
            if (stoneX >= 0 && Math.hypot(x - centerX, y - centerY) <= 28) {
                return 0xFF161616;
            }
            return 0xFFD9AE69;
        }
    }

    private static void require(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
