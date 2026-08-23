import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import jp.chisana.foxkifuscanner.BoardAnalyzer;
import jp.chisana.foxkifuscanner.BoardState;
import jp.chisana.foxkifuscanner.TerminalBannerDetector;

public final class ImageBoardSelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("image paths required");
        for (String path : args) {
            BufferedImage image = ImageIO.read(new File(path));
            BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
                public int width() { return image.getWidth(); }
                public int height() { return image.getHeight(); }
                public int argb(int x, int y) { return image.getRGB(x, y); }
            };
            BoardAnalyzer.Detection d = BoardAnalyzer.detect(pixels);
            if (d.board().width() < image.getWidth() * .8) throw new AssertionError("board too narrow");
            int black = d.state().count(BoardState.BLACK), white = d.state().count(BoardState.WHITE);
            String filename = new File(path).getName();
            boolean reference = filename.startsWith("01-") || filename.startsWith("02-") || filename.startsWith("03-");
            if (reference && (black < 10 || white < 10)) throw new AssertionError("stone classification failed");
            boolean terminalBanner = TerminalBannerDetector.hasBanner(pixels, d.board());
            boolean expectedBanner = filename.startsWith("03-");
            if (terminalBanner != expectedBanner) throw new AssertionError("terminal banner classification failed");
            System.out.printf("%s board=%s B=%d W=%d confidence=%.3f terminal=%s%n",
                    new File(path).getName(), d.board(), black, white, d.confidence(), terminalBanner);
        }
    }
}
