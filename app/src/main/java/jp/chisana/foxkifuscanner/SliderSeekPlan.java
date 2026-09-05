package jp.chisana.foxkifuscanner;

import java.util.Objects;

/**
 * Calibrates an accessibility slider from its initial value and the value observed after one
 * replay step.
 *
 * <p>The reported range is not assumed to use move numbers. Some controls expose percentages or
 * a normalized {@code 0..1} range. The observed one-step delta is therefore the authoritative
 * spacing between replay indexes.</p>
 */
final class SliderSeekPlan {
    static final int MAX_MOVES = 1000;

    private static final double RANGE_RELATIVE_TOLERANCE = 0.00001;
    private static final double RANGE_ULP_MULTIPLIER = 4.0;
    private static final double POSITION_STEP_TOLERANCE = 0.15;
    private static final double POSITION_SPAN_TOLERANCE = 0.000001;
    private static final double RESIDUAL_STEP_TOLERANCE = 0.15;
    private static final double RESIDUAL_SPAN_TOLERANCE = 0.002;

    record Snapshot(double min, double max, double current) {}

    private final double min;
    private final double max;
    private final double step;
    private final int totalMoves;
    private final double rangeTolerance;
    private final double positionTolerance;

    private SliderSeekPlan(double min, double max, double step, int totalMoves,
                           double rangeTolerance, double positionTolerance) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.totalMoves = totalMoves;
        this.rangeTolerance = rangeTolerance;
        this.positionTolerance = positionTolerance;
    }

    /**
     * Creates a seek plan from the range at the initial position and the range after exactly one
     * forward replay step.
     *
     * @throws IllegalArgumentException when the observations cannot prove a stable, discrete
     *         range containing between one and {@value #MAX_MOVES} moves
     */
    static SliderSeekPlan calibrate(Snapshot initial, Snapshot afterOneMove) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(afterOneMove, "afterOneMove");
        requireFinite("initial", initial);
        requireFinite("afterOneMove", afterOneMove);

        double span = initial.max() - initial.min();
        if (!(span > 0.0)) {
            throw new IllegalArgumentException("slider range must have positive span");
        }

        double rangeTolerance = rangeTolerance(initial.min(), initial.max(), span);
        if (!near(initial.min(), afterOneMove.min(), rangeTolerance)
                || !near(initial.max(), afterOneMove.max(), rangeTolerance)) {
            throw new IllegalArgumentException("slider range changed during calibration");
        }

        double step = afterOneMove.current() - initial.current();
        if (!(step > 0.0) || step > span + rangeTolerance) {
            throw new IllegalArgumentException("forward replay did not advance within the range");
        }

        double positionTolerance = Math.max(
                Math.abs(step) * POSITION_STEP_TOLERANCE,
                Math.abs(span) * POSITION_SPAN_TOLERANCE);
        positionTolerance = Math.max(positionTolerance, rangeTolerance);
        if (!near(initial.current(), initial.min(), positionTolerance)) {
            throw new IllegalArgumentException("initial slider value is not at the range minimum");
        }
        if (afterOneMove.current() > initial.max() + positionTolerance) {
            throw new IllegalArgumentException("forward replay advanced beyond the range maximum");
        }

        double estimatedMoves = span / step;
        if (!Double.isFinite(estimatedMoves) || estimatedMoves > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("slider move count is not finite");
        }
        long roundedMoves = Math.round(estimatedMoves);
        if (roundedMoves < 1 || roundedMoves > MAX_MOVES) {
            throw new IllegalArgumentException("slider move count is outside 1.." + MAX_MOVES);
        }

        double residual = Math.abs(span - roundedMoves * step);
        double residualTolerance = Math.max(
                Math.abs(step) * RESIDUAL_STEP_TOLERANCE,
                Math.abs(span) * RESIDUAL_SPAN_TOLERANCE);
        if (residual > residualTolerance) {
            throw new IllegalArgumentException("slider step does not divide the range");
        }

        return new SliderSeekPlan(initial.min(), initial.max(), step, (int) roundedMoves,
                rangeTolerance, positionTolerance);
    }

    int totalMoves() {
        return totalMoves;
    }

    double min() {
        return min;
    }

    double max() {
        return max;
    }

    double step() {
        return step;
    }

    double positionTolerance() {
        return positionTolerance;
    }

    /** Returns the raw accessibility range value for replay index {@code 0..totalMoves}. */
    double targetForIndex(int index) {
        if (index < 0 || index > totalMoves) {
            throw new IllegalArgumentException(
                    "replay index is outside 0.." + totalMoves);
        }
        // Use the advertised endpoint verbatim so accumulated floating-point error cannot leave
        // the final action just short of the end of the game.
        return index == totalMoves ? max : min + step * index;
    }

    /** Returns the exact calibrated replay index, or {@code -1} for an in-between/stale value. */
    int indexOf(double current) {
        if (!Double.isFinite(current)
                || current < min - positionTolerance
                || current > max + positionTolerance) {
            return -1;
        }
        if (near(current, max, positionTolerance)) return totalMoves;

        long rounded = Math.round((current - min) / step);
        if (rounded < 0 || rounded > totalMoves) return -1;
        int index = (int) rounded;
        return isAtIndex(current, index) ? index : -1;
    }

    /** True only when {@code current} is within the calibrated tolerance of {@code index}. */
    boolean isAtIndex(double current, int index) {
        return Double.isFinite(current)
                && index >= 0
                && index <= totalMoves
                && near(current, targetForIndex(index), positionTolerance);
    }

    /** Verifies that a later accessibility observation still describes the calibrated range. */
    boolean matchesRange(Snapshot observation) {
        return observation != null
                && Double.isFinite(observation.min())
                && Double.isFinite(observation.max())
                && near(min, observation.min(), rangeTolerance)
                && near(max, observation.max(), rangeTolerance);
    }

    private static void requireFinite(String label, Snapshot snapshot) {
        if (!Double.isFinite(snapshot.min())
                || !Double.isFinite(snapshot.max())
                || !Double.isFinite(snapshot.current())) {
            throw new IllegalArgumentException(label + " slider values must be finite");
        }
    }

    private static double rangeTolerance(double min, double max, double span) {
        double floatNoise = Math.max(Math.ulp((float) min), Math.ulp((float) max))
                * RANGE_ULP_MULTIPLIER;
        return Math.max(floatNoise, Math.abs(span) * RANGE_RELATIVE_TOLERANCE);
    }

    private static boolean near(double first, double second, double tolerance) {
        return Math.abs(first - second) <= tolerance;
    }
}
