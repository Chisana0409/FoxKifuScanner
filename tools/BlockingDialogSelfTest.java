import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import jp.chisana.foxkifuscanner.BlockingDialogDetector;
import jp.chisana.foxkifuscanner.BoardAnalyzer;

public final class BlockingDialogSelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("image paths required");
        if (!BlockingDialogDetector.isPointShortageText(
                "絶芸ポイントが不足しています。 取得しに行きますか？")) {
            throw new AssertionError("dialog text classification failed");
        }
        if (BlockingDialogDetector.isPointShortageText("すでに最後の一手です")) {
            throw new AssertionError("dialog text false positive");
        }
        for (String path : args) {
            BufferedImage image = ImageIO.read(new File(path));
            BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
                public int width() { return image.getWidth(); }
                public int height() { return image.getHeight(); }
                public int argb(int x, int y) { return image.getRGB(x, y); }
            };
            BoardAnalyzer.Region board = BoardAnalyzer.detect(pixels).board();
            boolean detected = BlockingDialogDetector.hasDialog(pixels, board);
            boolean expected = new File(path).getName().contains("2531");
            if (detected != expected) throw new AssertionError("dialog classification failed: " + path);
            System.out.printf("%s dialog=%s%n", new File(path).getName(), detected);
        }
    }
}
