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

    @Test public void findsDarkGestureIndicatorOnLightScreen() {
        BoardAnalyzer.Pixels pixels = pixels(800, 1280, (x, y) ->
                x >= 345 && x <= 454 && y >= 1266 && y <= 1270 ? 0xff606063 : 0xffefeff7);
        HomeIndicatorDetector.Result result = HomeIndicatorDetector.detect(pixels);
        assertNotNull(result);
        assertEquals(1266, result.top());
        assertEquals(110, result.width());
    }

    @Test public void ignoresReplayControlsAboveSystemBand() {
        BoardAnalyzer.Pixels pixels = pixels(800, 1280, (x, y) ->
                x >= 40 && x <= 360 && y == 1252 ? 0xff164d76 : 0xffe8edf5);
        assertNull(HomeIndicatorDetector.detect(pixels));
    }

    @Test public void shiftsReplayTouchAboveGestureNavigationHitArea() {
        HomeIndicatorDetector.Result indicator = new HomeIndicatorDetector.Result(1267, 1272, 400, 110);
        assertEquals(1219, ControlBarDetector.touchY(1253, indicator, 1280));
        assertEquals(1253, ControlBarDetector.touchY(1253, null));
    }

    @Test public void selectsScoreFourBetweenSliderOnlyAndHomeBarBounds() {
        assertEquals(1228.0f,
                ReaderAccessibilityService.operationYForScore4(1213, 1258), 0.001f);
        assertEquals(1228.0f,
                ReaderAccessibilityService.operationYForVisualControl(1253, 1280), 0.001f);
        assertEquals(1240.0f,
                ReaderAccessibilityService.sliderOnlySwipeYForVisualControl(1253, 1280), 0.001f);
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
