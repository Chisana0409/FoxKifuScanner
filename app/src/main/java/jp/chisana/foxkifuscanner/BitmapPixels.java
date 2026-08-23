package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;

public final class BitmapPixels implements BoardAnalyzer.Pixels {
    private final Bitmap bitmap;
    public BitmapPixels(Bitmap bitmap) { this.bitmap = bitmap; }
    @Override public int width() { return bitmap.getWidth(); }
    @Override public int height() { return bitmap.getHeight(); }
    @Override public int argb(int x, int y) { return bitmap.getPixel(x, y); }
}
