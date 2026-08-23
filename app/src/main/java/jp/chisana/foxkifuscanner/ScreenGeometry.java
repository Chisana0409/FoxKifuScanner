package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

public final class ScreenGeometry {
    public final BoardAnalyzer.Region board;
    public final PointF back;
    public final PointF forward;
    public final PointF sliderLeft;
    public final PointF sliderRight;
    private final int screenWidth;
    private final int safeTop;
    private final int safeBottom;

    private ScreenGeometry(BoardAnalyzer.Region board, PointF back, PointF forward,
                           PointF sliderLeft, PointF sliderRight, int screenWidth,
                           int safeTop, int safeBottom) {
        this.board = board; this.back = back; this.forward = forward;
        this.sliderLeft = sliderLeft; this.sliderRight = sliderRight;
        this.screenWidth = screenWidth; this.safeTop = safeTop; this.safeBottom = safeBottom;
    }

    public static ScreenGeometry detect(Bitmap bitmap, BoardAnalyzer.Region board) {
        int w = bitmap.getWidth();
        ControlBarDetector.Result control = ControlBarDetector.detect(new BitmapPixels(bitmap), board);
        float y = control.y();
        ScreenGeometry geometry = new ScreenGeometry(board,
                new PointF(w * 0.557f, y), new PointF(w * 0.660f, y),
                new PointF(w * 0.070f, y), new PointF(w * 0.470f, y),
                w, control.safeTop(), control.safeBottom());
        geometry.requireSafe(geometry.back);
        geometry.requireSafe(geometry.forward);
        geometry.requireSafe(geometry.sliderLeft);
        geometry.requireSafe(geometry.sliderRight);
        return geometry;
    }

    public void requireSafe(PointF point) {
        if (point == null || point.x < screenWidth * 0.02f || point.x > screenWidth * 0.75f
                || point.y < safeTop || point.y > safeBottom) {
            throw new IllegalStateException("広告を避けるため画面操作を中止しました：操作座標が安全領域外です");
        }
    }

    public PointF sliderThumb(Bitmap bitmap) {
        ControlBarDetector.Thumb thumb = ControlBarDetector.detectThumb(
                new BitmapPixels(bitmap), Math.round(sliderLeft.y));
        if (thumb.centerX() < 0) {
            throw new IllegalStateException("手数操作バーのツマミ位置を検出できません");
        }
        PointF point = new PointF(thumb.centerX(), sliderLeft.y);
        requireSafe(point);
        return point;
    }

    public boolean isSliderAtLeft(Bitmap bitmap) {
        ControlBarDetector.Thumb thumb = ControlBarDetector.detectThumb(
                new BitmapPixels(bitmap), Math.round(sliderLeft.y));
        if (thumb.centerX() < 0) return false;
        return Math.abs(thumb.centerX() - sliderLeft.x) <= Math.max(6, screenWidth * 0.015f);
    }

    public double sliderProgress(Bitmap b) {
        ControlBarDetector.Thumb thumb = ControlBarDetector.detectThumb(
                new BitmapPixels(b), Math.round(sliderLeft.y));
        if (thumb.centerX() < 0) return -1;
        double progress = (thumb.centerX() - sliderLeft.x)
                / Math.max(1.0, sliderRight.x - sliderLeft.x);
        return Math.max(0, Math.min(1, progress));
    }
}
