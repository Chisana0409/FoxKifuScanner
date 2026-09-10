package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SliderSeekPlanTest {
    @Test public void calibratesNormalizedRange() {
        SliderSeekPlan plan = plan(0.0, 1.0, 0.0, 0.005);

        assertEquals(200, plan.totalMoves());
        assertEquals(0.005, plan.step(), 0.0000001);
        assertEquals(0.0, plan.targetForIndex(0), 0.0);
        assertEquals(0.5, plan.targetForIndex(100), 0.0000001);
        assertEquals(1.0, plan.targetForIndex(200), 0.0);
    }

    @Test public void calibratesPercentageRange() {
        SliderSeekPlan plan = plan(0.0, 100.0, 0.0, 0.5);

        assertEquals(200, plan.totalMoves());
        assertEquals(73.5, plan.targetForIndex(147), 0.0000001);
    }

    @Test public void calibratesNonZeroMinimum() {
        SliderSeekPlan plan = plan(5.0, 105.0, 5.0, 5.5);

        assertEquals(200, plan.totalMoves());
        assertEquals(5.0, plan.targetForIndex(0), 0.0);
        assertEquals(105.0, plan.targetForIndex(200), 0.0);
        assertEquals(19, plan.indexOf(14.5));
    }

    @Test public void acceptsSmallRangeAndStepNoise() {
        SliderSeekPlan plan = SliderSeekPlan.calibrate(
                snapshot(0.0, 1.0, 0.0),
                snapshot(0.000001, 1.000001, 0.005001));

        assertEquals(200, plan.totalMoves());
        assertTrue(plan.matchesRange(snapshot(0.000001, 1.000001, 0.75)));
    }

    @Test public void usesAdvertisedMaximumForFinalTargetWhenResidualIsAllowed() {
        SliderSeekPlan plan = plan(0.0, 100.0, 0.0, 3.02);

        assertEquals(33, plan.totalMoves());
        assertEquals(96.64, plan.targetForIndex(32), 0.0000001);
        assertEquals(100.0, plan.targetForIndex(33), 0.0);
        assertTrue(plan.isAtIndex(100.0, 33));
    }

    @Test public void mapsOnlyValuesCloseToCalibratedIndexes() {
        SliderSeekPlan plan = plan(0.0, 1.0, 0.0, 0.005);

        assertEquals(100, plan.indexOf(0.5007));
        assertTrue(plan.isAtIndex(0.5007, 100));
        assertEquals(-1, plan.indexOf(0.5010));
        assertFalse(plan.isAtIndex(0.5010, 100));
        assertEquals(200, plan.indexOf(1.0));
        assertEquals(-1, plan.indexOf(Double.NaN));
    }

    @Test public void rejectsChangedRange() {
        assertCalibrationFails(
                snapshot(0.0, 100.0, 0.0),
                snapshot(0.0, 101.0, 1.0));
        assertCalibrationFails(
                snapshot(5.0, 105.0, 5.0),
                snapshot(6.0, 105.0, 6.0));

        SliderSeekPlan plan = plan(0.0, 100.0, 0.0, 1.0);
        assertFalse(plan.matchesRange(snapshot(0.0, 101.0, 20.0)));
    }

    @Test public void rejectsInitialValueAwayFromMinimum() {
        assertCalibrationFails(
                snapshot(0.0, 100.0, 1.0),
                snapshot(0.0, 100.0, 2.0));
    }

    @Test public void rejectsNoAdvanceReverseAndBeyondMaximum() {
        assertCalibrationFails(
                snapshot(0.0, 100.0, 0.0),
                snapshot(0.0, 100.0, 0.0));
        assertCalibrationFails(
                snapshot(0.0, 100.0, 0.0),
                snapshot(0.0, 100.0, -1.0));
        assertCalibrationFails(
                snapshot(0.0, 100.0, 0.0),
                snapshot(0.0, 100.0, 101.0));
    }

    @Test public void rejectsInvalidSpanAndNonFiniteValues() {
        assertCalibrationFails(
                snapshot(1.0, 1.0, 1.0),
                snapshot(1.0, 1.0, 1.0));
        assertCalibrationFails(
                snapshot(2.0, 1.0, 2.0),
                snapshot(2.0, 1.0, 1.0));
        assertCalibrationFails(
                snapshot(Double.NaN, 1.0, 0.0),
                snapshot(0.0, 1.0, 0.1));
        assertCalibrationFails(
                snapshot(0.0, Double.POSITIVE_INFINITY, 0.0),
                snapshot(0.0, 1.0, 0.1));
    }

    @Test public void rejectsMoveCountsOutsideSupportedLimit() {
        assertCalibrationFails(
                snapshot(0.0, 1.0, 0.0),
                snapshot(0.0, 1.0, 0.0005));
    }

    @Test public void rejectsStepThatDoesNotDivideRangeWithinTolerance() {
        assertCalibrationFails(
                snapshot(0.0, 100.0, 0.0),
                snapshot(0.0, 100.0, 3.0));
    }

    @Test public void rejectsTargetsOutsideReplayIndexes() {
        SliderSeekPlan plan = plan(0.0, 10.0, 0.0, 1.0);

        assertThrows(IllegalArgumentException.class, () -> plan.targetForIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> plan.targetForIndex(11));
        assertFalse(plan.isAtIndex(0.0, -1));
        assertFalse(plan.isAtIndex(10.0, 11));
    }

    private static SliderSeekPlan plan(double min, double max,
                                       double initialCurrent, double afterCurrent) {
        return SliderSeekPlan.calibrate(
                snapshot(min, max, initialCurrent),
                snapshot(min, max, afterCurrent));
    }

    private static SliderSeekPlan.Snapshot snapshot(double min, double max, double current) {
        return new SliderSeekPlan.Snapshot(min, max, current);
    }

    private static void assertCalibrationFails(SliderSeekPlan.Snapshot initial,
                                               SliderSeekPlan.Snapshot after) {
        assertThrows(IllegalArgumentException.class,
                () -> SliderSeekPlan.calibrate(initial, after));
    }

    private static <T extends Throwable> T assertThrows(Class<T> type,
                                                         ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            fail("expected " + type.getSimpleName() + " but got "
                    + error.getClass().getSimpleName());
        }
        fail("expected " + type.getSimpleName());
        return null;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
