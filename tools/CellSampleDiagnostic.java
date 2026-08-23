import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import jp.chisana.foxkifuscanner.BoardAnalyzer;

public final class CellSampleDiagnostic {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("image x y");
        BufferedImage image = ImageIO.read(new File(args[0]));
        BoardAnalyzer.Pixels pixels = new BoardAnalyzer.Pixels() {
            public int width() { return image.getWidth(); }
            public int height() { return image.getHeight(); }
            public int argb(int x, int y) { return image.getRGB(x, y); }
        };
        BoardAnalyzer.Region board = BoardAnalyzer.detect(pixels).board();
        int gx = Integer.parseInt(args[1]), gy = Integer.parseInt(args[2]);
        double left = board.left() + board.width() * .039;
        double right = board.right() - board.width() * .034;
        double top = board.top() + board.height() * .033;
        double bottom = board.bottom() - board.height() * .031;
        double dx = (right - left) / 18.0, dy = (bottom - top) / 18.0;
        int radius = Math.max(3, (int) Math.round(Math.min(dx, dy) * .34));
        int cx = (int) Math.round(left + gx * dx), cy = (int) Math.round(top + gy * dy);
        int black = 0, white = 0, count = 0; double luma = 0, chroma = 0;
        for (int yy=-radius; yy<=radius; yy++) for (int xx=-radius; xx<=radius; xx++) {
            if (xx*xx+yy*yy>radius*radius) continue;
            int color=image.getRGB(cx+xx,cy+yy), r=(color>>16)&255,g=(color>>8)&255,b=color&255;
            double lum=.2126*r+.7152*g+.0722*b;
            int chr=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b));
            if(lum<92) black++; if(lum>176&&chr<48) white++;
            luma+=lum;chroma+=chr;count++;
        }
        System.out.printf("cell=%d,%d pixel=%d,%d radius=%d black=%.3f white=%.3f luma=%.1f chroma=%.1f%n",
                gx,gy,cx,cy,radius,black/(double)count,white/(double)count,luma/count,chroma/count);
    }
}
