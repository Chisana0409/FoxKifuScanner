package jp.chisana.foxkifuscanner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ReaderAccessibilityService extends AccessibilityService {
    private static final String FOX_APP_PACKAGE = "com.foxwq.yhwqgl";
    private static final int MAX_MOVES = 1000;
    private static final long TARGET_TOTAL_MS = 10_000;
    private static final long TARGET_SAVE_RESERVE_MS = 300;
    private static final long FRESH_FRAME_WAIT_MS = 320;
    private static final long OCR_BACKGROUND_TIMEOUT_MS = 12_000;
    private static final long TAP_DURATION_MS = 20;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 150;
    private static final double FAST_FRAME_MIN_CONFIDENCE = 0.78;
    private static final long SHS_INDEX_ACK_TIMEOUT_MS = 180;
    private static final long SHS_STEP_TIMEOUT_MS = 2200;
    private static final long SHS_ACK_POLL_MS = 4;
    private static final long SHS_PASS_SCREENSHOT_TIMEOUT_MS = 500;
    private static final long SHS_PASS_PROBE_INTERVAL_MS = 350;
    private static final long SHS_SEEK_DURATION_MS = 16;
    private static final int SHS_NODE_LIMIT = 1200;
    private static final double SHS_MIN_CONFIDENCE = 0.78;
    private static final float SHS_TEXT_MIN_PIXELS_PER_MOVE = 1.20f;
    private static final double SHS_THUMB_UNCERTAINTY_PIXELS = 2.5;
    private static final double SHS_MIN_VISUAL_PROGRESS_TOLERANCE = 0.008;
    private static volatile ReaderAccessibilityService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService ocrWorker = Executors.newSingleThreadExecutor();
    private final ShsFramePixels shsFramePixels = new ShsFramePixels();
    private final AtomicReference<SliderSession> activeSliderSession = new AtomicReference<>();
    private final AtomicReference<CachedRangeSliderEvent> latestRangeSliderEvent =
            new AtomicReference<>();
    private final AtomicReference<CachedTextSliderEvent> latestTextSliderEvent =
            new AtomicReference<>();
    private final AtomicReference<SliderKey> pendingRangeSliderKey = new AtomicReference<>();
    private volatile boolean cancelled;
    private volatile boolean running;
    private volatile long terminalSignalUntil;
    private volatile int activeMoveCount;
    private volatile String targetPackageName = "";
    private WindowManager windowManager;
    private View overlay;
    private TextView overlayStatus;
    private WindowManager.LayoutParams overlayParams;
    private View metadataEditor;
    private WindowManager.LayoutParams metadataEditorParams;
    private boolean overlayDocked;
    private int overlayOriginalX;
    private int overlayOriginalY;

    private record Frame(Bitmap bitmap, BoardAnalyzer.Detection detection, double sliderProgress) {
        void recycle() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private record SliderTouchProbe(Frame frame, boolean sliderMoved,
                                    boolean homeBarReacted) {}

    private record LightFrame(Bitmap bitmap, BoardFrameFingerprint fingerprint,
                              double sliderProgress, long sequence) {
        void recycle() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private enum OutcomeType { MOVE, PASS, END, FAILURE }

    private record Outcome(OutcomeType type, Move move, BoardState after,
                           BoardFrameFingerprint fingerprint, double sliderProgress,
                           String message) {}

    private enum SliderMode { RANGE, TEXT }

    private record SliderKey(String packageName, String viewId, String className, Rect bounds) {}

    private record SliderRangeSnapshot(SliderKey key, SliderIndexRange range,
                                       SliderSeekPlan.Snapshot raw) {}

    private record SliderNodeCandidate(AccessibilityNodeInfo node,
                                       SliderRangeSnapshot snapshot) {}

    private record SliderAction(long frameSequence, long startedUptimeMillis) {}

    private record SliderAcknowledgement(SliderSeekPlan.Snapshot snapshot,
                                         long eventUptimeMillis,
                                         long receivedUptimeMillis,
                                         long frameSequence,
                                         boolean eventDriven) {}

    /** Immutable source identity attached to a lightweight slider accessibility event. */
    private record SliderEventSource(String packageName, String className, String viewId,
                                     int left, int top, int right, int bottom) {
        SliderKey sliderKey() {
            return new SliderKey(packageName, viewId, className,
                    new Rect(left, top, right, bottom));
        }
    }

    private record SliderEventStamp(SliderEventSource source, long eventUptimeMillis,
                                    long receivedUptimeMillis, long captureSequence) {}

    private record CachedRangeSliderEvent(SliderEventStamp stamp, SliderIndexRange range,
                                          SliderSeekPlan.Snapshot raw) {}

    private record CachedTextSliderEvent(SliderEventStamp stamp,
                                         SliderIndexTextParser.Snapshot index) {}

    private static final class SliderSession implements AutoCloseable {
        private final SliderMode mode;
        private final SliderKey key;
        private final SliderSeekPlan plan;
        private final int totalMoves;
        private final ScreenGeometry geometry;
        private AccessibilityNodeInfo cachedRangeNode;
        private boolean closed;

        SliderSession(SliderMode mode, SliderKey key, SliderSeekPlan plan,
                      int totalMoves, ScreenGeometry geometry,
                      AccessibilityNodeInfo cachedRangeNode) {
            this.mode = mode;
            this.key = key;
            this.plan = plan;
            this.totalMoves = totalMoves;
            this.geometry = geometry;
            this.cachedRangeNode = cachedRangeNode;
        }

        SliderMode mode() { return mode; }

        SliderKey key() { return key; }

        SliderSeekPlan plan() { return plan; }

        int totalMoves() { return totalMoves; }

        ScreenGeometry geometry() { return geometry; }

        String metricName() { return mode == SliderMode.RANGE ? "range" : "text"; }

        synchronized AccessibilityNodeInfo cachedRangeNode() {
            return closed ? null : cachedRangeNode;
        }

        synchronized boolean replaceCachedRangeNode(AccessibilityNodeInfo replacement) {
            if (closed) return false;
            if (cachedRangeNode == replacement) return true;
            AccessibilityNodeInfo previous = cachedRangeNode;
            cachedRangeNode = replacement;
            recycleNode(previous);
            return true;
        }

        synchronized void discardCachedRangeNode(AccessibilityNodeInfo expected) {
            if (cachedRangeNode != expected) return;
            cachedRangeNode = null;
            recycleNode(expected);
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            AccessibilityNodeInfo previous = cachedRangeNode;
            cachedRangeNode = null;
            recycleNode(previous);
        }

        private static void recycleNode(AccessibilityNodeInfo node) {
            if (node == null) return;
            try {
                node.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private record SliderStep(Move move, BoardState state, PointF thumb) {}

    private record SliderReplayResult(List<Move> moves, String fallbackReason) {
        static SliderReplayResult success(List<Move> moves) {
            return new SliderReplayResult(List.copyOf(moves), null);
        }

        static SliderReplayResult fallback(String reason) {
            return new SliderReplayResult(List.of(), reason);
        }

        boolean succeeded() { return fallbackReason == null; }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        publish("ユーザー補助：有効");
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean showOverlayIfConnected() {
        ReaderAccessibilityService service = instance;
        if (service == null) return false;
        service.main.post(service::showOverlay);
        return true;
    }

    public static void stopReadingIfConnected() {
        ReaderAccessibilityService service = instance;
        if (service != null) service.cancelled = true;
    }

    public static void exitIfConnected() {
        ReaderAccessibilityService service = instance;
        if (service != null) service.main.post(service::exitReader);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        StringBuilder text = new StringBuilder();
        for (CharSequence item : event.getText()) text.append(item).append(' ');
        if (event.getContentDescription() != null) text.append(event.getContentDescription());
        if (isTerminalText(text.toString())) {
            terminalSignalUntil = SystemClock.uptimeMillis() + 6000;
        }
        if (!running) return;

        long eventUptimeMillis = event.getEventTime();
        long receivedUptimeMillis = SystemClock.uptimeMillis();
        long captureSequence = CaptureService.currentSequence();
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null || !source.isVisibleToUser()) return;

            CharSequence packageValue = source.getPackageName();
            String packageName = packageValue == null ? "" : packageValue.toString();
            if (packageName.isBlank() || getPackageName().equals(packageName)) return;
            String target = targetPackageName;
            if (target == null || target.isBlank()) {
                targetPackageName = packageName;
            } else if (!target.equals(packageName)) {
                return;
            }

            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) return;
            CharSequence classValue = source.getClassName();
            String className = classValue == null ? "" : classValue.toString();
            String viewId = source.getViewIdResourceName();
            SliderEventSource eventSource = new SliderEventSource(
                    packageName, className, viewId == null ? "" : viewId,
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
            SliderEventStamp stamp = new SliderEventStamp(
                    eventSource, eventUptimeMillis, receivedUptimeMillis, captureSequence);

            cacheDiscreteRangeEvent(source, stamp);
            SliderIndexTextParser.Snapshot index = parseSliderIndexEventText(event, source);
            if (index != null && index.total() > 0) {
                SliderSession active = activeSliderSession.get();
                if (active == null || (active.mode() == SliderMode.TEXT
                        && index.total() == active.totalMoves()
                        && active.geometry().isReplayControlRow(bounds))) {
                    latestTextSliderEvent.set(new CachedTextSliderEvent(stamp, index));
                }
            }
        } catch (Throwable ignored) {
            // Accessibility implementations may invalidate an event source while it is read.
        } finally {
            if (source != null) {
                try {
                    source.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void cacheDiscreteRangeEvent(AccessibilityNodeInfo source, SliderEventStamp stamp) {
        if (!source.isEnabled() || !supportsSetProgress(source)) return;
        AccessibilityNodeInfo.RangeInfo info = source.getRangeInfo();
        if (info == null
                || info.getType() != AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) return;
        try {
            SliderIndexRange range = SliderIndexRange.from(
                    info.getMin(), info.getMax(), info.getCurrent(), true);
            if (range.moveCount() <= 0) return;
            SliderSeekPlan.Snapshot raw = new SliderSeekPlan.Snapshot(
                    info.getMin(), info.getMax(), info.getCurrent());
            SliderSession active = activeSliderSession.get();
            if (active != null && (active.mode() != SliderMode.RANGE
                    || !matchesSliderKey(active.key(), stamp.source().sliderKey()))) return;
            SliderKey pendingKey = pendingRangeSliderKey.get();
            if (active == null && pendingKey != null
                    && !matchesSliderKey(pendingKey, stamp.source().sliderKey())) return;
            latestRangeSliderEvent.set(new CachedRangeSliderEvent(stamp, range, raw));
        } catch (IllegalArgumentException ignored) {
            // Continuous, malformed and out-of-bounds ranges are never cached as move indexes.
        }
    }

    private static SliderIndexTextParser.Snapshot parseSliderIndexEventText(
            AccessibilityEvent event, AccessibilityNodeInfo source) {
        ArrayList<SliderIndexTextParser.Snapshot> parsed = new ArrayList<>();
        for (CharSequence item : event.getText()) addSliderIndexText(parsed, item);
        addSliderIndexText(parsed, event.getContentDescription());
        addSliderIndexText(parsed, source.getText());
        addSliderIndexText(parsed, source.getContentDescription());
        if (parsed.isEmpty()) return null;
        SliderIndexTextParser.Snapshot first = parsed.get(0);
        for (int index = 1; index < parsed.size(); index++) {
            if (!first.equals(parsed.get(index))) return null;
        }
        return first;
    }

    private static void addSliderIndexText(List<SliderIndexTextParser.Snapshot> out,
                                           CharSequence value) {
        if (value == null) return;
        SliderIndexTextParser.Snapshot parsed = SliderIndexTextParser.parse(value.toString());
        if (parsed != null) out.add(parsed);
    }

    /** Atomic reads used by the worker once the event-driven replay path is connected. */
    private CachedRangeSliderEvent latestRangeSliderEvent() {
        return latestRangeSliderEvent.get();
    }

    private CachedTextSliderEvent latestTextSliderEvent() {
        return latestTextSliderEvent.get();
    }

    @Override public void onInterrupt() {
        cancelled = true;
    }

    private void showOverlay() {
        if (overlay != null) {
            overlay.setVisibility(View.VISIBLE);
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(4), dp(5), dp(4));
        panel.setBackground(round(0xF2182B40, 0xFF35C7EA, 16));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView drag = buttonLike("↕");
        Button start = smallButton("開始");
        Button stop = smallButton("停止");
        Button exit = smallButton("終了");
        row.addView(drag);
        row.addView(start);
        row.addView(stop);
        row.addView(exit);

        overlayStatus = new TextView(this);
        overlayStatus.setText("待機中");
        overlayStatus.setTextSize(11);
        overlayStatus.setTextColor(0xFF8CEBFF);
        overlayStatus.setGravity(Gravity.CENTER);
        overlayStatus.setPadding(0, dp(2), 0, dp(3));
        panel.addView(row);
        panel.addView(overlayStatus);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(8);
        overlayParams.y = dp(120);

        drag.setOnTouchListener(new DragHandler());
        start.setOnClickListener(v -> startReading());
        stop.setOnClickListener(v -> {
            cancelled = true;
            setOverlayStatus("停止要求を受け付けました");
        });
        exit.setOnClickListener(v -> exitReader());
        overlay = panel;
        windowManager.addView(overlay, overlayParams);
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12); button.setTextColor(0xFFE7F7FF);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(round(text.equals("開始") ? 0xFF00BCD4 : text.equals("停止") ? 0xFFB3261E : 0xFF294257, 0xFF5B788E, 10));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(42)));
        return button;
    }

    private TextView buttonLike(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xFF8CEBFF);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(42)));
        return view;
    }

    private android.graphics.drawable.GradientDrawable round(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d;
    }

    private final class DragHandler implements View.OnTouchListener {
        float downX;
        float downY;
        int startX;
        int startY;

        @Override public boolean onTouch(View view, android.view.MotionEvent event) {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = overlayParams.x;
                startY = overlayParams.y;
                return true;
            }
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                overlayParams.x = startX + (int) (event.getRawX() - downX);
                overlayParams.y = startY + (int) (event.getRawY() - downY);
                windowManager.updateViewLayout(overlay, overlayParams);
                return true;
            }
            return true;
        }
    }

    private void startReading() {
        if (running) {
            setOverlayStatus("既に読取中です");
            return;
        }
        if (!CaptureService.isReady()) {
            publish("先にアプリ画面で「画面読取を許可」を押してください");
            return;
        }
        cancelled = false;
        running = true;
        targetPackageName = FOX_APP_PACKAGE;
        terminalSignalUntil = 0;
        latestRangeSliderEvent.set(null);
        latestTextSliderEvent.set(null);
        pendingRangeSliderKey.set(null);
        ScanMetrics metrics = new ScanMetrics();
        activeMoveCount = 0;
        worker.execute(() -> {
            try {
                scanGame(metrics);
            } catch (CancellationException e) {
                metrics.finishCancelled(activeMoveCount);
                publish("読み取りを停止しました（未保存）");
            } catch (Throwable e) {
                metrics.finishFailure(activeMoveCount, e);
                String message = "処理を停止しました：" + cleanMessage(e);
                publish(message);
                main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            } finally {
                running = false;
                restoreOverlayPosition();
            }
        });
    }

    private void scanGame(ScanMetrics metrics) throws Exception {
        long setupStarted = metrics.mark();
        publish("1/3 画面画像を取得中…");
        Frame first = captureLatestFrame(null);
        first = recoverBlockingDialog(first);
        publish("2/3 盤面を検出しました");
        dockOverlayBelowBoard(first.detection().board());
        first.recycle();

        Thread.sleep(180);
        first = captureLatestFrame(null);
        first = recoverBlockingDialog(first);
        ScreenGeometry geometry = ScreenGeometry.detect(first.bitmap(), first.detection().board());
        metrics.addPhase("setup", setupStarted);

        publish("3/3 対局情報を画像認識中…");
        long accessibilityStarted = metrics.mark();
        List<MetadataReader.UiText> accessibilityTexts = collectWindowTextsOnMain();
        metrics.addPhase("accessibility", accessibilityStarted);

        GameMetadata meta;
        CompletableFuture<GameMetadata> enhancedMetadata;
        try {
            meta = MetadataReader.read(first.bitmap(), first.detection().board(),
                    accessibilityTexts, List.of());
            logMetadata("accessibility", meta);
            enhancedMetadata = startMetadataEnhancement(
                    first.bitmap(), first.detection().board(), accessibilityTexts, meta, metrics);
            publish("認識結果：黒 " + meta.blackDisplay() + "／白 " + meta.whiteDisplay()
                    + "／手合 " + meta.handicapText
                    + (meta.result.isBlank() ? "" : "／結果 " + meta.result));
        } finally {
            first.recycle();
        }

        long rewindStarted = metrics.mark();
        Frame initial = rewindToInitial(geometry, meta);
        metrics.addPhase("rewind", rewindStarted);
        BoardState current = initial.detection().state();
        meta.initialPosition = current;
        initial.recycle();

        byte expected = meta.handicap > 1 ? BoardState.WHITE : BoardState.BLACK;
        long replayStarted = metrics.mark();
        SliderReplayResult sliderReplay = trySliderReplay(
                geometry, current, expected, meta, metrics);
        if (sliderReplay.succeeded()) {
            List<Move> moves = new ArrayList<>(sliderReplay.moves());
            activeMoveCount = moves.size();
            metrics.addPhase("replay", replayStarted);
            checkCancelled();
            meta = resolveMetadata(meta, enhancedMetadata, metrics);
            checkCancelled();
            saveCompletedGame(meta, moves, metrics);
            return;
        }

        metrics.sliderFallback();
        publish("スライダー高速読取を利用できないため、安全読取へ戻します："
                + sliderReplay.fallbackReason());
        activeMoveCount = 0;
        Frame fallbackInitial = rewindToInitial(geometry, meta);
        current = fallbackInitial.detection().state();
        if (!current.equals(meta.initialPosition)) {
            fallbackInitial.recycle();
            throw new IllegalStateException("高速読取後に初期局面へ安全に復帰できません");
        }
        BoardFrameFingerprint fingerprint = BoardFrameFingerprint.capture(
                new BitmapPixels(fallbackInitial.bitmap()), geometry.board);
        double progress = fallbackInitial.sliderProgress();
        fallbackInitial.recycle();

        List<Move> moves = new ArrayList<>();
        expected = meta.handicap > 1 ? BoardState.WHITE : BoardState.BLACK;
        long lastProgressUpdate = 0L;
        for (int turn = 1; turn <= MAX_MOVES; turn++) {
            checkCancelled();
            long now = SystemClock.uptimeMillis();
            if (turn == 1 || now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                setOverlayStatus("棋譜読取中：" + moves.size() + "手");
                lastProgressUpdate = now;
            }
            Outcome outcome = advanceOne(geometry, current, fingerprint, progress, expected, metrics);
            if (outcome.type() == OutcomeType.END) {
                metrics.addPhase("replay", replayStarted);
                checkCancelled();
                meta = resolveMetadata(meta, enhancedMetadata, metrics);
                checkCancelled();
                saveCompletedGame(meta, moves, metrics);
                return;
            }
            if (outcome.type() == OutcomeType.FAILURE) {
                throw new IllegalStateException(outcome.message() + "。SGFは保存していません");
            }
            moves.add(outcome.move());
            activeMoveCount = moves.size();
            current = outcome.after();
            fingerprint = outcome.fingerprint();
            progress = outcome.sliderProgress();
            expected = expected == BoardState.BLACK ? BoardState.WHITE : BoardState.BLACK;
        }
        throw new IllegalStateException(MAX_MOVES + "手に到達しても終局を確認できません。SGFは保存していません");
    }

    private void saveCompletedGame(GameMetadata meta, List<Move> moves,
                                   ScanMetrics metrics) throws Exception {
        checkCancelled();
        long saveStarted = metrics.mark();
        publish("対局情報を確認・編集してください");
        GameMetadata edited = awaitMetadataEditor(meta);
        checkCancelled();
        publish("SGFを保存中…");
        LocalDate date = LocalDate.now();
        String sgf = SgfWriter.build(edited, moves, date);
        checkCancelled();
        SgfStore.save(this, edited, sgf, date);
        metrics.addPhase("save", saveStarted);
        metrics.finishSuccess(moves.size());
        publish("保存完了：Download/" + safeName(edited.blackName) + "_vs_"
                + safeName(edited.whiteName) + "_" + date.toString().replace("-", "")
                + ".sgf（" + moves.size() + "手／"
                + String.format(java.util.Locale.ROOT, "%.2f", metrics.elapsedSeconds()) + "秒）");
    }

    private GameMetadata awaitMetadataEditor(GameMetadata source) throws Exception {
        CompletableFuture<GameMetadata> result = new CompletableFuture<>();
        main.post(() -> showMetadataEditor(source, result));
        while (true) {
            checkCancelled();
            try {
                GameMetadata edited = result.get(250, TimeUnit.MILLISECONDS);
                if (edited == null) throw new CancellationException("対局情報の編集をキャンセルしました");
                return edited;
            } catch (TimeoutException ignored) {
                // Keep cancellation responsive while the user is editing.
            }
        }
    }

    private void showMetadataEditor(GameMetadata source, CompletableFuture<GameMetadata> result) {
        if (metadataEditor != null) return;
        GameMetadata draft = copyMetadata(source);
        draft.date = textOrDefault(draft.date, LocalDate.now().toString());
        draft.event = textOrDefault(draft.event, GameMetadata.DEFAULT_EVENT);
        draft.place = textOrDefault(draft.place, GameMetadata.DEFAULT_PLACE);
        draft.rule = textOrDefault(draft.rule, GameMetadata.DEFAULT_RULE);
        if (draft.komi == null || draft.komi.isBlank()) draft.applyDefaultKomi();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        panel.setBackground(round(0xF2182B40, 0xFF35C7EA, 16));
        panel.setFocusableInTouchMode(true);

        TextView title = new TextView(this);
        title.setText("対局情報の確認・編集");
        title.setTextSize(19);
        title.setTextColor(0xFFE7F7FF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(38)));

        TextView hint = new TextView(this);
        hint.setText("読み取り結果を確認し、必要に応じて修正してから保存してください。");
        hint.setTextSize(12);
        hint.setTextColor(0xFFB8CBD8);
        hint.setPadding(0, 0, 0, dp(6));
        panel.addView(hint);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText date = addMetadataField(fields, "*対局日時", draft.date, InputType.TYPE_CLASS_DATETIME);
        EditText event = addMetadataField(fields, "大会名", draft.event, InputType.TYPE_CLASS_TEXT);
        EditText place = addMetadataField(fields, "対局場所", draft.place, InputType.TYPE_CLASS_TEXT);
        EditText blackName = addMetadataField(fields, "*黒番名", draft.blackName, InputType.TYPE_CLASS_TEXT);
        EditText blackRank = addMetadataField(fields, "黒番級位", draft.blackRank, InputType.TYPE_CLASS_TEXT);
        EditText whiteName = addMetadataField(fields, "*白番名", draft.whiteName, InputType.TYPE_CLASS_TEXT);
        EditText whiteRank = addMetadataField(fields, "白番級位", draft.whiteRank, InputType.TYPE_CLASS_TEXT);
        EditText rule = addMetadataField(fields, "*ルール", draft.rule, InputType.TYPE_CLASS_TEXT);
        EditText komi = addMetadataField(fields, "*コミ", draft.komi, InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText resultText = addMetadataField(fields, "*勝敗", draft.result, InputType.TYPE_CLASS_TEXT);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(fields, new ScrollView.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView validation = new TextView(this);
        validation.setTextSize(12);
        validation.setTextColor(0xFFFFB4AB);
        validation.setPadding(0, dp(5), 0, dp(3));
        panel.addView(validation, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(32)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = smallButton("キャンセル");
        Button save = smallButton("保存");
        save.setBackground(round(0xFF00BCD4, 0xFF5B788E, 10));
        buttons.addView(cancel);
        buttons.addView(save);
        panel.addView(buttons, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(48)));

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateMetadataValidation(save, validation, draft, date, event, place,
                        blackName, blackRank, whiteName, whiteRank, rule, komi, resultText);
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        EditText[] inputs = {date, event, place, blackName, blackRank, whiteName,
                whiteRank, rule, komi, resultText};
        for (EditText input : inputs) input.addTextChangedListener(watcher);

        cancel.setOnClickListener(v -> {
            closeMetadataEditor();
            result.complete(null);
        });
        save.setOnClickListener(v -> {
            updateDraft(draft, date, event, place, blackName, blackRank, whiteName,
                    whiteRank, rule, komi, resultText);
            String error = GameMetadataValidator.validate(draft);
            if (!error.isBlank()) {
                validation.setText(error);
                return;
            }
            closeMetadataEditor();
            result.complete(draft);
        });

        metadataEditor = panel;
        metadataEditorParams = new WindowManager.LayoutParams(
                screenWidthPixels(0.8f), screenHeightPixels(0.8f),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT);
        metadataEditorParams.gravity = Gravity.CENTER;
        metadataEditorParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (overlay != null) overlay.setVisibility(View.INVISIBLE);
        try {
            windowManager.addView(metadataEditor, metadataEditorParams);
            panel.requestFocus();
            updateMetadataValidation(save, validation, draft, date, event, place,
                    blackName, blackRank, whiteName, whiteRank, rule, komi, resultText);
        } catch (Throwable error) {
            metadataEditor = null;
            metadataEditorParams = null;
            if (overlay != null) overlay.setVisibility(View.VISIBLE);
            result.completeExceptionally(error);
        }
    }

    private EditText addMetadataField(LinearLayout parent, String label, String value, int inputType) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(12);
        caption.setTextColor(0xFF8CEBFF);
        caption.setPadding(0, dp(3), 0, dp(1));
        parent.addView(caption, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(22)));
        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setTextSize(15);
        input.setTextColor(0xFFE7F7FF);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(round(0xFF0D3855, 0xFF1B6F9A, 8));
        parent.addView(input, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(40)));
        return input;
    }

    private void updateMetadataValidation(Button save, TextView validation, GameMetadata draft,
                                          EditText date, EditText event, EditText place,
                                          EditText blackName, EditText blackRank,
                                          EditText whiteName, EditText whiteRank,
                                          EditText rule, EditText komi, EditText result) {
        updateDraft(draft, date, event, place, blackName, blackRank, whiteName, whiteRank,
                rule, komi, result);
        String error = GameMetadataValidator.validate(draft);
        save.setEnabled(error.isBlank());
        save.setAlpha(error.isBlank() ? 1f : 0.45f);
        validation.setText(error);
    }

    private void updateDraft(GameMetadata draft, EditText date, EditText event, EditText place,
                             EditText blackName, EditText blackRank, EditText whiteName,
                             EditText whiteRank, EditText rule, EditText komi, EditText result) {
        draft.date = date.getText().toString().trim();
        draft.event = event.getText().toString();
        draft.place = place.getText().toString();
        draft.blackName = blackName.getText().toString();
        draft.blackRank = blackRank.getText().toString();
        draft.whiteName = whiteName.getText().toString();
        draft.whiteRank = whiteRank.getText().toString();
        draft.rule = rule.getText().toString();
        draft.komi = komi.getText().toString().trim();
        draft.result = result.getText().toString().trim();
    }

    private GameMetadata copyMetadata(GameMetadata source) {
        GameMetadata copy = new GameMetadata();
        copy.blackName = source.blackName;
        copy.whiteName = source.whiteName;
        copy.blackRank = source.blackRank;
        copy.whiteRank = source.whiteRank;
        copy.result = source.result;
        copy.handicapText = source.handicapText;
        copy.handicapRecognized = source.handicapRecognized;
        copy.date = source.date;
        copy.event = source.event;
        copy.place = source.place;
        copy.rule = source.rule;
        copy.komi = source.komi;
        copy.handicap = source.handicap;
        copy.initialPosition = source.initialPosition;
        return copy;
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int screenWidthPixels(float ratio) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return Math.max(1, Math.round(metrics.widthPixels * ratio));
    }

    private int screenHeightPixels(float ratio) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return Math.max(1, Math.round(metrics.heightPixels * ratio));
    }

    private void closeMetadataEditor() {
        if (metadataEditor != null) {
            try {
                windowManager.removeView(metadataEditor);
            } catch (Exception ignored) {
            }
        }
        metadataEditor = null;
        metadataEditorParams = null;
        if (overlay != null) overlay.setVisibility(View.VISIBLE);
    }

    private CompletableFuture<GameMetadata> startMetadataEnhancement(
            Bitmap screen, BoardAnalyzer.Region board,
            List<MetadataReader.UiText> accessibilityTexts,
            GameMetadata fallback, ScanMetrics metrics) {
        if (metadataComplete(fallback)) return CompletableFuture.completedFuture(fallback);

        int headerHeight = Math.max(1, Math.min(screen.getHeight(), board.top()));
        Bitmap header = Bitmap.createBitmap(screen, 0, 0, screen.getWidth(), headerHeight);
        metrics.ocrStarted();
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<MetadataReader.UiText> ocrTexts = HeaderTextRecognizer.recognize(
                        header, board, OCR_BACKGROUND_TIMEOUT_MS);
                logHandicapOcrCandidates(ocrTexts, header.getWidth(), board.top());
                GameMetadata recognized = MetadataReader.read(
                        header, board, accessibilityTexts, ocrTexts);
                logMetadata("ocr", recognized);
                return recognized;
            } catch (HeaderTextRecognizer.RecognitionTimeoutException timeout) {
                metrics.ocrTimedOut();
                Log.w("FoxKifuMetadata", "ocr timeout before handicap recognition");
                return fallback;
            } catch (Throwable ignored) {
                Log.w("FoxKifuMetadata", "ocr failed before handicap recognition", ignored);
                return fallback;
            } finally {
                metrics.ocrFinished();
                if (!header.isRecycled()) header.recycle();
            }
        }, ocrWorker);
    }

    private GameMetadata resolveMetadata(GameMetadata scanMetadata,
                                         CompletableFuture<GameMetadata> enhanced,
                                         ScanMetrics metrics) {
        long joinStarted = metrics.mark();
        GameMetadata resolved = scanMetadata;
        long joinBudgetMs = Math.min(OCR_BACKGROUND_TIMEOUT_MS,
                Math.max(0L, TARGET_TOTAL_MS - TARGET_SAVE_RESERVE_MS
                        - metrics.elapsedMillis()));
        try {
            if (enhanced.isDone()) resolved = enhanced.get();
            else if (joinBudgetMs > 0L) {
                resolved = enhanced.get(joinBudgetMs, TimeUnit.MILLISECONDS);
            } else {
                throw new TimeoutException("10秒の処理時間枠を使い切りました");
            }
        } catch (TimeoutException timeout) {
            enhanced.cancel(false);
            metrics.ocrTimedOut();
            publish("対局情報OCRの完了を待たず、棋譜を保存します");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException();
            cancellation.initCause(interrupted);
            throw cancellation;
        } catch (Throwable ignored) {
            // The accessibility-derived metadata remains a complete safe fallback.
        } finally {
            metrics.addPhase("ocrJoin", joinStarted);
        }

        if (resolved == null) resolved = scanMetadata;
        resolved.initialPosition = scanMetadata.initialPosition;
        if (scanMetadata.handicapRecognized) {
            resolved.handicap = scanMetadata.handicap;
            resolved.handicapText = scanMetadata.handicapText;
            resolved.handicapRecognized = true;
        }
        resolved.applyDefaultKomi();
        logMetadata("resolved", resolved);
        publish("確定情報：黒 " + resolved.blackDisplay() + "／白 "
                + resolved.whiteDisplay()
                + "／手合 " + resolved.handicapText
                + (resolved.result.isBlank() ? "" : "／結果 " + resolved.result));
        return resolved;
    }

    private static boolean metadataComplete(GameMetadata meta) {
        return meta != null
                && meta.blackName != null && !meta.blackName.isBlank()
                && !meta.blackName.contains("不明")
                && meta.whiteName != null && !meta.whiteName.isBlank()
                && !meta.whiteName.contains("不明")
                && !meta.blackRank.isBlank()
                && !meta.whiteRank.isBlank()
                && meta.handicapRecognized
                && !meta.result.isBlank();
    }

    private Frame rewindToInitial(ScreenGeometry geometry, GameMetadata meta) throws Exception {
        publish("初期局面を確認中…");
        Frame frame = captureFrame(geometry);
        if (isInitialOrInfer(frame.detection().state(), meta)) return frame;

        publish("手数バーのツマミを左端へ移動しています…");
        frame = rewindSliderWithCalibratedTouch(geometry, frame);
        if (!geometry.isSliderAtLeft(frame.bitmap())) {
            frame.recycle();
            throw new IllegalStateException("手数操作バーのツマミを左端へ移動できません");
        }
        if (isInitialOrInfer(frame.detection().state(), meta)) return frame;

        publish("手数バーだけでは戻りきらないため、一手ずつ戻しています…");
        int unchanged = 0;
        for (int i = 0; i < MAX_MOVES; i++) {
            checkCancelled();
            BoardState before = frame.detection().state();
            frame.recycle();
            gestureTap(geometry, geometry.back);
            Frame next = awaitStable(geometry, before, 1600);
            if (next.detection().state().equals(before)) unchanged++;
            else unchanged = 0;
            if (isInitialOrInfer(next.detection().state(), meta)) return next;
            if (unchanged >= 2) {
                if (inferHandicapAtBeginning(next.detection().state(), meta)) return next;
                next.recycle();
                throw new IllegalStateException("最初の局面まで戻りましたが、手合いと盤上の石数が一致しません");
            }
            frame = next;
        }
        frame.recycle();
        throw new IllegalStateException("初期局面への巻き戻しが上限を超えました");
    }

    /** Prefers a slider-only swipe, then falls back to left-track taps. */
    private Frame rewindSliderWithCalibratedTouch(ScreenGeometry geometry, Frame frame)
            throws Exception {
        if (geometry.isSliderAtLeft(frame.bitmap())) return frame;
        float swipeY = geometry.usesHomeIndicatorAdjustedTouch()
                ? sliderOnlySwipeYForVisualControl(
                        geometry.visualControlY(), geometry.screenHeight())
                : geometry.sliderLeft.y;
        publish("スライダだけが反応する位置で巻き戻しています（Y=" + swipeY + "）");
        SliderTouchProbe swipe = swipeSliderAtY(
                geometry, frame, swipeY, geometry.sliderLeft.x);
        frame = swipe.frame();
        if (!swipe.homeBarReacted() && geometry.isSliderAtLeft(frame.bitmap())) return frame;

        float tapY = geometry.usesHomeIndicatorAdjustedTouch()
                ? operationYForVisualControl(
                        geometry.visualControlY(), geometry.screenHeight())
                : geometry.sliderLeft.y;
        publish("スワイプだけでは戻りきらないため左端をタップします（Y=" + tapY + "）");
        for (int attempt = 0; attempt < 3; attempt++) {
            SliderTouchProbe probe = tapSliderAtY(
                    geometry, frame, tapY, geometry.sliderLeft.x);
            frame = probe.frame();
            if (probe.homeBarReacted()) {
                frame.recycle();
                throw new IllegalStateException("調整したY座標でホームバーが反応しました");
            }
            if (geometry.isSliderAtLeft(frame.bitmap())) return frame;
            if (!probe.sliderMoved()) {
                frame.recycle();
                throw new IllegalStateException("調整したY座標のタップでスライダを操作できません");
            }
        }
        frame.recycle();
        throw new IllegalStateException("安全なタップを繰り返してもツマミを左端へ移動できません");
    }

    private SliderTouchProbe swipeSliderAtY(ScreenGeometry geometry, Frame frame,
                                             float y, float targetX) throws Exception {
        double beforeProgress = geometry.sliderProgress(frame.bitmap());
        BoardState before = frame.detection().state();
        PointF thumb = geometry.sliderThumb(frame.bitmap());
        PointF from = pointAtY(geometry, thumb.x, y);
        PointF to = pointAtY(geometry, targetX, y);
        frame.recycle();
        gestureSwipe(geometry, from, to, 420);
        return awaitSliderGestureResult(geometry, before, beforeProgress);
    }

    private SliderTouchProbe tapSliderAtY(ScreenGeometry geometry, Frame frame,
                                          float y, float targetX) throws Exception {
        double beforeProgress = geometry.sliderProgress(frame.bitmap());
        BoardState before = frame.detection().state();
        PointF target = pointAtY(geometry, targetX, y);
        frame.recycle();
        gestureTap(geometry, target);
        return awaitSliderGestureResult(geometry, before, beforeProgress);
    }

    private SliderTouchProbe awaitSliderGestureResult(ScreenGeometry geometry,
                                                       BoardState before,
                                                       double beforeProgress) throws Exception {
        boolean homeBarReacted = !targetAppIsForeground();
        if (homeBarReacted) {
            performGlobalBack();
            if (!awaitTargetAppForeground(2500)) {
                throw new IllegalStateException("ホームバー反応後に野狐の画面へ復帰できません");
            }
        }
        Frame after = awaitStable(geometry, before, 3000);
        homeBarReacted = homeBarReacted || !targetAppIsForeground();
        if (homeBarReacted && !awaitTargetAppForeground(1500)) {
            after.recycle();
            throw new IllegalStateException("ホームバー反応後に野狐の画面へ復帰できません");
        }
        double afterProgress = geometry.sliderProgress(after.bitmap());
        boolean moved = geometry.isSliderAtLeft(after.bitmap())
                || progressMovedTowardLeft(beforeProgress, afterProgress);
        return new SliderTouchProbe(after, moved, homeBarReacted);
    }

    private static boolean progressMovedTowardLeft(double before, double after) {
        return before >= 0 && after >= 0 && before - after > 0.008;
    }

    static float operationYForScore4(int sliderOnlyY, int sliderAndHomeY) {
        if (sliderAndHomeY <= sliderOnlyY) {
            throw new IllegalArgumentException("両方反応するY座標はスライダのみのY座標より下側である必要があります");
        }
        return sliderOnlyY
                + (sliderAndHomeY - sliderOnlyY) * (4.0f - 1.0f) / (10.0f - 1.0f);
    }

    static float operationYForVisualControl(float visualControlY, int screenHeight) {
        float scale = Math.max(1, screenHeight) / 1280.0f;
        int sliderOnlyY = Math.round(visualControlY - 40.0f * scale);
        int sliderAndHomeY = Math.round(visualControlY + 5.0f * scale);
        return operationYForScore4(sliderOnlyY, sliderAndHomeY);
    }

    static float sliderOnlySwipeYForVisualControl(float visualControlY, int screenHeight) {
        float scale = Math.max(1, screenHeight) / 1280.0f;
        return Math.round(visualControlY - 13.0f * scale);
    }

    private static PointF pointAtY(ScreenGeometry geometry, float x, float y) {
        PointF point = new PointF(x, y);
        geometry.requireSafe(point);
        return point;
    }

    private boolean targetAppIsForeground() {
        String target = targetPackageName;
        if (target == null || target.isBlank()) return true;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    try {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) continue;
                        try {
                            CharSequence packageName = root.getPackageName();
                            if (packageName != null && target.contentEquals(packageName)) return true;
                        } finally {
                            root.recycle();
                        }
                    } finally {
                        window.recycle();
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean awaitTargetAppForeground(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        do {
            if (targetAppIsForeground()) return true;
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) return false;
            Thread.sleep(Math.min(100L, remaining));
        } while (true);
    }

    private boolean isInitialOrInfer(BoardState state, GameMetadata meta) {
        return reconcileInitialPosition(state, meta);
    }

    static boolean reconcileInitialPosition(BoardState state, GameMetadata meta) {
        if (BoardAnalyzer.isInitial(state, 0)) {
            if (meta.handicap > 1) return false;
            meta.applyDefaultKomi();
            return true;
        }
        if (BoardAnalyzer.isInitial(state, meta.handicap)) return true;
        return !meta.handicapRecognized && meta.handicap == 0
                && inferHandicapAtBeginning(state, meta);
    }

    private static boolean inferHandicapAtBeginning(BoardState state, GameMetadata meta) {
        int black = state.count(BoardState.BLACK);
        if (black >= 2 && black <= 9 && BoardAnalyzer.isInitial(state, black)) {
            meta.handicap = black;
            meta.handicapText = black + "子";
            meta.applyDefaultKomi();
            logMetadata("board-inferred", meta);
            return true;
        }
        return false;
    }

    private static void logMetadata(String stage, GameMetadata meta) {
        Log.i("FoxKifuMetadata", stage + " handicapText=" + meta.handicapText
                + " handicap=" + meta.handicap
                + " recognized=" + meta.handicapRecognized
                + " komi=" + meta.komi);
    }

    private static void logHandicapOcrCandidates(List<MetadataReader.UiText> texts,
                                                  int width, int boardTop) {
        int rowBottom = boardTop - (int) Math.round(width * .09);
        int rightEdge = (int) Math.round(width * .58);
        for (MetadataReader.UiText item : texts) {
            Rect bounds = item.bounds();
            if (bounds.centerX() <= rightEdge && bounds.centerY() <= rowBottom) {
                Log.i("FoxKifuMetadata", "title-candidate text=" + item.text()
                        + " bounds=" + bounds.flattenToString());
            }
        }
    }

    /**
     * Experimental super-high-speed path. It only runs when the replay control exposes an
     * exact current/total index. Visual thumb progress alone is never treated as a move number.
     */
    private SliderReplayResult trySliderReplay(ScreenGeometry geometry, BoardState initial,
                                               byte firstColor, GameMetadata meta,
                                               ScanMetrics metrics) throws Exception {
        try {
            SliderIndexTextParser.Snapshot initialText = readSliderTextIndex(geometry);
            boolean canTryText = isInitialTextIndex(initialText);
            SliderRangeSnapshot range = findReplayRangeSliderOnMain(geometry, null);
            if (range != null && range.range().currentIndex() == 0
                    && range.range().moveCount() > 0) {
                metrics.sliderStarted("range");
                SliderReplayResult rangeResult;
                try {
                    rangeResult = replayRangeSlider(
                            geometry, range, initial, firstColor, metrics);
                } catch (CancellationException | InterruptedException error) {
                    throw error;
                } catch (Throwable error) {
                    rangeResult = SliderReplayResult.fallback("離散Range経路の検証に失敗しました（"
                            + error.getClass().getSimpleName() + "）");
                }
                if (rangeResult.succeeded() || !canTryText) return rangeResult;

                Frame rewound = rewindToInitial(geometry, meta);
                PointF resetThumb;
                try {
                    if (!rewound.detection().state().equals(initial)) {
                        return SliderReplayResult.fallback(rangeResult.fallbackReason()
                                + "／TEXT経路用の初期局面と一致しません");
                    }
                    resetThumb = geometry.sliderThumb(rewound.bitmap());
                } catch (IllegalStateException missingThumb) {
                    return SliderReplayResult.fallback(rangeResult.fallbackReason()
                            + "／TEXT経路用のツマミを確認できません");
                } finally {
                    rewound.recycle();
                }

                SliderIndexTextParser.Snapshot resetText = readSliderTextIndex(geometry);
                if (!isInitialTextIndex(resetText) || resetText.total() != initialText.total()) {
                    return SliderReplayResult.fallback(rangeResult.fallbackReason()
                            + "／TEXT経路用の0手目へ安全に復帰できません");
                }
                activeMoveCount = 0;
                metrics.sliderStarted("range->text");
                SliderReplayResult textResult = replayTextSlider(
                        geometry, initial, firstColor, resetText, resetThumb, metrics);
                if (textResult.succeeded()) return textResult;
                return SliderReplayResult.fallback(rangeResult.fallbackReason()
                        + "／TEXT経路: " + textResult.fallbackReason());
            }

            if (!canTryText) {
                metrics.sliderStarted("unsupported");
                return SliderReplayResult.fallback("手数バーの正確な現在手／総手数を取得できません");
            }
            PointF thumb = latestSliderThumb(geometry);
            if (thumb == null) {
                return SliderReplayResult.fallback("手数バーのツマミを再取得できません");
            }
            metrics.sliderStarted("text");
            return replayTextSlider(
                    geometry, initial, firstColor, initialText, thumb, metrics);
        } catch (CancellationException | InterruptedException error) {
            throw error;
        } catch (Throwable error) {
            return SliderReplayResult.fallback("高速経路の検証に失敗しました（"
                    + error.getClass().getSimpleName() + "）");
        } finally {
            shsFramePixels.release();
        }
    }

    private static boolean isInitialTextIndex(SliderIndexTextParser.Snapshot text) {
        return text != null && text.current() == 0 && text.total() > 0;
    }

    private void activateSliderSession(SliderSession session) {
        SliderSession previous = activeSliderSession.getAndSet(session);
        if (previous != null && previous != session) previous.close();
    }

    private void closeSliderSession(SliderSession session) {
        activeSliderSession.compareAndSet(session, null);
        session.close();
    }

    private void closeActiveSliderSession() {
        SliderSession session = activeSliderSession.getAndSet(null);
        if (session != null) session.close();
    }

    private SliderReplayResult replayTextSlider(ScreenGeometry geometry, BoardState initial,
                                                byte firstColor,
                                                SliderIndexTextParser.Snapshot text,
                                                PointF thumb, ScanMetrics metrics)
            throws Exception {
        if (geometry.sliderTrackWidth() / text.total() < SHS_TEXT_MIN_PIXELS_PER_MOVE) {
            return SliderReplayResult.fallback("画面上の手数バーでは全手を個別指定できません");
        }
        SliderSeekPlan plan = SliderSeekPlan.calibrate(
                new SliderSeekPlan.Snapshot(0, text.total(), 0),
                new SliderSeekPlan.Snapshot(0, text.total(), 1));
        SliderSession session = new SliderSession(
                SliderMode.TEXT, null, plan, plan.totalMoves(), geometry, null);
        activateSliderSession(session);
        try {
            return replaySliderIndexes(geometry, session, 0, initial, firstColor,
                    new ArrayList<>(), new ArrayList<>(List.of(initial)), thumb, metrics);
        } finally {
            closeSliderSession(session);
        }
    }

    private SliderReplayResult replayRangeSlider(ScreenGeometry geometry,
                                                  SliderRangeSnapshot initialRange,
                                                  BoardState initial,
                                                  byte firstColor,
                                                  ScanMetrics metrics) throws Exception {
        checkCancelled();
        pendingRangeSliderKey.set(initialRange.key());
        try {
            long gestureStarted = metrics.mark();
            SliderAction firstAction = gestureTap(geometry, geometry.forward);
            metrics.addGesture(gestureStarted);

            SliderRangeSnapshot afterOne = awaitChangedRangeSlider(
                    geometry, initialRange, firstAction, SHS_INDEX_ACK_TIMEOUT_MS);
            if (afterOne == null) {
                return SliderReplayResult.fallback("一手進めた後の離散インデックスを確認できません");
            }

            SliderSeekPlan plan;
            try {
                plan = SliderSeekPlan.calibrate(initialRange.raw(), afterOne.raw());
            } catch (IllegalArgumentException invalidRange) {
                return SliderReplayResult.fallback("手数バーの刻み幅を一手単位に校正できません");
            }
            if (plan.totalMoves() < 1 || plan.totalMoves() > MAX_MOVES
                    || !plan.isAtIndex(afterOne.raw().current(), 1)) {
                return SliderReplayResult.fallback("手数バーの総手数を安全に確定できません");
            }
            AccessibilityNodeInfo cachedRangeNode = obtainRangeSliderNodeOnMain(
                    geometry, initialRange.key(), plan, 1);
            SliderSession session = new SliderSession(SliderMode.RANGE,
                    initialRange.key(), plan, plan.totalMoves(), geometry, cachedRangeNode);
            activateSliderSession(session);
            pendingRangeSliderKey.compareAndSet(initialRange.key(), null);
            try {
                SliderStep first = awaitSliderStep(geometry, session, 1, initial, firstColor,
                        null, firstAction, metrics);
                if (first == null) {
                    return SliderReplayResult.fallback("高速経路で1手目の盤面を確定できません");
                }

                ArrayList<Move> moves = new ArrayList<>();
                moves.add(first.move());
                metrics.acceptedSliderMove();
                activeMoveCount = 1;
                ArrayList<BoardState> history = new ArrayList<>();
                history.add(initial);
                history.add(first.state());
                byte nextColor = firstColor == BoardState.BLACK
                        ? BoardState.WHITE : BoardState.BLACK;
                return replaySliderIndexes(geometry, session, 1, first.state(), nextColor,
                        moves, history, first.thumb(), metrics);
            } finally {
                closeSliderSession(session);
            }
        } finally {
            pendingRangeSliderKey.compareAndSet(initialRange.key(), null);
        }
    }

    private SliderReplayResult replaySliderIndexes(ScreenGeometry geometry,
                                                    SliderSession session,
                                                    int completedIndexes,
                                                    BoardState current,
                                                    byte expected,
                                                    ArrayList<Move> moves,
                                                    ArrayList<BoardState> history,
                                                    PointF currentThumb,
                                                    ScanMetrics metrics) throws Exception {
        long lastProgressUpdate = 0L;
        for (int index = completedIndexes + 1; index <= session.totalMoves(); index++) {
            checkCancelled();
            SliderAction action = seekSliderIndex(
                    geometry, session, index - 1, index, currentThumb, metrics);
            if (action == null) {
                return SliderReplayResult.fallback("手数バーを" + index + "手目へ移動できません");
            }

            BoardState twoPliesAgo = history.size() >= 2
                    ? history.get(history.size() - 2) : null;
            SliderStep step = awaitSliderStep(geometry, session, index, current, expected,
                    twoPliesAgo, action, metrics);
            if (step == null) {
                return SliderReplayResult.fallback(index + "手目の局面を一手として検証できません");
            }

            moves.add(step.move());
            history.add(step.state());
            current = step.state();
            currentThumb = step.thumb();
            expected = expected == BoardState.BLACK ? BoardState.WHITE : BoardState.BLACK;
            activeMoveCount = moves.size();
            metrics.acceptedSliderMove();

            long now = SystemClock.uptimeMillis();
            if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                setOverlayStatus("SHS高速読取：" + index + "/" + session.totalMoves() + "手");
                lastProgressUpdate = now;
            }
        }

        SliderSeekPlan.Snapshot finalSnapshot = readSliderSessionSnapshot(geometry, session);
        if (finalSnapshot == null
                || !session.plan().matchesRange(finalSnapshot)
                || !session.plan().isAtIndex(finalSnapshot.current(), session.totalMoves())
                || moves.size() != session.totalMoves()) {
            return SliderReplayResult.fallback("手数バー右端の最終局面を確認できません");
        }
        return SliderReplayResult.success(moves);
    }

    private SliderAction seekSliderIndex(ScreenGeometry geometry, SliderSession session,
                                         int previousIndex, int targetIndex, PointF currentThumb,
                                         ScanMetrics metrics) throws Exception {
        long seekStarted = metrics.mark();
        try {
            if (session.mode() == SliderMode.RANGE) {
                return setRangeSliderIndexOnMain(session, previousIndex, targetIndex);
            }
            if (currentThumb == null) return null;
            double progress = targetIndex / (double) session.totalMoves();
            PointF target = geometry.sliderPoint(progress);
            return gestureSwipe(geometry, currentThumb, target, SHS_SEEK_DURATION_MS);
        } finally {
            metrics.addSliderSeek(seekStarted);
        }
    }

    private SliderAcknowledgement awaitSliderAcknowledgement(
            ScreenGeometry geometry, SliderSession session, int targetIndex,
            SliderAction action, ScanMetrics metrics) throws Exception {
        long deadline = SystemClock.uptimeMillis() + SHS_INDEX_ACK_TIMEOUT_MS;
        long eventOnlyDeadline = Math.min(deadline, SystemClock.uptimeMillis() + 40L);
        while (SystemClock.uptimeMillis() < eventOnlyDeadline) {
            checkCancelled();
            SliderAcknowledgement event = sliderEventAcknowledgement(
                    geometry, session, targetIndex, action.startedUptimeMillis());
            if (event != null) {
                metrics.sliderEventAck();
                return event;
            }
            Thread.sleep(SHS_ACK_POLL_MS);
        }

        SliderSeekPlan.Snapshot observed = readSliderSessionSnapshot(geometry, session);
        if (isAcknowledgedIndex(session, observed, targetIndex)) {
            metrics.sliderSnapshotAck();
            long now = SystemClock.uptimeMillis();
            return new SliderAcknowledgement(observed, now, now,
                    CaptureService.currentSequence(), false);
        }
        if (observed != null && session.plan().matchesRange(observed)
                && session.plan().indexOf(observed.current()) > targetIndex) return null;

        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled();
            SliderAcknowledgement event = sliderEventAcknowledgement(
                    geometry, session, targetIndex, action.startedUptimeMillis());
            if (event != null) {
                metrics.sliderEventAck();
                return event;
            }
            Thread.sleep(SHS_ACK_POLL_MS);
        }
        return null;
    }

    private SliderAcknowledgement sliderEventAcknowledgement(
            ScreenGeometry geometry, SliderSession session, int targetIndex,
            long actionStartedUptimeMillis) {
        SliderSeekPlan.Snapshot snapshot;
        SliderEventStamp stamp;
        if (session.mode() == SliderMode.RANGE) {
            CachedRangeSliderEvent event = latestRangeSliderEvent();
            if (event == null
                    || event.stamp().eventUptimeMillis() < actionStartedUptimeMillis
                    || !matchesSliderKey(session.key(), event.stamp().source().sliderKey())) {
                return null;
            }
            snapshot = event.raw();
            stamp = event.stamp();
        } else {
            CachedTextSliderEvent event = latestTextSliderEvent();
            if (event == null
                    || event.stamp().eventUptimeMillis() < actionStartedUptimeMillis
                    || event.index().total() != session.totalMoves()
                    || !geometry.isReplayControlRow(event.stamp().source().sliderKey().bounds())) {
                return null;
            }
            snapshot = new SliderSeekPlan.Snapshot(
                    0, event.index().total(), event.index().current());
            stamp = event.stamp();
        }
        if (!isAcknowledgedIndex(session, snapshot, targetIndex)) return null;
        return new SliderAcknowledgement(snapshot, stamp.eventUptimeMillis(),
                stamp.receivedUptimeMillis(), stamp.captureSequence(), true);
    }

    private static boolean isAcknowledgedIndex(SliderSession session,
                                                SliderSeekPlan.Snapshot snapshot,
                                                int targetIndex) {
        return snapshot != null
                && session.plan().matchesRange(snapshot)
                && session.plan().isAtIndex(snapshot.current(), targetIndex);
    }

    private boolean sliderSessionStillAtIndex(
            ScreenGeometry geometry, SliderSession session, int targetIndex,
            SliderAcknowledgement acknowledged) {
        if (session.mode() == SliderMode.RANGE) {
            CachedRangeSliderEvent event = latestRangeSliderEvent();
            if (event == null
                    || event.stamp().eventUptimeMillis() < acknowledged.eventUptimeMillis()
                    || event.stamp().receivedUptimeMillis() < acknowledged.receivedUptimeMillis()
                    || !matchesSliderKey(session.key(), event.stamp().source().sliderKey())) {
                return true;
            }
            return isAcknowledgedIndex(session, event.raw(), targetIndex);
        }
        CachedTextSliderEvent event = latestTextSliderEvent();
        if (event == null
                || event.stamp().eventUptimeMillis() < acknowledged.eventUptimeMillis()
                || event.stamp().receivedUptimeMillis() < acknowledged.receivedUptimeMillis()
                || event.index().total() != session.totalMoves()
                || !geometry.isReplayControlRow(event.stamp().source().sliderKey().bounds())) {
            return true;
        }
        SliderSeekPlan.Snapshot snapshot = new SliderSeekPlan.Snapshot(
                0, event.index().total(), event.index().current());
        return isAcknowledgedIndex(session, snapshot, targetIndex);
    }

    private SliderStep awaitSliderStep(ScreenGeometry geometry, SliderSession session,
                                       int targetIndex, BoardState before, byte expected,
                                       BoardState twoPliesAgo, SliderAction action,
                                       ScanMetrics metrics) throws Exception {
        SliderAcknowledgement acknowledged = awaitSliderAcknowledgement(
                geometry, session, targetIndex, action, metrics);
        if (acknowledged == null) return null;

        long acknowledgedAt = acknowledged.receivedUptimeMillis();
        // Frames produced between dispatch and ACK can already contain the completed ordinary
        // move, so inspect them instead of discarding them. They may commit a legal board change,
        // but only a frame strictly newer than the ACK may count as unchanged/pass evidence.
        long sequence = action.frameSequence();
        long deadline = acknowledgedAt + SHS_STEP_TIMEOUT_MS;
        SliderPassGate passGate = new SliderPassGate(acknowledgedAt);
        long lastPassProbeAt = Long.MIN_VALUE;
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled();
            long now = SystemClock.uptimeMillis();
            long remaining = deadline - now;
            boolean passWasReady = passGate.canRequestExplicitPassConfirmation(now);
            boolean passEvidenceWindow = passGate.canRequestAdditionalFreshEvidence(now);
            boolean passProbeCooledDown = lastPassProbeAt == Long.MIN_VALUE
                    || now - lastPassProbeAt >= SHS_PASS_PROBE_INTERVAL_MS;
            boolean explicitPassCheck = passEvidenceWindow && passProbeCooledDown;
            long waitStarted = metrics.mark();
            CaptureService.CapturedFrame captured = null;
            Bitmap bitmap;
            if (explicitPassCheck) {
                lastPassProbeAt = now;
                bitmap = captureWithAccessibility(Math.min(
                        SHS_PASS_SCREENSHOT_TIMEOUT_MS, Math.max(1L, remaining)));
                metrics.sliderPassProbe();
            } else {
                long captureWaitMs = Math.min(FRESH_FRAME_WAIT_MS,
                        Math.min(SliderPassGate.MIN_QUIET_MS, Math.max(1L, remaining)));
                if (passEvidenceWindow && lastPassProbeAt != Long.MIN_VALUE) {
                    long untilNextProbe = SHS_PASS_PROBE_INTERVAL_MS
                            - Math.max(0L, now - lastPassProbeAt);
                    captureWaitMs = Math.min(captureWaitMs, Math.max(1L, untilNextProbe));
                }
                captured = CaptureService.captureAfter(sequence, Math.min(
                        captureWaitMs, Math.max(1L, remaining)));
                bitmap = captured == null ? null : captured.bitmap();
            }
            metrics.addFreshFrameWait(waitStarted, bitmap != null);
            if (bitmap == null) {
                if (!CaptureService.isReady()) return null;
                continue;
            }
            long capturedSequence = captured == null ? -1L : captured.sequence();
            if (captured != null) sequence = Math.max(sequence, capturedSequence);
            try {
                if (!sliderSessionStillAtIndex(
                        geometry, session, targetIndex, acknowledged)) {
                    return null;
                }

                shsFramePixels.bind(bitmap, geometry.board, Math.round(geometry.visualControlY()));
                PointF thumb = null;
                if (session.mode() == SliderMode.TEXT) {
                    try {
                        thumb = geometry.sliderThumb(shsFramePixels);
                    } catch (IllegalStateException missingThumb) {
                        passGate.observeInvalidOrPartial(SystemClock.uptimeMillis());
                        continue;
                    }
                    double visualProgress = geometry.sliderProgressForX(thumb.x);
                    double expectedProgress = targetIndex / (double) session.totalMoves();
                    double tolerance = visualIndexTolerance(geometry);
                    if (Math.abs(visualProgress - expectedProgress) > tolerance) {
                        passGate.observeInvalidOrPartial(SystemClock.uptimeMillis());
                        continue;
                    }
                }

                long analyzeStarted = metrics.mark();
                BoardAnalyzer.Detection detection = BoardAnalyzer.detect(
                        shsFramePixels, geometry.board);
                metrics.addBoardAnalysis(analyzeStarted);
                if (detection.confidence() < SHS_MIN_CONFIDENCE) {
                    passGate.observeInvalidOrPartial(SystemClock.uptimeMillis());
                    continue;
                }

                BoardState observed = detection.state();
                SliderStepValidator.Result validation = SliderStepValidator.validate(
                        before, observed, expected, twoPliesAgo);
                if (validation.valid() && validation.move().pass()) {
                    metrics.sliderUnchangedFrame();
                    boolean postAcknowledgementEvidence = explicitPassCheck
                            || capturedSequence > acknowledged.frameSequence();
                    if (!postAcknowledgementEvidence) continue;
                    boolean passReady = passGate.observeUnchangedFreshFrame(
                            SystemClock.uptimeMillis());
                    if (explicitPassCheck && passWasReady && passReady) {
                        SliderSeekPlan.Snapshot confirmed = readSliderSessionSnapshot(
                                geometry, session);
                        if (!isAcknowledgedIndex(session, confirmed, targetIndex)) return null;
                        return new SliderStep(validation.move(), before, thumb);
                    }
                    continue;
                }
                if (validation.valid()) {
                    return new SliderStep(validation.move(), observed, thumb);
                }
                passGate.observeInvalidOrPartial(SystemClock.uptimeMillis());
            } finally {
                shsFramePixels.releaseFrame();
                if (!bitmap.isRecycled()) bitmap.recycle();
            }
        }
        return null;
    }

    private static double visualIndexTolerance(ScreenGeometry geometry) {
        return Math.max(SHS_THUMB_UNCERTAINTY_PIXELS / geometry.sliderTrackWidth(),
                SHS_MIN_VISUAL_PROGRESS_TOLERANCE);
    }

    private PointF latestSliderThumb(ScreenGeometry geometry) {
        CaptureService.CapturedFrame captured = CaptureService.latestFrame();
        if (captured == null) return null;
        try {
            return geometry.sliderThumb(captured.bitmap());
        } catch (IllegalStateException ignored) {
            return null;
        } finally {
            captured.recycle();
        }
    }

    private SliderRangeSnapshot awaitChangedRangeSlider(ScreenGeometry geometry,
                                                        SliderRangeSnapshot initial,
                                                        SliderAction action,
                                                        long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled();
            CachedRangeSliderEvent event = latestRangeSliderEvent();
            SliderRangeSnapshot observed = null;
            if (event != null
                    && event.stamp().eventUptimeMillis() >= action.startedUptimeMillis()
                    && matchesSliderKey(initial.key(), event.stamp().source().sliderKey())) {
                observed = new SliderRangeSnapshot(
                        event.stamp().source().sliderKey(), event.range(), event.raw());
            }
            if (observed != null
                    && observed.range().moveCount() == initial.range().moveCount()
                    && observed.raw().min() == initial.raw().min()
                    && observed.raw().max() == initial.raw().max()
                    && observed.raw().current() > initial.raw().current()) {
                return observed;
            }
            Thread.sleep(SHS_ACK_POLL_MS);
        }
        SliderRangeSnapshot observed = findReplayRangeSliderOnMain(geometry, initial.key());
        if (observed == null
                || observed.range().moveCount() != initial.range().moveCount()
                || observed.raw().min() != initial.raw().min()
                || observed.raw().max() != initial.raw().max()
                || observed.raw().current() <= initial.raw().current()) return null;
        return observed;
    }

    private SliderSeekPlan.Snapshot readSliderSessionSnapshot(
            ScreenGeometry geometry, SliderSession session) throws InterruptedException {
        if (session.mode() == SliderMode.RANGE) {
            AtomicReference<SliderRangeSnapshot> refreshed = new AtomicReference<>();
            runOnMainSync(() -> refreshed.set(refreshCachedRangeSliderNode(
                    session.cachedRangeNode(), session.key())), 350);
            SliderRangeSnapshot range = refreshed.get();
            if (range == null) {
                range = findReplayRangeSliderOnMain(geometry, session.key());
            }
            return range == null ? null : range.raw();
        }
        SliderIndexTextParser.Snapshot text = readSliderTextIndex(geometry);
        if (text == null || text.total() != session.totalMoves()) return null;
        return new SliderSeekPlan.Snapshot(0, text.total(), text.current());
    }

    private SliderIndexTextParser.Snapshot readSliderTextIndex(ScreenGeometry geometry)
            throws InterruptedException {
        SliderIndexTextParser.Snapshot found = null;
        for (MetadataReader.UiText item : collectWindowTextsOnMain()) {
            if (!geometry.isReplayControlRow(item.bounds())) continue;
            SliderIndexTextParser.Snapshot parsed = SliderIndexTextParser.parse(item.text());
            if (parsed == null) continue;
            if (found != null && !found.equals(parsed)) return null;
            found = parsed;
        }
        return found;
    }

    private SliderAction setRangeSliderIndexOnMain(SliderSession session,
                                                   int previousIndex, int targetIndex)
            throws InterruptedException {
        AtomicReference<SliderAction> accepted = new AtomicReference<>();
        runOnMainSync(() -> {
            AccessibilityNodeInfo node = session.cachedRangeNode();
            SliderRangeSnapshot current = refreshCachedRangeSliderNode(node, session.key());
            if (!isExpectedRangePosition(session, current, previousIndex)) {
                session.discardCachedRangeNode(node);
                node = replaceCachedRangeNodeFromTree(session, previousIndex);
            }
            if (node == null) return;

            try {
                SliderAction action = new SliderAction(
                        CaptureService.currentSequence(), SystemClock.uptimeMillis());
                if (performRangeSliderAction(node, session, targetIndex)) accepted.set(action);
            } catch (Throwable ignored) {
                accepted.set(null);
            }

            // A node can become stale between a successful refresh and performAction(). Resolve
            // the exact same slider once more only in that failure case; normal moves never walk
            // the complete accessibility tree.
            if (accepted.get() == null) {
                session.discardCachedRangeNode(node);
                AccessibilityNodeInfo replacement = replaceCachedRangeNodeFromTree(
                        session, previousIndex);
                if (replacement != null) {
                    try {
                        SliderAction action = new SliderAction(
                                CaptureService.currentSequence(), SystemClock.uptimeMillis());
                        if (performRangeSliderAction(replacement, session, targetIndex)) {
                            accepted.set(action);
                        }
                    } catch (Throwable ignored) {
                        accepted.set(null);
                    }
                }
            }
        }, 700);
        return accepted.get();
    }

    private AccessibilityNodeInfo obtainRangeSliderNodeOnMain(
            ScreenGeometry geometry, SliderKey requiredKey,
            SliderSeekPlan plan, int expectedIndex) throws InterruptedException {
        AtomicReference<AccessibilityNodeInfo> result = new AtomicReference<>();
        runOnMainSync(() -> {
            SliderNodeCandidate candidate = findReplayRangeSliderNow(geometry, requiredKey);
            if (candidate == null) return;
            boolean retained = false;
            try {
                SliderSeekPlan.Snapshot current = candidate.snapshot().raw();
                if (plan.matchesRange(current)
                        && plan.isAtIndex(current.current(), expectedIndex)) {
                    result.set(candidate.node());
                    retained = true;
                }
            } finally {
                if (!retained) candidate.node().recycle();
            }
        }, 900);
        return result.get();
    }

    private AccessibilityNodeInfo replaceCachedRangeNodeFromTree(
            SliderSession session, int expectedIndex) {
        SliderNodeCandidate candidate = findReplayRangeSliderNow(null, session.key());
        if (candidate == null) return null;
        boolean retained = false;
        try {
            if (!isExpectedRangePosition(session, candidate.snapshot(), expectedIndex)) {
                return null;
            }
            if (!session.replaceCachedRangeNode(candidate.node())) return null;
            retained = true;
            return candidate.node();
        } finally {
            if (!retained) candidate.node().recycle();
        }
    }

    private SliderRangeSnapshot refreshCachedRangeSliderNode(
            AccessibilityNodeInfo node, SliderKey requiredKey) {
        if (node == null) return null;
        try {
            if (!node.refresh()) return null;
            return rangeSnapshotFromNode(node, requiredKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SliderRangeSnapshot rangeSnapshotFromNode(
            AccessibilityNodeInfo node, SliderKey requiredKey) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()
                || !supportsSetProgress(node)) return null;
        CharSequence packageValue = node.getPackageName();
        String packageName = packageValue == null ? "" : packageValue.toString();
        if (getPackageName().equals(packageName)) return null;
        AccessibilityNodeInfo.RangeInfo info = node.getRangeInfo();
        if (info == null
                || info.getType() != AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) return null;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        SliderKey key = sliderKey(node, packageName, bounds);
        if (requiredKey != null && !matchesSliderKey(requiredKey, key)) return null;
        try {
            SliderIndexRange range = SliderIndexRange.from(
                    info.getMin(), info.getMax(), info.getCurrent(), true);
            SliderSeekPlan.Snapshot raw = new SliderSeekPlan.Snapshot(
                    info.getMin(), info.getMax(), info.getCurrent());
            return new SliderRangeSnapshot(key, range, raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isExpectedRangePosition(
            SliderSession session, SliderRangeSnapshot snapshot, int expectedIndex) {
        if (snapshot == null) return false;
        SliderSeekPlan.Snapshot current = snapshot.raw();
        return session.plan().matchesRange(current)
                && session.plan().isAtIndex(current.current(), expectedIndex);
    }

    private static boolean performRangeSliderAction(
            AccessibilityNodeInfo node, SliderSession session, int targetIndex) {
        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                (float) session.plan().targetForIndex(targetIndex));
        return node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                arguments);
    }

    private SliderRangeSnapshot findReplayRangeSliderOnMain(
            ScreenGeometry geometry, SliderKey requiredKey) throws InterruptedException {
        AtomicReference<SliderRangeSnapshot> result = new AtomicReference<>();
        runOnMainSync(() -> {
            SliderNodeCandidate candidate = findReplayRangeSliderNow(geometry, requiredKey);
            if (candidate == null) return;
            try {
                result.set(candidate.snapshot());
            } finally {
                candidate.node().recycle();
            }
        }, 900);
        return result.get();
    }

    private SliderNodeCandidate findReplayRangeSliderNow(
            ScreenGeometry geometry, SliderKey requiredKey) {
        ArrayList<SliderNodeCandidate> candidates = new ArrayList<>();
        int[] remaining = {SHS_NODE_LIMIT};
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    try {
                        if (remaining[0] > 0) {
                            AccessibilityNodeInfo root = window.getRoot();
                            if (root != null) {
                                try {
                                    collectRangeSliderNodes(
                                            root, geometry, requiredKey, candidates, remaining);
                                } finally {
                                    root.recycle();
                                }
                            }
                        }
                    } finally {
                        window.recycle();
                    }
                }
            }
            if (candidates.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        collectRangeSliderNodes(root, geometry, requiredKey, candidates, remaining);
                    } finally {
                        root.recycle();
                    }
                }
            }
        } catch (Throwable ignored) {
            recycleSliderCandidates(candidates);
            return null;
        }
        if (candidates.size() != 1) {
            recycleSliderCandidates(candidates);
            return null;
        }
        return candidates.get(0);
    }

    private void collectRangeSliderNodes(AccessibilityNodeInfo node,
                                         ScreenGeometry geometry,
                                         SliderKey requiredKey,
                                         List<SliderNodeCandidate> out,
                                         int[] remaining) {
        if (node == null || remaining[0]-- <= 0) return;
        CharSequence packageValue = node.getPackageName();
        String packageName = packageValue == null ? "" : packageValue.toString();
        boolean ownOverlay = getPackageName().equals(packageName);
        if (!ownOverlay && node.isVisibleToUser() && node.isEnabled()
                && supportsSetProgress(node)) {
            AccessibilityNodeInfo.RangeInfo info = node.getRangeInfo();
            if (info != null) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                boolean plausibleBounds = geometry == null
                        ? requiredKey != null && matchesSliderKey(requiredKey,
                        sliderKey(node, packageName, bounds))
                        : geometry.isSliderControlBounds(bounds);
                if (plausibleBounds) {
                    try {
                        boolean discrete = info.getType()
                                == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT;
                        SliderIndexRange range = SliderIndexRange.from(
                                info.getMin(), info.getMax(), info.getCurrent(), discrete);
                        SliderKey key = sliderKey(node, packageName, bounds);
                        if (requiredKey == null || matchesSliderKey(requiredKey, key)) {
                            SliderSeekPlan.Snapshot raw = new SliderSeekPlan.Snapshot(
                                    info.getMin(), info.getMax(), info.getCurrent());
                            out.add(new SliderNodeCandidate(AccessibilityNodeInfo.obtain(node),
                                    new SliderRangeSnapshot(key, range, raw)));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Continuous, normalized or malformed ranges are not exact move indexes.
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount() && remaining[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectRangeSliderNodes(child, geometry, requiredKey, out, remaining);
            } finally {
                child.recycle();
            }
        }
    }

    private static boolean supportsSetProgress(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId()
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                return true;
            }
        }
        return false;
    }

    private static SliderKey sliderKey(AccessibilityNodeInfo node,
                                       String packageName, Rect bounds) {
        String viewId = node.getViewIdResourceName();
        CharSequence classValue = node.getClassName();
        String className = classValue == null ? "" : classValue.toString();
        return new SliderKey(packageName, viewId == null ? "" : viewId,
                className, new Rect(bounds));
    }

    private static boolean matchesSliderKey(SliderKey expected, SliderKey actual) {
        if (expected == null || actual == null
                || !expected.packageName().equals(actual.packageName())
                || !expected.className().equals(actual.className())) return false;
        if (!expected.viewId().isBlank() || !actual.viewId().isBlank()) {
            return expected.viewId().equals(actual.viewId());
        }
        Rect a = expected.bounds();
        Rect b = actual.bounds();
        return Math.abs(a.centerX() - b.centerX()) <= 4
                && Math.abs(a.centerY() - b.centerY()) <= 4
                && Math.abs(a.width() - b.width()) <= 8
                && Math.abs(a.height() - b.height()) <= 8;
    }

    private static void recycleSliderCandidates(List<SliderNodeCandidate> candidates) {
        for (SliderNodeCandidate candidate : candidates) {
            try {
                candidate.node().recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private Outcome advanceOne(ScreenGeometry geometry, BoardState before,
                               BoardFrameFingerprint beforeFingerprint,
                               double beforeProgress, byte expected,
                               ScanMetrics metrics) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled();
            if (attempt > 0) metrics.retriedMove();
            terminalSignalUntil = 0;
            long afterSequence = CaptureService.currentSequence();
            long gestureStarted = metrics.mark();
            gestureTap(geometry, geometry.forward);
            metrics.addGesture(gestureStarted);
            long start = SystemClock.uptimeMillis();
            BoardFrameFingerprint candidate = null;
            int sameCandidate = 0;
            int bannerSightings = 0;
            boolean checkedWindowText = false;

            while (SystemClock.uptimeMillis() - start < 2400) {
                checkCancelled();
                LightFrame frame = captureLightFrameAfter(
                        geometry, afterSequence, FRESH_FRAME_WAIT_MS, metrics);
                if (frame == null) {
                    if (SystemClock.uptimeMillis() - start > 500 && !checkedWindowText) {
                        checkedWindowText = true;
                        if (terminalRecently() || windowHasTerminalText()) {
                            return new Outcome(OutcomeType.END, null, before,
                                    beforeFingerprint, beforeProgress, "終局メッセージ");
                        }
                    }
                    continue;
                }
                afterSequence = Math.max(afterSequence, frame.sequence());
                boolean terminalSignaled = terminalRecently();
                boolean banner = SystemClock.uptimeMillis() - start >= 180
                        && TerminalBannerDetector.hasBanner(
                        new BitmapPixels(frame.bitmap()), geometry.board);
                if (banner) {
                    bannerSightings++;
                } else {
                    bannerSightings = 0;
                }

                BoardFrameFingerprint observedFingerprint = frame.fingerprint();
                if (!observedFingerprint.equals(beforeFingerprint)) {
                    boolean newCandidate = !observedFingerprint.equals(candidate);
                    BoardAnalyzer.Detection candidateDetection = null;
                    if (newCandidate) {
                        long analyzeStarted = metrics.mark();
                        candidateDetection = analyzeLightDetection(frame, geometry);
                        metrics.addBoardAnalysis(analyzeStarted);
                        MoveDetector.Result fastDiff = MoveDetector.between(
                                before, candidateDetection.state(), expected);
                        if (isExactLegalTransition(before, candidateDetection.state(), fastDiff)
                                && fastFrameCanCommit(candidateDetection, beforeProgress,
                                frame.sliderProgress())) {
                            double p = usableProgress(frame.sliderProgress(), beforeProgress);
                            frame.recycle();
                            metrics.acceptedFastMove();
                            return new Outcome(OutcomeType.MOVE, fastDiff.move(),
                                    candidateDetection.state(), observedFingerprint, p, "fast");
                        }
                    }
                    if (!newCandidate) sameCandidate++;
                    else {
                        candidate = observedFingerprint;
                        sameCandidate = 1;
                    }
                    if (sameCandidate >= 2) {
                        BoardState observed;
                        if (candidateDetection != null) {
                            observed = candidateDetection.state();
                        } else {
                            long analyzeStarted = metrics.mark();
                            observed = analyzeLightState(frame, geometry);
                            metrics.addBoardAnalysis(analyzeStarted);
                        }
                        MoveDetector.Result diff = MoveDetector.between(before, observed, expected);
                        if (isExactLegalTransition(before, observed, diff)) {
                            double p = usableProgress(frame.sliderProgress(), beforeProgress);
                            frame.recycle();
                            metrics.acceptedVerifiedMove();
                            return new Outcome(OutcomeType.MOVE, diff.move(), observed,
                                    observedFingerprint, p, "ok");
                        }
                        if (observed.equals(before)
                                && progressAdvanced(beforeProgress, frame.sliderProgress())) {
                            double p = frame.sliderProgress();
                            frame.recycle();
                            metrics.acceptedVerifiedMove();
                            return new Outcome(OutcomeType.PASS, Move.pass(expected), before,
                                    observedFingerprint, p, "pass");
                        }
                        candidate = null;
                        sameCandidate = 0;
                    }
                } else if (SystemClock.uptimeMillis() - start > 500) {
                    if (progressAdvanced(beforeProgress, frame.sliderProgress())) {
                        long analyzeStarted = metrics.mark();
                        BoardState observed = analyzeLightState(frame, geometry);
                        metrics.addBoardAnalysis(analyzeStarted);
                        if (observed.equals(before)) {
                            double p = frame.sliderProgress();
                            frame.recycle();
                            metrics.acceptedVerifiedMove();
                            return new Outcome(OutcomeType.PASS, Move.pass(expected), before,
                                    observedFingerprint, p, "pass");
                        }
                        MoveDetector.Result diff = MoveDetector.between(before, observed, expected);
                        if (isExactLegalTransition(before, observed, diff)) {
                            double p = frame.sliderProgress();
                            frame.recycle();
                            metrics.acceptedVerifiedMove();
                            return new Outcome(OutcomeType.MOVE, diff.move(), observed,
                                    observedFingerprint, p, "ok");
                        }
                    }
                }

                long elapsed = SystemClock.uptimeMillis() - start;
                boolean windowTerminal = false;
                if (elapsed > 500 && !checkedWindowText) {
                    checkedWindowText = true;
                    windowTerminal = windowHasTerminalText();
                }
                if ((terminalSignaled && elapsed >= 120)
                        || bannerSightings >= 2 || windowTerminal) {
                    double p = usableProgress(frame.sliderProgress(), beforeProgress);
                    frame.recycle();
                    return new Outcome(OutcomeType.END, null, before, beforeFingerprint, p,
                            bannerSightings >= 2 ? "終局バナー" : "終局メッセージ");
                }
                frame.recycle();
            }

            // The sparse fingerprint controls polling only.  Before accepting a move,
            // always run the complete detector against the settled bitmap.
            LightFrame settled = awaitLightStable(geometry, null, 900, metrics);
            boolean settledBanner = TerminalBannerDetector.hasBanner(
                    new BitmapPixels(settled.bitmap()), geometry.board);
            boolean settledTerminal = terminalRecently() || settledBanner
                    || windowHasTerminalText();
            BoardFrameFingerprint settledFingerprint = settled.fingerprint();
            boolean fingerprintChanged = !settledFingerprint.equals(beforeFingerprint);

            // A changed picture without slider movement is treated as an unfinished render.
            // This is the same guarded retry used by the real-device-tested speed branch.
            if (fingerprintChanged
                    && beforeProgress >= 0
                    && !progressAdvanced(beforeProgress, settled.sliderProgress())
                    && !settledTerminal) {
                settled.recycle();
                continue;
            }

            long analyzeStarted = metrics.mark();
            BoardState settledState = analyzeLightState(settled, geometry);
            metrics.addBoardAnalysis(analyzeStarted);
            if (settledState.equals(before)) {
                if (progressAdvanced(beforeProgress, settled.sliderProgress())) {
                    double p = settled.sliderProgress();
                    settled.recycle();
                    metrics.acceptedVerifiedMove();
                    return new Outcome(OutcomeType.PASS, Move.pass(expected), before,
                            settledFingerprint, p, "pass");
                }
                if (settledTerminal) {
                    double p = usableProgress(settled.sliderProgress(), beforeProgress);
                    settled.recycle();
                    return new Outcome(OutcomeType.END, null, before, beforeFingerprint, p,
                            settledBanner ? "終局バナー" : "終局メッセージ");
                }
                settled.recycle();
                continue;
            }

            MoveDetector.Result settledDiff = MoveDetector.between(before, settledState, expected);
            if (isExactLegalTransition(before, settledState, settledDiff)) {
                double p = usableProgress(settled.sliderProgress(), beforeProgress);
                settled.recycle();
                metrics.acceptedVerifiedMove();
                return new Outcome(OutcomeType.MOVE, settledDiff.move(), settledState,
                        settledFingerprint, p, "ok");
            }

            if (settledTerminal) {
                double p = usableProgress(settled.sliderProgress(), beforeProgress);
                settled.recycle();
                return new Outcome(OutcomeType.END, null, before, beforeFingerprint, p,
                        settledBanner ? "終局バナー" : "終局メッセージ");
            }

            // A changed but invalid frame is rolled back before any retry. If the exact prior
            // state cannot be restored, stop instead of producing a shifted SGF.
            settled.recycle();
            publish("局面を再同期中…");
            gestureTap(geometry, geometry.back);
            Frame restored = awaitStable(geometry, settledState, 1800);
            boolean restoredOk = restored.detection().state().equals(before);
            restored.recycle();
            if (!restoredOk) {
                return new Outcome(OutcomeType.FAILURE, null, before, beforeFingerprint,
                        beforeProgress, "画面の局面と記録中の局面を再同期できません");
            }
        }
        return new Outcome(OutcomeType.FAILURE, null, before, beforeFingerprint,
                beforeProgress, "一手進む操作後に、着手・パス・終局のいずれも確認できません");
    }

    private static boolean fastFrameCanCommit(BoardAnalyzer.Detection detection,
                                              double beforeProgress,
                                              double afterProgress) {
        return progressAdvanced(beforeProgress, afterProgress)
                && detection.confidence() >= FAST_FRAME_MIN_CONFIDENCE;
    }

    private static boolean isExactLegalTransition(BoardState before, BoardState observed,
                                                  MoveDetector.Result result) {
        if (!result.legal()) return false;
        BoardState expected = GoBoardRules.apply(before, result.move());
        return expected != null && expected.equals(observed);
    }

    private static boolean progressAdvanced(double before, double after) {
        return before >= 0 && after >= 0 && after - before > 0.0018;
    }

    private static double usableProgress(double value, double fallback) {
        return value >= 0 ? value : fallback;
    }

    private Frame awaitStable(ScreenGeometry geometry, BoardState previous, long timeout) throws Exception {
        long start = SystemClock.uptimeMillis();
        BoardState candidate = null;
        int repeats = 0;
        Frame held = null;
        while (SystemClock.uptimeMillis() - start < timeout) {
            checkCancelled();
            Frame frame = captureFrame(geometry);
            BoardState state = frame.detection().state();
            if (state.equals(candidate)) repeats++;
            else {
                candidate = state;
                repeats = 1;
            }
            if (held != null) held.recycle();
            held = frame;
            if (repeats >= 2 && (previous == null || !state.equals(previous))) return held;
            if (repeats >= 3 && previous != null && state.equals(previous)
                    && SystemClock.uptimeMillis() - start > timeout - 220) return held;
            Thread.sleep(75);
        }
        if (held != null) return held;
        throw new IllegalStateException("盤面画像が安定しません");
    }

    private LightFrame awaitLightStable(ScreenGeometry geometry,
                                        BoardFrameFingerprint previous,
                                        long timeout, ScanMetrics metrics) throws Exception {
        long start = SystemClock.uptimeMillis();
        BoardFrameFingerprint candidate = null;
        int repeats = 0;
        LightFrame held = null;
        while (SystemClock.uptimeMillis() - start < timeout) {
            checkCancelled();
            LightFrame frame = captureLightFrame(geometry, metrics);
            BoardFrameFingerprint fingerprint = frame.fingerprint();
            if (fingerprint.equals(candidate)) repeats++;
            else {
                candidate = fingerprint;
                repeats = 1;
            }
            if (held != null) held.recycle();
            held = frame;
            if (repeats >= 2 && (previous == null || !fingerprint.equals(previous))) return held;
            if (repeats >= 3 && previous != null && fingerprint.equals(previous)
                    && SystemClock.uptimeMillis() - start > timeout - 180) return held;
            Thread.sleep(35);
        }
        if (held != null) return held;
        throw new IllegalStateException("盤面画像が安定しません");
    }

    private BoardState analyzeLightState(LightFrame frame, ScreenGeometry geometry) {
        return analyzeLightDetection(frame, geometry).state();
    }

    private BoardAnalyzer.Detection analyzeLightDetection(LightFrame frame,
                                                           ScreenGeometry geometry) {
        return BoardAnalyzer.detect(new BitmapPixels(frame.bitmap()), geometry.board);
    }

    private LightFrame captureLightFrame(ScreenGeometry geometry, ScanMetrics metrics)
            throws Exception {
        long acquireStarted = metrics.mark();
        Bitmap bitmap = CaptureService.capture(700);
        if (bitmap == null) bitmap = captureWithAccessibility(1200);
        metrics.addFreshFrameWait(acquireStarted, bitmap != null);
        if (bitmap == null) {
            throw new IllegalStateException("画面画像を取得できません。画面読取の許可をやり直してください");
        }
        long lightStarted = metrics.mark();
        try {
            BoardFrameFingerprint fingerprint = BoardFrameFingerprint.capture(
                    new BitmapPixels(bitmap), geometry.board);
            return new LightFrame(bitmap, fingerprint, geometry.sliderProgress(bitmap),
                    CaptureService.currentSequence());
        } catch (RuntimeException | Error error) {
            bitmap.recycle();
            throw error;
        } finally {
            metrics.addLightAnalysis(lightStarted);
        }
    }

    private LightFrame captureLightFrameAfter(ScreenGeometry geometry, long afterSequence,
                                               long timeoutMs, ScanMetrics metrics) throws Exception {
        long acquireStarted = metrics.mark();
        CaptureService.CapturedFrame captured = CaptureService.captureAfter(afterSequence, timeoutMs);
        metrics.addFreshFrameWait(acquireStarted, captured != null);
        if (captured == null) return null;
        Bitmap bitmap = captured.bitmap();
        long lightStarted = metrics.mark();
        try {
            BoardFrameFingerprint fingerprint = BoardFrameFingerprint.capture(
                    new BitmapPixels(bitmap), geometry.board);
            return new LightFrame(bitmap, fingerprint, geometry.sliderProgress(bitmap),
                    captured.sequence());
        } catch (RuntimeException | Error error) {
            bitmap.recycle();
            throw error;
        } finally {
            metrics.addLightAnalysis(lightStarted);
        }
    }

    private Frame captureFrame(ScreenGeometry geometry) throws Exception {
        Bitmap bitmap = CaptureService.capture(1100);
        if (bitmap == null) bitmap = captureWithAccessibility(2200);
        if (bitmap == null) {
            throw new IllegalStateException("画面画像を取得できません。画面読取の許可をやり直してください");
        }
        return analyzeFrame(bitmap, geometry);
    }

    private Frame captureLatestFrame(ScreenGeometry geometry) throws Exception {
        CaptureService.CapturedFrame cached = CaptureService.latestFrame();
        Bitmap bitmap = cached == null ? CaptureService.capture(1100) : cached.bitmap();
        if (bitmap == null) bitmap = captureWithAccessibility(2200);
        if (bitmap == null) {
            throw new IllegalStateException("画面画像を取得できません。画面読取の許可をやり直してください");
        }
        return analyzeFrame(bitmap, geometry);
    }

    private Frame captureFreshFrame(ScreenGeometry geometry) throws Exception {
        Bitmap bitmap = captureWithAccessibility(2200);
        if (bitmap == null) bitmap = CaptureService.capture(1100);
        if (bitmap == null) throw new IllegalStateException("ダイアログを閉じた後の画面を取得できません");
        return analyzeFrame(bitmap, geometry);
    }

    private Frame analyzeFrame(Bitmap bitmap, ScreenGeometry geometry) {
        try {
            BoardAnalyzer.Detection detection = geometry == null
                    ? BoardAnalyzer.detect(new BitmapPixels(bitmap))
                    : BoardAnalyzer.detect(new BitmapPixels(bitmap), geometry.board);
            double progress = geometry == null ? -1 : geometry.sliderProgress(bitmap);
            return new Frame(bitmap, detection, progress);
        } catch (RuntimeException | Error error) {
            bitmap.recycle();
            throw error;
        }
    }

    private Frame recoverBlockingDialog(Frame frame) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean byImage = BlockingDialogDetector.hasDialog(
                    new BitmapPixels(frame.bitmap()), frame.detection().board());
            boolean byText = windowHasPointShortageDialog();
            if (!byImage && !byText) return frame;

            publish("ポイント確認画面を閉じて処理を再開します…");
            frame.recycle();
            if (!performGlobalBack()) {
                throw new IllegalStateException("ポイント確認画面を安全に閉じられません");
            }
            Thread.sleep(550);
            frame = captureFreshFrame(null);
        }
        boolean remains = BlockingDialogDetector.hasDialog(
                new BitmapPixels(frame.bitmap()), frame.detection().board())
                || windowHasPointShortageDialog();
        if (!remains) return frame;
        frame.recycle();
        throw new IllegalStateException("ポイント確認画面を閉じられません");
    }

    private boolean windowHasTerminalText() throws InterruptedException {
        for (MetadataReader.UiText item : collectWindowTextsOnMain()) {
            if (isTerminalText(item.text())) return true;
        }
        return false;
    }

    private boolean windowHasPointShortageDialog() throws InterruptedException {
        for (MetadataReader.UiText item : collectWindowTextsOnMain()) {
            if (BlockingDialogDetector.isPointShortageText(item.text())) return true;
        }
        return false;
    }

    private List<MetadataReader.UiText> collectWindowTextsOnMain() throws InterruptedException {
        AtomicReference<List<MetadataReader.UiText>> result = new AtomicReference<>(List.of());
        runOnMainSync(() -> result.set(collectWindowTexts()), 1500);
        return result.get();
    }

    private List<MetadataReader.UiText> collectWindowTexts() {
        ArrayList<MetadataReader.UiText> result = new ArrayList<>();
        int[] remaining = {1200};
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    try {
                        if (remaining[0] > 0) {
                            AccessibilityNodeInfo root = window.getRoot();
                            if (root != null) {
                                try {
                                    collectNodeText(root, result, remaining);
                                } finally {
                                    root.recycle();
                                }
                            }
                        }
                    } finally {
                        window.recycle();
                    }
                }
            }
            if (result.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        collectNodeText(root, result, remaining);
                    } finally {
                        root.recycle();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Some vendors expose only screenshots. MetadataReader has safe fallbacks.
        }
        return result;
    }

    private void collectNodeText(AccessibilityNodeInfo node, List<MetadataReader.UiText> out,
                                 int[] remaining) {
        if (node == null || remaining[0]-- <= 0) return;
        CharSequence packageName = node.getPackageName();
        boolean ownOverlay = packageName != null && getPackageName().contentEquals(packageName);
        if (!ownOverlay) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            addUiText(out, node.getText(), bounds);
            CharSequence description = node.getContentDescription();
            String nodeText = node.getText() == null ? "" : node.getText().toString();
            if (description != null && !description.toString().contentEquals(nodeText)) {
                addUiText(out, description, bounds);
            }
        }
        for (int i = 0; i < node.getChildCount() && remaining[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectNodeText(child, out, remaining);
            } finally {
                child.recycle();
            }
        }
    }

    private static void addUiText(List<MetadataReader.UiText> out, CharSequence value, Rect bounds) {
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty() || bounds.isEmpty()) return;
        out.add(new MetadataReader.UiText(text, new Rect(bounds)));
    }

    static boolean isTerminalText(String text) {
        if (text == null) return false;
        String normalized = text.replaceAll("[\\s。、]", "");
        return normalized.contains("すでに最後の一手です")
                || normalized.contains("既に最後の一手です")
                || (normalized.contains("最後の一手") && normalized.contains("です"));
    }

    private boolean terminalRecently() {
        return SystemClock.uptimeMillis() < terminalSignalUntil;
    }

    private SliderAction gestureTap(ScreenGeometry geometry, PointF point) throws Exception {
        geometry.requireSafe(point);
        Path path = new Path();
        path.moveTo(point.x, point.y);
        return performGesture(new GestureDescription.Builder().addStroke(
                new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)).build());
    }

    private SliderAction gestureSwipe(ScreenGeometry geometry, PointF from, PointF to,
                                      long durationMs) throws Exception {
        geometry.requireSafe(from);
        geometry.requireSafe(to);
        Path path = new Path();
        path.moveTo(from.x, from.y);
        path.lineTo(to.x, to.y);
        return performGesture(new GestureDescription.Builder().addStroke(
                new GestureDescription.StrokeDescription(path, 0, durationMs)).build());
    }

    private boolean performGlobalBack() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] accepted = {false};
        main.post(() -> {
            try {
                accepted[0] = performGlobalAction(GLOBAL_ACTION_BACK);
            } catch (Throwable ignored) {
                accepted[0] = false;
            } finally {
                latch.countDown();
            }
        });
        return latch.await(1500, TimeUnit.MILLISECONDS) && accepted[0];
    }

    private Bitmap captureWithAccessibility(long timeoutMs) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Object ownershipLock = new Object();
        Bitmap[] result = {null};
        boolean[] accepting = {true};
        main.post(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult screenshot) {
                        HardwareBuffer buffer = screenshot.getHardwareBuffer();
                        Bitmap copy = null;
                        Bitmap wrapped = null;
                        try {
                            wrapped = Bitmap.wrapHardwareBuffer(
                                    buffer, screenshot.getColorSpace());
                            if (wrapped != null) copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                            synchronized (ownershipLock) {
                                if (accepting[0]) {
                                    result[0] = copy;
                                    copy = null;
                                }
                            }
                        } finally {
                            if (copy != null && !copy.isRecycled()) copy.recycle();
                            if (wrapped != null && !wrapped.isRecycled()) wrapped.recycle();
                            try {
                                buffer.close();
                            } finally {
                                latch.countDown();
                            }
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        latch.countDown();
                    }
                });
            } catch (Throwable ignored) {
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Bitmap abandoned;
            synchronized (ownershipLock) {
                accepting[0] = false;
                abandoned = result[0];
                result[0] = null;
            }
            if (abandoned != null && !abandoned.isRecycled()) abandoned.recycle();
            throw interrupted;
        }
        synchronized (ownershipLock) {
            accepting[0] = false;
            Bitmap captured = result[0];
            result[0] = null;
            return captured;
        }
    }

    private SliderAction performGesture(GestureDescription description) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};
        SliderAction[] action = {null};
        main.post(() -> {
            try {
                action[0] = new SliderAction(
                        CaptureService.currentSequence(), SystemClock.uptimeMillis());
                boolean accepted = dispatchGesture(description, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) {
                        ok[0] = true;
                        latch.countDown();
                    }

                    @Override public void onCancelled(GestureDescription gesture) {
                        latch.countDown();
                    }
                }, null);
                if (!accepted) latch.countDown();
            } catch (Throwable ignored) {
                latch.countDown();
            }
        });
        if (!latch.await(3, TimeUnit.SECONDS) || !ok[0]) {
            throw new IllegalStateException("ユーザー補助の画面操作に失敗しました");
        }
        if (action[0] == null) {
            throw new IllegalStateException("ユーザー補助の画面操作時刻を取得できません");
        }
        return action[0];
    }

    private void checkCancelled() {
        if (cancelled) throw new CancellationException();
    }

    private void dockOverlayBelowBoard(BoardAnalyzer.Region board) throws InterruptedException {
        runOnMainSync(() -> {
            if (overlay == null || overlayParams == null) return;
            if (!overlayDocked) {
                overlayOriginalX = overlayParams.x;
                overlayOriginalY = overlayParams.y;
                overlayDocked = true;
            }
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int panelHeight = Math.max(overlay.getHeight(), dp(76));
            int controlClearance = dp(135);
            int lowestSafeY = Math.max(dp(8), screenHeight - panelHeight - controlClearance);
            overlayParams.x = dp(4);
            overlayParams.y = Math.min(board.bottom() + dp(5), lowestSafeY);
            overlay.setVisibility(View.VISIBLE);
            try {
                windowManager.updateViewLayout(overlay, overlayParams);
            } catch (Exception ignored) {
            }
        }, 1200);
    }

    private void restoreOverlayPosition() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            restoreOverlayPositionNow();
            return;
        }
        try {
            runOnMainSync(this::restoreOverlayPositionNow, 1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            main.post(this::restoreOverlayPositionNow);
        }
    }

    private void restoreOverlayPositionNow() {
        if (overlay == null || overlayParams == null) return;
        overlay.setVisibility(View.VISIBLE);
        if (overlayDocked) {
            overlayParams.x = overlayOriginalX;
            overlayParams.y = overlayOriginalY;
            overlayDocked = false;
            try {
                windowManager.updateViewLayout(overlay, overlayParams);
            } catch (Exception ignored) {
            }
        }
    }

    private void runOnMainSync(Runnable action, long timeoutMs) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        main.post(() -> {
            if (!pending.compareAndSet(true, false)) {
                latch.countDown();
                return;
            }
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.compareAndSet(true, false);
            throw new IllegalStateException("操作パネルの制御がタイムアウトしました");
        }
        Throwable error = failure.get();
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        if (error != null) throw new IllegalStateException("メイン画面の操作に失敗しました", error);
    }

    private void setOverlayStatus(String text) {
        main.post(() -> {
            if (overlayStatus != null) overlayStatus.setText(text);
        });
    }

    private void publish(String text) {
        setOverlayStatus(text);
        MainActivity.publishStatus(this, text);
    }

    private String cleanMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + " - " + message;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "不明";
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void exitReader() {
        cancelled = true;
        closeActiveSliderSession();
        closeMetadataEditor();
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
            overlayStatus = null;
        }
        CaptureService.shutdown(this);
        publish("終了しました");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        cancelled = true;
        closeActiveSliderSession();
        worker.shutdownNow();
        ocrWorker.shutdownNow();
        HeaderTextRecognizer.close();
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        closeMetadataEditor();
        overlay = null;
        instance = null;
        super.onDestroy();
    }
}
