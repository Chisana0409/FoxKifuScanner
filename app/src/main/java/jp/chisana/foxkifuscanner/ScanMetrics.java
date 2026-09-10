package jp.chisana.foxkifuscanner;

import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Lightweight wall-clock metrics for the complete scan-to-SGF pipeline. */
final class ScanMetrics {
    private static final String TAG = "FoxKifuPerf";

    private final long startedNanos = SystemClock.elapsedRealtimeNanos();
    private final Map<String, Long> phaseNanos = new LinkedHashMap<>();
    private long gestureNanos;
    private long freshFrameWaitNanos;
    private long lightAnalysisNanos;
    private long boardAnalysisNanos;
    private long sliderSeekNanos;
    private int freshFrames;
    private int freshTimeouts;
    private int fastOneFrameMoves;
    private int verifiedMoves;
    private int retries;
    private int ocrTimeouts;
    private int sliderMoves;
    private int sliderFallbacks;
    private int sliderEventAcks;
    private int sliderSnapshotAcks;
    private int sliderUnchangedFrames;
    private int sliderPassProbes;
    private String sliderMode = "none";
    private long ocrStartedNanos;
    private boolean ocrPhaseRecorded;
    private boolean ocrTimeoutRecorded;
    private boolean finished;

    long mark() {
        return SystemClock.elapsedRealtimeNanos();
    }

    synchronized void addPhase(String name, long sinceNanos) {
        if (finished) return;
        long elapsed = Math.max(0, SystemClock.elapsedRealtimeNanos() - sinceNanos);
        phaseNanos.merge(name, elapsed, Long::sum);
    }

    synchronized void addGesture(long sinceNanos) {
        gestureNanos += Math.max(0, SystemClock.elapsedRealtimeNanos() - sinceNanos);
    }

    synchronized void addFreshFrameWait(long sinceNanos, boolean received) {
        freshFrameWaitNanos += Math.max(0, SystemClock.elapsedRealtimeNanos() - sinceNanos);
        if (received) freshFrames++;
        else freshTimeouts++;
    }

    synchronized void addBoardAnalysis(long sinceNanos) {
        boardAnalysisNanos += Math.max(0, SystemClock.elapsedRealtimeNanos() - sinceNanos);
    }

    synchronized void addLightAnalysis(long sinceNanos) {
        lightAnalysisNanos += Math.max(0, SystemClock.elapsedRealtimeNanos() - sinceNanos);
    }

    synchronized void addSliderSeek(long sinceNanos) {
        sliderSeekNanos += Math.max(0, SystemClock.elapsedRealtimeNanos() - sinceNanos);
    }

    synchronized void sliderStarted(String mode) {
        sliderMode = mode == null || mode.isBlank() ? "unknown" : mode;
        // A failed RANGE attempt can be rewound and retried via TEXT. Report only the
        // accepted moves of the currently selected SHS route, not discarded probe moves.
        sliderMoves = 0;
        sliderEventAcks = 0;
        sliderSnapshotAcks = 0;
        sliderUnchangedFrames = 0;
        sliderPassProbes = 0;
    }

    synchronized void acceptedSliderMove() {
        sliderMoves++;
    }

    synchronized void sliderFallback() {
        sliderFallbacks++;
    }

    synchronized void sliderEventAck() {
        sliderEventAcks++;
    }

    synchronized void sliderSnapshotAck() {
        sliderSnapshotAcks++;
    }

    synchronized void sliderUnchangedFrame() {
        sliderUnchangedFrames++;
    }

    synchronized void sliderPassProbe() {
        sliderPassProbes++;
    }

    synchronized void acceptedFastMove() {
        fastOneFrameMoves++;
    }

    synchronized void acceptedVerifiedMove() {
        verifiedMoves++;
    }

    synchronized void retriedMove() {
        retries++;
    }

    synchronized void ocrStarted() {
        ocrStartedNanos = SystemClock.elapsedRealtimeNanos();
        ocrPhaseRecorded = false;
        ocrTimeoutRecorded = false;
    }

    synchronized void ocrFinished() {
        recordOcrPhase();
    }

    synchronized void ocrTimedOut() {
        if (!ocrTimeoutRecorded) {
            ocrTimeouts++;
            ocrTimeoutRecorded = true;
        }
        recordOcrPhase();
    }

    private void recordOcrPhase() {
        if (finished || ocrPhaseRecorded || ocrStartedNanos == 0L) return;
        phaseNanos.put("ocr", Math.max(0L,
                SystemClock.elapsedRealtimeNanos() - ocrStartedNanos));
        ocrPhaseRecorded = true;
    }

    synchronized String finishSuccess(int moves) {
        return finish("success", moves, null);
    }

    synchronized String finishFailure(int moves, Throwable error) {
        String reason = error == null ? null : error.getClass().getSimpleName();
        return finish("failure", moves, reason);
    }

    synchronized String finishCancelled(int moves) {
        return finish("cancelled", moves, null);
    }

    private String finish(String result, int moves, String reason) {
        if (finished) return summary(result, moves, reason);
        recordOcrPhase();
        finished = true;
        String summary = summary(result, moves, reason);
        Log.i(TAG, summary);
        return summary;
    }

    private String summary(String result, int moves, String reason) {
        double totalMs = millis(SystemClock.elapsedRealtimeNanos() - startedNanos);
        StringBuilder out = new StringBuilder(256);
        out.append("result=").append(result)
                .append(" totalMs=").append(format(totalMs))
                .append(" moves=").append(moves)
                .append(" fast1Frame=").append(fastOneFrameMoves)
                .append(" verified=").append(verifiedMoves)
                .append(" sliderMode=").append(sliderMode)
                .append(" sliderMoves=").append(sliderMoves)
                .append(" sliderFallbacks=").append(sliderFallbacks)
                .append(" sliderEventAcks=").append(sliderEventAcks)
                .append(" sliderSnapshotAcks=").append(sliderSnapshotAcks)
                .append(" sliderUnchanged=").append(sliderUnchangedFrames)
                .append(" sliderPassProbes=").append(sliderPassProbes)
                .append(" retries=").append(retries)
                .append(" ocrTimeouts=").append(ocrTimeouts)
                .append(" freshFrames=").append(freshFrames)
                .append(" freshTimeouts=").append(freshTimeouts)
                .append(" gestureMs=").append(format(millis(gestureNanos)))
                .append(" freshAcquireMs=").append(format(millis(freshFrameWaitNanos)))
                .append(" lightAnalysisMs=").append(format(millis(lightAnalysisNanos)))
                .append(" boardAnalysisMs=").append(format(millis(boardAnalysisNanos)))
                .append(" sliderSeekMs=").append(format(millis(sliderSeekNanos)));
        for (Map.Entry<String, Long> phase : phaseNanos.entrySet()) {
            out.append(' ').append(phase.getKey()).append("Ms=")
                    .append(format(millis(phase.getValue())));
        }
        if (reason != null) out.append(" reason=").append(reason);
        return out.toString();
    }

    synchronized double elapsedSeconds() {
        return (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000_000.0;
    }

    synchronized long elapsedMillis() {
        return Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - startedNanos));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
