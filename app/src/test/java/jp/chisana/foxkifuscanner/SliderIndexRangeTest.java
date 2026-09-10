package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SliderIndexRangeTest {
    @Test public void mapsNormalZeroToNRange() {
        SliderIndexRange range = SliderIndexRange.from(0.0f, 250.0f, 73.0f, true);

        assertEquals(250, range.moveCount());
        assertEquals(73, range.currentIndex());
        assertTrue(range.hasNext());
        assertEquals(74.0f, range.nextExactValue(), 0.0f);
        assertEquals(0.0f, range.valueForIndex(0), 0.0f);
        assertEquals(250.0f, range.valueForIndex(250), 0.0f);
    }

    @Test public void mapsNonZeroRawMinimumToZeroBasedIndexes() {
        SliderIndexRange range = SliderIndexRange.from(5.0f, 10.0f, 7.0f, true);

        assertEquals(5, range.moveCount());
        assertEquals(2, range.currentIndex());
        assertEquals(8.0f, range.nextExactValue(), 0.0f);
        assertEquals(5.0f, range.valueForIndex(0), 0.0f);
    }

    @Test public void acceptsOnlyTinyFloatingPointIntegerNoise() {
        SliderIndexRange range = SliderIndexRange.from(0.00001f, 19.00001f, 4.00001f, true);

        assertEquals(19, range.moveCount());
        assertEquals(4, range.currentIndex());
    }

    @Test public void zeroMoveRangeHasNoNextValue() {
        SliderIndexRange range = SliderIndexRange.from(0.0f, 0.0f, 0.0f, true);

        assertEquals(0, range.moveCount());
        assertEquals(0, range.currentIndex());
        assertFalse(range.hasNext());
        assertThrows(IllegalStateException.class, range::nextExactValue);
    }

    @Test public void rejectsContinuousAndNonIntegerRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(0.0f, 100.0f, 10.0f, false));
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(0.01f, 100.0f, 10.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(0.0f, 100.25f, 10.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(0.0f, 100.0f, 10.5f, true));
    }

    @Test public void rejectsNaNAndInfinityInEveryField() {
        float[] invalid = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float value : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> SliderIndexRange.from(value, 10.0f, 0.0f, true));
            assertThrows(IllegalArgumentException.class,
                    () -> SliderIndexRange.from(0.0f, value, 0.0f, true));
            assertThrows(IllegalArgumentException.class,
                    () -> SliderIndexRange.from(0.0f, 10.0f, value, true));
        }
    }

    @Test public void rejectsReversedNegativeAndOversizeRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(10.0f, 5.0f, 7.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(-1.0f, 100.0f, 0.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(0.0f, 1001.0f, 0.0f, true));
    }

    @Test public void rejectsCurrentValueOutsideRange() {
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(10.0f, 20.0f, 9.0f, true));
        assertThrows(IllegalArgumentException.class,
                () -> SliderIndexRange.from(10.0f, 20.0f, 21.0f, true));
    }

    @Test public void distinguishesExactNextFromDuplicateSkipAndReverse() {
        SliderIndexRange index7 = SliderIndexRange.from(0.0f, 100.0f, 7.0f, true);
        SliderIndexRange duplicate = SliderIndexRange.from(0.0f, 100.0f, 7.0f, true);
        SliderIndexRange index8 = SliderIndexRange.from(0.0f, 100.0f, 8.0f, true);
        SliderIndexRange index9 = SliderIndexRange.from(0.0f, 100.0f, 9.0f, true);
        SliderIndexRange index6 = SliderIndexRange.from(0.0f, 100.0f, 6.0f, true);

        assertEquals(0, index7.deltaTo(duplicate));
        assertEquals(1, index7.deltaTo(index8));
        assertEquals(2, index7.deltaTo(index9));
        assertEquals(-1, index7.deltaTo(index6));
        assertTrue(index7.isImmediateSuccessor(index8));
        assertFalse(index7.isImmediateSuccessor(duplicate));
        assertFalse(index7.isImmediateSuccessor(index9));
        assertFalse(index7.isImmediateSuccessor(index6));
        assertFalse(index7.isImmediateSuccessor(null));
        assertTrue(index8.isNextIndex(7));
        assertFalse(index8.isNextIndex(8));
        assertFalse(index8.isNextIndex(6));
        assertFalse(index8.isNextIndex(-1));
    }

    @Test public void rejectsDeltaAcrossChangedRange() {
        SliderIndexRange original = SliderIndexRange.from(0.0f, 100.0f, 7.0f, true);
        SliderIndexRange changedMaximum = SliderIndexRange.from(0.0f, 101.0f, 8.0f, true);
        SliderIndexRange changedMinimum = SliderIndexRange.from(1.0f, 100.0f, 8.0f, true);

        assertThrows(IllegalArgumentException.class, () -> original.deltaTo(changedMaximum));
        assertThrows(IllegalArgumentException.class, () -> original.deltaTo(changedMinimum));
        assertFalse(original.isImmediateSuccessor(changedMaximum));
        assertFalse(original.isImmediateSuccessor(changedMinimum));
    }

    @Test public void rejectsIndexesOutsideValidatedRange() {
        SliderIndexRange range = SliderIndexRange.from(0.0f, 10.0f, 3.0f, true);

        assertThrows(IllegalArgumentException.class, () -> range.valueForIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> range.valueForIndex(11));
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
