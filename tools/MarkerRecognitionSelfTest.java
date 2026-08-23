import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import jp.chisana.foxkifuscanner.BoardAnalyzer;
import jp.chisana.foxkifuscanner.BoardState;
import jp.chisana.foxkifuscanner.MoveDetector;

public final class MarkerRecognitionSelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("marked-board image required");
        BufferedImage image = ImageIO.read(new File(args[0]));
        BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
            public int width() { return image.getWidth(); }
            public int height() { return image.getHeight(); }
            public int argb(int x, int y) { return image.getRGB(x, y); }
        };
        BoardState state = BoardAnalyzer.detect(pixels).state();
        if (state.at(3, 15) != BoardState.WHITE) {
            throw new AssertionError("white stone with black last-move marker was missed");
        }
        if (state.at(15, 3) != BoardState.BLACK) {
            throw new AssertionError("black stone was missed");
        }
        if (state.count(BoardState.BLACK) != 1 || state.count(BoardState.WHITE) != 1) {
            throw new AssertionError("unexpected stones in two-move fixture");
        }
        byte[] oneMove = new byte[361];
        oneMove[3 * 19 + 15] = BoardState.BLACK;
        MoveDetector.Result secondMove = MoveDetector.between(
                new BoardState(oneMove), state, BoardState.WHITE);
        if (!secondMove.legal() || secondMove.move().x() != 3 || secondMove.move().y() != 15) {
            throw new AssertionError("marked white move difference was not recognized");
        }
        System.out.println("MarkerRecognitionSelfTest: marked white second move passed");
    }
}
