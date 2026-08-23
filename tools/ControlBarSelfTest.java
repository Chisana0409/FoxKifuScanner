import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import jp.chisana.foxkifuscanner.BoardAnalyzer;
import jp.chisana.foxkifuscanner.ControlBarDetector;

public final class ControlBarSelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("image paths required");
        for (String path : args) {
            BufferedImage image = ImageIO.read(new File(path));
            BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
                public int width() { return image.getWidth(); }
                public int height() { return image.getHeight(); }
                public int argb(int x, int y) { return image.getRGB(x, y); }
            };
            BoardAnalyzer.Region board = BoardAnalyzer.detect(pixels).board();
            ControlBarDetector.Result control;
            try {
                control = ControlBarDetector.detect(pixels, board);
            } catch (IllegalStateException error) {
                if (new File(path).getName().contains("2531")) {
                    System.out.printf("%s modal=true gesture=blocked%n", new File(path).getName());
                    continue;
                }
                throw error;
            }
            require(control.y() >= control.safeTop(), "control above protected strip");
            require(control.y() <= control.safeBottom(), "control below protected strip");
            require(control.y() > board.bottom() + (image.getHeight() - board.bottom()) * .60,
                    "control too close to ads");
            ControlBarDetector.Thumb thumb = ControlBarDetector.detectThumb(pixels, control.y());
            require(thumb.centerX() >= 0, "slider thumb not detected");
            int legacyY = legacyControlY(pixels);
            if (new File(path).getName().contains("2533")) {
                require(legacyY < control.safeTop(), "bug fixture no longer reproduces legacy ad hit");
            }
            System.out.printf("%s controlY=%d thumbX=%d legacyY=%d safe=%d..%d trackRun=%d%n",
                    new File(path).getName(), control.y(), thumb.centerX(), legacyY, control.safeTop(),
                    control.safeBottom(), control.trackRun());
        }
    }

    private static int legacyControlY(BoardAnalyzer.Pixels pixels) {
        int width = pixels.width(), height = pixels.height();
        int bestY = (int) (height * .929), best = 0;
        for (int y = (int) (height * .82); y < height * .97; y += 2) {
            int score = 0;
            for (int x = (int) (width * .04); x < width * .72; x += 2) {
                int color = pixels.argb(x, y);
                int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
                if (blue > red + 18 && blue > green + 4 && red < 120) score++;
            }
            if (score > best) { best = score; bestY = y; }
        }
        return bestY;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
