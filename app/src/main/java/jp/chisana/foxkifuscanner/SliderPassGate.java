package jp.chisana.foxkifuscanner;

/**
 * Decides when an unchanged slider frame is settled enough to request an explicit screenshot.
 *
 * <p>The caller owns frame freshness and screenshot capture. This class only tracks observations,
 * so it never sleeps or blocks. A pass confirmation may be requested only after the slider index
 * acknowledgement and the most recent invalid/partial frame have both been quiet for 250 ms, and
 * two fresh frames have shown an unchanged board.</p>
 */
public final class SliderPassGate {
    public static final long MIN_QUIET_MS = 250L;
    public static final int REQUIRED_UNCHANGED_FRESH_FRAMES = 2;

    private final long acknowledgedAtMs;
    private long quietSinceMs;
    private long lastObservationAtMs;
    private int unchangedFreshFrames;
    private boolean timelineValid;

    public SliderPassGate(long acknowledgedAtMs) {
        this.acknowledgedAtMs = acknowledgedAtMs;
        quietSinceMs = acknowledgedAtMs;
        lastObservationAtMs = acknowledgedAtMs;
        timelineValid = acknowledgedAtMs >= 0L;
    }

    /** Resets both the quiet interval and the unchanged-frame evidence. */
    public void observeInvalidOrPartial(long observedAtMs) {
        unchangedFreshFrames = 0;
        if (!acceptObservationTime(observedAtMs)) return;
        quietSinceMs = observedAtMs;
    }

    /**
     * Records one caller-verified fresh frame whose board is unchanged.
     *
     * @return whether an explicit screenshot may now be requested to confirm a pass
     */
    public boolean observeUnchangedFreshFrame(long observedAtMs) {
        if (!acceptObservationTime(observedAtMs)) return false;
        if (unchangedFreshFrames < REQUIRED_UNCHANGED_FRESH_FRAMES) {
            unchangedFreshFrames++;
        }
        return canRequestExplicitPassConfirmation(observedAtMs);
    }

    /** Returns true only when all timing and fresh-frame requirements are satisfied. */
    public boolean canRequestExplicitPassConfirmation(long nowMs) {
        return unchangedFreshFrames >= REQUIRED_UNCHANGED_FRESH_FRAMES
                && canRequestAdditionalFreshEvidence(nowMs);
    }

    /**
     * Returns true once another explicit screenshot can be sampled without blocking the normal
     * fresh-frame path. The caller still needs two prior unchanged observations before that
     * screenshot can be used as the final pass proof.
     */
    public boolean canRequestAdditionalFreshEvidence(long nowMs) {
        return timelineValid
                && nowMs >= lastObservationAtMs
                && elapsedAtLeast(nowMs, acknowledgedAtMs, MIN_QUIET_MS)
                && elapsedAtLeast(nowMs, quietSinceMs, MIN_QUIET_MS);
    }

    private boolean acceptObservationTime(long observedAtMs) {
        if (!timelineValid || observedAtMs < 0L || observedAtMs < lastObservationAtMs) {
            // A regressed timestamp cannot prove freshness. Fail closed for this seek rather than
            // allowing old frame evidence to become valid again when the clock catches up.
            timelineValid = false;
            unchangedFreshFrames = 0;
            return false;
        }
        lastObservationAtMs = observedAtMs;
        return true;
    }

    private static boolean elapsedAtLeast(long nowMs, long sinceMs, long durationMs) {
        // Ordering the non-negative timestamps before subtraction avoids both reversal and
        // signed-overflow surprises near Long.MAX_VALUE.
        return nowMs >= 0L && sinceMs >= 0L && nowMs >= sinceMs
                && nowMs - sinceMs >= durationMs;
    }
}
