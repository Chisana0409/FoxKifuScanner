package jp.chisana.foxkifuscanner;

import java.util.Objects;

/**
 * A validated, discrete slider range whose values can be mapped to replay indexes.
 *
 * <p>Image-derived slider progress must not be passed off as an exact replay index. This
 * class is intended for a source such as an accessibility {@code RangeInfo} which explicitly
 * reports a discrete minimum, maximum and current value.</p>
 */
public final class SliderIndexRange {
    public static final int MAX_MOVES = 1000;
    private static final double INTEGER_TOLERANCE = 0.0001;

    private final int minimumValue;
    private final int maximumValue;
    private final int currentValue;

    private SliderIndexRange(int minimumValue, int maximumValue, int currentValue) {
        this.minimumValue = minimumValue;
        this.maximumValue = maximumValue;
        this.currentValue = currentValue;
    }

    /**
     * Validates and converts a discrete slider range.
     *
     * @throws IllegalArgumentException if the source is continuous, non-finite, not sufficiently
     *         close to integer values, outside the supported 0..1000 range, reversed, or reports
     *         a current value outside its bounds
     */
    public static SliderIndexRange from(float minimum, float maximum, float current,
                                        boolean discrete) {
        if (!discrete) {
            throw new IllegalArgumentException("slider range is not discrete");
        }

        int minimumValue = exactInteger("minimum", minimum);
        int maximumValue = exactInteger("maximum", maximum);
        int currentValue = exactInteger("current", current);

        if (minimumValue < 0 || maximumValue > MAX_MOVES) {
            throw new IllegalArgumentException("slider range must stay within 0.." + MAX_MOVES);
        }
        if (maximumValue < minimumValue) {
            throw new IllegalArgumentException("slider range is reversed");
        }
        if (currentValue < minimumValue || currentValue > maximumValue) {
            throw new IllegalArgumentException("current slider value is outside its range");
        }
        return new SliderIndexRange(minimumValue, maximumValue, currentValue);
    }

    /** Number of replay transitions represented by the range. */
    public int moveCount() {
        return maximumValue - minimumValue;
    }

    /** Zero-based replay index, independent of a non-zero raw range minimum. */
    public int currentIndex() {
        return currentValue - minimumValue;
    }

    public boolean hasNext() {
        return currentValue < maximumValue;
    }

    /** Exact raw slider value for the immediately following replay index. */
    public float nextExactValue() {
        if (!hasNext()) {
            throw new IllegalStateException("slider is already at its final index");
        }
        return currentValue + 1.0f;
    }

    /** Exact raw slider value for a validated zero-based replay index. */
    public float valueForIndex(int index) {
        if (index < 0 || index > moveCount()) {
            throw new IllegalArgumentException("replay index is outside 0.." + moveCount());
        }
        return minimumValue + (float) index;
    }

    /** True only when this observation is exactly one node after {@code previousIndex}. */
    public boolean isNextIndex(int previousIndex) {
        return previousIndex >= 0
                && previousIndex < moveCount()
                && currentIndex() == previousIndex + 1;
    }

    /**
     * Returns the signed index difference to another observation of the same slider range.
     * A result of one is the only value that proves an immediately following replay node.
     */
    public int deltaTo(SliderIndexRange next) {
        Objects.requireNonNull(next, "next");
        if (minimumValue != next.minimumValue || maximumValue != next.maximumValue) {
            throw new IllegalArgumentException("slider range changed during replay");
        }
        return next.currentIndex() - currentIndex();
    }

    public boolean isImmediateSuccessor(SliderIndexRange next) {
        return next != null
                && minimumValue == next.minimumValue
                && maximumValue == next.maximumValue
                && next.currentIndex() - currentIndex() == 1;
    }

    private static int exactInteger(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " slider value is not finite");
        }
        long rounded = Math.round((double) value);
        if (Math.abs((double) value - rounded) > INTEGER_TOLERANCE) {
            throw new IllegalArgumentException(label + " slider value is not an integer");
        }
        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " slider value is out of integer range");
        }
        return (int) rounded;
    }
}
