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
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int MAX_MOVES = 1000;
    private static final long TARGET_TOTAL_MS = 10_000;
    private static final long TARGET_SAVE_RESERVE_MS = 300;
    private static final long FRESH_FRAME_WAIT_MS = 320;
    private static final long OCR_BACKGROUND_TIMEOUT_MS = 1500;
    private static final long TAP_DURATION_MS = 20;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 150;
    private static final double FAST_FRAME_MIN_CONFIDENCE = 0.78;
    private static final long SHS_INDEX_ACK_TIMEOUT_MS = 180;
    private static final long SHS_STEP_TIMEOUT_MS = 2200;
    private static final long SHS_PASS_SETTLE_MS = 500;
    private static final long SHS_PASS_SCREENSHOT_TIMEOUT_MS = 1200;
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
    private volatile boolean cancelled;
    private volatile boolean running;
    private volatile long terminalSignalUntil;
    private volatile int activeMoveCount;
    private WindowManager windowManager;
    private View overlay;
    private TextView overlayStatus;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayDocked;
    private int overlayOriginalX;
    private int overlayOriginalY;

    private record Frame(Bitmap bitmap, BoardAnalyzer.Detection detection, double sliderProgress) {
        void recycle() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

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

    private record SliderSession(SliderMode mode, SliderKey key, SliderSeekPlan plan,
                                 int totalMoves) {
        String metricName() { return mode == SliderMode.RANGE ? "range" : "text"; }
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
        panel.setBackgroundColor(0xE6FFFFFF);

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
        overlayStatus.setTextColor(0xFF17364A);
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
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(42)));
        return button;
    }

    private TextView buttonLike(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xFF0B6172);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(42)));
        return view;
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
        terminalSignalUntil = 0;
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
            enhancedMetadata = startMetadataEnhancement(
                    first.bitmap(), first.detection().board(), accessibilityTexts, meta, metrics);
            publish("認識結果：黒 " + meta.blackDisplay() + "／白 " + meta.whiteDisplay()
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
        publish("SGFを保存中…");
        LocalDate date = LocalDate.now();
        String sgf = SgfWriter.build(meta, moves, date);
        checkCancelled();
        SgfStore.save(this, meta, sgf, date);
        metrics.addPhase("save", saveStarted);
        metrics.finishSuccess(moves.size());
        publish("保存完了：Download/" + safeName(meta.blackName) + "_vs_"
                + safeName(meta.whiteName) + "_" + date.toString().replace("-", "")
                + ".sgf（" + moves.size() + "手／"
                + String.format(java.util.Locale.ROOT, "%.2f", metrics.elapsedSeconds()) + "秒）");
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
                return MetadataReader.read(header, board, accessibilityTexts, ocrTexts);
            } catch (HeaderTextRecognizer.RecognitionTimeoutException timeout) {
                metrics.ocrTimedOut();
                return fallback;
            } catch (Throwable ignored) {
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
        if (scanMetadata.handicap > 1) {
            resolved.handicap = scanMetadata.handicap;
            resolved.handicapText = scanMetadata.handicapText;
        }
        publish("確定情報：黒 " + resolved.blackDisplay() + "／白 "
                + resolved.whiteDisplay()
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
                && !meta.result.isBlank();
    }

    private Frame rewindToInitial(ScreenGeometry geometry, GameMetadata meta) throws Exception {
        publish("初期局面を確認中…");
        Frame frame = captureFrame(geometry);
        if (isInitialOrInfer(frame.detection().state(), meta)) return frame;

        publish("手数バーのツマミを左端へ移動しています…");
        for (int attempt = 0; attempt < 2 && !geometry.isSliderAtLeft(frame.bitmap()); attempt++) {
            BoardState beforeDrag = frame.detection().state();
            PointF thumb = geometry.sliderThumb(frame.bitmap());
            frame.recycle();
            gestureSwipe(geometry, thumb, geometry.sliderLeft, 420);
            frame = awaitStable(geometry, beforeDrag, 3000);
        }
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

    private boolean isInitialOrInfer(BoardState state, GameMetadata meta) {
        if (BoardAnalyzer.isInitial(state, meta.handicap)) return true;
        return meta.handicap == 0 && inferHandicapAtBeginning(state, meta);
    }

    private boolean inferHandicapAtBeginning(BoardState state, GameMetadata meta) {
        int black = state.count(BoardState.BLACK);
        if (black >= 2 && black <= 9 && BoardAnalyzer.isInitial(state, black)) {
            meta.handicap = black;
            meta.handicapText = black + "子";
            return true;
        }
        return false;
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
                SliderMode.TEXT, null, plan, plan.totalMoves());
        return replaySliderIndexes(geometry, session, 0, initial, firstColor,
                new ArrayList<>(), new ArrayList<>(List.of(initial)), thumb, metrics);
    }

    private SliderReplayResult replayRangeSlider(ScreenGeometry geometry,
                                                  SliderRangeSnapshot initialRange,
                                                  BoardState initial,
                                                  byte firstColor,
                                                  ScanMetrics metrics) throws Exception {
        checkCancelled();
        long actionSequence = CaptureService.currentSequence();
        long gestureStarted = metrics.mark();
        gestureTap(geometry, geometry.forward);
        metrics.addGesture(gestureStarted);

        SliderRangeSnapshot afterOne = awaitChangedRangeSlider(
                geometry, initialRange, SHS_INDEX_ACK_TIMEOUT_MS);
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
        SliderSession session = new SliderSession(SliderMode.RANGE,
                initialRange.key(), plan, plan.totalMoves());
        SliderStep first = awaitSliderStep(geometry, session, 1, initial, firstColor,
                null, actionSequence, metrics);
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
        byte nextColor = firstColor == BoardState.BLACK ? BoardState.WHITE : BoardState.BLACK;
        return replaySliderIndexes(geometry, session, 1, first.state(), nextColor,
                moves, history, first.thumb(), metrics);
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
            long actionSequence = seekSliderIndex(
                    geometry, session, index - 1, index, currentThumb, metrics);
            if (actionSequence < 0L) {
                return SliderReplayResult.fallback("手数バーを" + index + "手目へ移動できません");
            }

            BoardState twoPliesAgo = history.size() >= 2
                    ? history.get(history.size() - 2) : null;
            SliderStep step = awaitSliderStep(geometry, session, index, current, expected,
                    twoPliesAgo, actionSequence, metrics);
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

    private long seekSliderIndex(ScreenGeometry geometry, SliderSession session,
                                 int previousIndex, int targetIndex, PointF currentThumb,
                                 ScanMetrics metrics) throws Exception {
        if (session.mode() == SliderMode.TEXT) {
            SliderSeekPlan.Snapshot before = readSliderSessionSnapshot(geometry, session);
            if (before == null || !session.plan().matchesRange(before)
                    || !session.plan().isAtIndex(before.current(), previousIndex)) {
                return -1L;
            }
        }

        long sequence = CaptureService.currentSequence();
        long seekStarted = metrics.mark();
        boolean accepted;
        if (session.mode() == SliderMode.RANGE) {
            accepted = setRangeSliderIndexOnMain(session, previousIndex, targetIndex);
        } else {
            if (currentThumb == null) return -1L;
            double progress = targetIndex / (double) session.totalMoves();
            PointF target = geometry.sliderPoint(progress);
            gestureSwipe(geometry, currentThumb, target, SHS_SEEK_DURATION_MS);
            accepted = true;
        }
        metrics.addSliderSeek(seekStarted);
        return accepted ? sequence : -1L;
    }

    private SliderStep awaitSliderStep(ScreenGeometry geometry, SliderSession session,
                                       int targetIndex, BoardState before, byte expected,
                                       BoardState twoPliesAgo, long actionSequence,
                                       ScanMetrics metrics) throws Exception {
        long ackDeadline = SystemClock.uptimeMillis() + SHS_INDEX_ACK_TIMEOUT_MS;
        SliderSeekPlan.Snapshot acknowledged = null;
        while (SystemClock.uptimeMillis() < ackDeadline) {
            checkCancelled();
            SliderSeekPlan.Snapshot observed = readSliderSessionSnapshot(geometry, session);
            if (observed != null && session.plan().matchesRange(observed)) {
                int index = session.plan().indexOf(observed.current());
                if (index == targetIndex) {
                    acknowledged = observed;
                    break;
                }
                if (index > targetIndex) return null;
            }
            Thread.sleep(6);
        }
        if (acknowledged == null) return null;

        long acknowledgedAt = SystemClock.uptimeMillis();
        long sequence = actionSequence;
        long deadline = acknowledgedAt + SHS_STEP_TIMEOUT_MS;
        long quietSince = acknowledgedAt;
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled();
            long now = SystemClock.uptimeMillis();
            long remaining = deadline - now;
            boolean explicitPassCheck = now - quietSince >= SHS_PASS_SETTLE_MS;
            long waitStarted = metrics.mark();
            CaptureService.CapturedFrame captured = null;
            Bitmap bitmap;
            if (explicitPassCheck) {
                bitmap = captureWithAccessibility(Math.min(
                        SHS_PASS_SCREENSHOT_TIMEOUT_MS, Math.max(1L, remaining)));
            } else {
                long untilPassCheck = quietSince + SHS_PASS_SETTLE_MS - now;
                captured = CaptureService.captureAfter(sequence, Math.min(
                        FRESH_FRAME_WAIT_MS,
                        Math.min(Math.max(1L, remaining), Math.max(1L, untilPassCheck))));
                bitmap = captured == null ? null : captured.bitmap();
            }
            metrics.addFreshFrameWait(waitStarted, bitmap != null);
            if (bitmap == null) {
                if (explicitPassCheck) return null;
                continue;
            }
            if (captured != null) sequence = Math.max(sequence, captured.sequence());
            try {
                SliderSeekPlan.Snapshot stableIndex = readSliderSessionSnapshot(
                        geometry, session);
                if (stableIndex == null || !session.plan().matchesRange(stableIndex)
                        || !session.plan().isAtIndex(stableIndex.current(), targetIndex)) {
                    return null;
                }

                shsFramePixels.bind(bitmap, geometry.board, Math.round(geometry.sliderLeft.y));
                PointF thumb;
                try {
                    thumb = geometry.sliderThumb(shsFramePixels);
                } catch (IllegalStateException missingThumb) {
                    if (explicitPassCheck) return null;
                    quietSince = SystemClock.uptimeMillis();
                    continue;
                }
                double visualProgress = geometry.sliderProgressForX(thumb.x);
                double expectedProgress = targetIndex / (double) session.totalMoves();
                double tolerance = visualIndexTolerance(geometry);
                if (Math.abs(visualProgress - expectedProgress) > tolerance) {
                    if (explicitPassCheck) return null;
                    quietSince = SystemClock.uptimeMillis();
                    continue;
                }

                long analyzeStarted = metrics.mark();
                BoardAnalyzer.Detection detection = BoardAnalyzer.detect(
                        shsFramePixels, geometry.board);
                metrics.addBoardAnalysis(analyzeStarted);
                if (detection.confidence() < SHS_MIN_CONFIDENCE) {
                    if (explicitPassCheck) return null;
                    quietSince = SystemClock.uptimeMillis();
                    continue;
                }

                BoardState observed = detection.state();
                SliderStepValidator.Result validation = SliderStepValidator.validate(
                        before, observed, expected, twoPliesAgo);
                if (validation.valid() && validation.move().pass()) {
                    if (explicitPassCheck
                            && SystemClock.uptimeMillis() - quietSince >= SHS_PASS_SETTLE_MS) {
                        return new SliderStep(validation.move(), before, thumb);
                    }
                    long untilPassCheck = quietSince + SHS_PASS_SETTLE_MS
                            - SystemClock.uptimeMillis();
                    if (untilPassCheck > 0L) Thread.sleep(untilPassCheck);
                    continue;
                }
                if (validation.valid()) {
                    return new SliderStep(validation.move(), observed, thumb);
                }
                if (explicitPassCheck) return null;
                quietSince = SystemClock.uptimeMillis();
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
                                                        long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            checkCancelled();
            SliderRangeSnapshot observed = findReplayRangeSliderOnMain(geometry, initial.key());
            if (observed != null
                    && observed.range().moveCount() == initial.range().moveCount()
                    && observed.raw().min() == initial.raw().min()
                    && observed.raw().max() == initial.raw().max()
                    && observed.raw().current() > initial.raw().current()) {
                return observed;
            }
            Thread.sleep(6);
        }
        return null;
    }

    private SliderSeekPlan.Snapshot readSliderSessionSnapshot(
            ScreenGeometry geometry, SliderSession session) throws InterruptedException {
        if (session.mode() == SliderMode.RANGE) {
            SliderRangeSnapshot range = findReplayRangeSliderOnMain(geometry, session.key());
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

    private boolean setRangeSliderIndexOnMain(SliderSession session,
                                              int previousIndex, int targetIndex)
            throws InterruptedException {
        AtomicReference<Boolean> accepted = new AtomicReference<>(false);
        runOnMainSync(() -> {
            SliderNodeCandidate candidate = findReplayRangeSliderNow(null, session.key());
            if (candidate == null) return;
            try {
                SliderSeekPlan.Snapshot current = candidate.snapshot().raw();
                if (!session.plan().matchesRange(current)
                        || !session.plan().isAtIndex(current.current(), previousIndex)) return;
                Bundle arguments = new Bundle();
                arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                        (float) session.plan().targetForIndex(targetIndex));
                accepted.set(candidate.node().performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                        arguments));
            } catch (Throwable ignored) {
                accepted.set(false);
            } finally {
                candidate.node().recycle();
            }
        }, 700);
        return accepted.get();
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

    private void gestureTap(ScreenGeometry geometry, PointF point) throws Exception {
        geometry.requireSafe(point);
        Path path = new Path();
        path.moveTo(point.x, point.y);
        performGesture(new GestureDescription.Builder().addStroke(
                new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)).build());
    }

    private void gestureSwipe(ScreenGeometry geometry, PointF from, PointF to,
                              long durationMs) throws Exception {
        geometry.requireSafe(from);
        geometry.requireSafe(to);
        Path path = new Path();
        path.moveTo(from.x, from.y);
        path.lineTo(to.x, to.y);
        performGesture(new GestureDescription.Builder().addStroke(
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
                        try {
                            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                            if (wrapped != null) copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                            synchronized (ownershipLock) {
                                if (accepting[0]) {
                                    result[0] = copy;
                                    copy = null;
                                }
                            }
                        } finally {
                            if (copy != null && !copy.isRecycled()) copy.recycle();
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

    private void performGesture(GestureDescription description) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};
        main.post(() -> {
            try {
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
        worker.shutdownNow();
        ocrWorker.shutdownNow();
        HeaderTextRecognizer.close();
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
        instance = null;
        super.onDestroy();
    }
}
