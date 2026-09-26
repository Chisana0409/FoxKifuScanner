package jp.chisana.foxkifuscanner;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class HomeIndicatorDetectorTest {
    @Test public void findsTabletGestureIndicator() {
        BoardAnalyzer.Pixels pixels = pixels(800, 1280, (x, y) ->
                x >= 345 && x <= 454 && y >= 1267 && y <= 1271 ? 0xfff8f8f8 : 0xffe8edf5);
        HomeIndicatorDetector.Result result = HomeIndicatorDetector.detect(pixels);
        assertNotNull(result);
        assertEquals(1267, result.top());
        assertEquals(110, result.width());
    }

    @Test public void ignoresReplayControlsAboveSystemBand() {
        BoardAnalyzer.Pixels pixels = pixels(800, 1280, (x, y) ->
                x >= 40 && x <= 360 && y == 1252 ? 0xff164d76 : 0xffe8edf5);
        assertNull(HomeIndicatorDetector.detect(pixels));
    }

    @Test public void shiftsReplayTouchAboveGestureNavigationHitArea() {
        HomeIndicatorDetector.Result indicator = new HomeIndicatorDetector.Result(1267, 1272, 400, 110);
        assertEquals(1239, ControlBarDetector.touchY(1253, indicator));
        assertEquals(1253, ControlBarDetector.touchY(1253, null));
    }

    private static BoardAnalyzer.Pixels pixels(int width, int height, Pixel pixel) {
        return new BoardAnalyzer.Pixels() {
            public int width() { return width; }
            public int height() { return height; }
            public int argb(int x, int y) { return pixel.value(x, y); }
        };
    }

    private interface Pixel { int value(int x, int y); }
}
