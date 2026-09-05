package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SliderPassGateTest {
    @Test public void requiresFullAckQuietBoundary() {
        SliderPassGate gate = new SliderPassGate(1_000L);
        gate.observeUnchangedFreshFrame(1_248L);
        gate.observeUnchangedFreshFrame(1_249L);

        assertFalse(gate.canRequestExplicitPassConfirmation(1_249L));
        assertTrue(gate.canRequestExplicitPassConfirmation(1_250L));
    }

    @Test public void requiresFullQuietBoundaryAfterLastInvalidFrame() {
        SliderPassGate gate = new SliderPassGate(1_000L);
        gate.observeInvalidOrPartial(1_050L);
        gate.observeUnchangedFreshFrame(1_298L);
        gate.observeUnchangedFreshFrame(1_299L);

        assertFalse(gate.canRequestExplicitPassConfirmation(1_299L));
        assertTrue(gate.canRequestExplicitPassConfirmation(1_300L));
    }

    @Test public void requiresTwoUnchangedFreshFrames() {
        SliderPassGate gate = new SliderPassGate(0L);

        assertTrue(gate.canRequestAdditionalFreshEvidence(250L));
        assertFalse(gate.observeUnchangedFreshFrame(250L));
        assertFalse(gate.canRequestExplicitPassConfirmation(250L));
        assertTrue(gate.observeUnchangedFreshFrame(250L));
    }

    @Test public void invalidOrPartialObservationResetsQuietTimeAndFrameCount() {
        SliderPassGate gate = new SliderPassGate(0L);
        gate.observeUnchangedFreshFrame(250L);
        assertTrue(gate.observeUnchangedFreshFrame(250L));

        gate.observeInvalidOrPartial(300L);
        assertFalse(gate.canRequestAdditionalFreshEvidence(549L));
        assertTrue(gate.canRequestAdditionalFreshEvidence(550L));
        assertFalse(gate.canRequestExplicitPassConfirmation(550L));
        assertFalse(gate.observeUnchangedFreshFrame(550L));
        assertTrue(gate.observeUnchangedFreshFrame(550L));
    }

    @Test public void timestampRegressionFailsClosed() {
        SliderPassGate gate = new SliderPassGate(100L);
        gate.observeUnchangedFreshFrame(350L);

        assertFalse(gate.observeUnchangedFreshFrame(349L));
        assertFalse(gate.observeUnchangedFreshFrame(500L));
        assertFalse(gate.canRequestExplicitPassConfirmation(Long.MAX_VALUE));
    }

    @Test public void negativeTimesFailClosed() {
        SliderPassGate negativeAck = new SliderPassGate(-1L);
        assertFalse(negativeAck.observeUnchangedFreshFrame(250L));
        assertFalse(negativeAck.canRequestExplicitPassConfirmation(Long.MAX_VALUE));

        SliderPassGate negativeObservation = new SliderPassGate(0L);
        assertFalse(negativeObservation.observeUnchangedFreshFrame(-1L));
        assertFalse(negativeObservation.canRequestExplicitPassConfirmation(Long.MAX_VALUE));

        SliderPassGate negativeNow = new SliderPassGate(0L);
        negativeNow.observeUnchangedFreshFrame(250L);
        negativeNow.observeUnchangedFreshFrame(250L);
        assertFalse(negativeNow.canRequestExplicitPassConfirmation(-1L));
    }

    @Test public void elapsedBoundaryDoesNotOverflowNearLongMaximum() {
        long acknowledgedAt = Long.MAX_VALUE - SliderPassGate.MIN_QUIET_MS;
        SliderPassGate gate = new SliderPassGate(acknowledgedAt);

        gate.observeUnchangedFreshFrame(Long.MAX_VALUE);
        assertTrue(gate.observeUnchangedFreshFrame(Long.MAX_VALUE));
    }
}
