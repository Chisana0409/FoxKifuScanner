package jp.chisana.foxkifuscanner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ReaderAccessibilityService extends AccessibilityService {
    private static final int MAX_MOVES = 1000;
    private static volatile ReaderAccessibilityService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile boolean running;
    private volatile long terminalSignalUntil;
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
                              double sliderProgress) {
        void recycle() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private enum OutcomeType { MOVE, PASS, END, FAILURE }

    private record Outcome(OutcomeType type, Move move, BoardState after,
                           BoardFrameFingerprint fingerprint, double sliderProgress,
                           String message) {}

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
        worker.execute(() -> {
            try {
                scanGame();
            } catch (CancellationException e) {
                publish("読み取りを停止しました（未保存）");
            } catch (Throwable e) {
                String message = "処理を停止しました：" + cleanMessage(e);
                publish(message);
                main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            } finally {
                running = false;
                restoreOverlayPosition();
            }
        });
    }

    private void scanGame() throws Exception {
        publish("1/3 画面画像を取得中…");
        Frame first = captureFrame(null);
        first = recoverBlockingDialog(first);
        publish("2/3 盤面を検出しました");
        dockOverlayBelowBoard(first.detection().board());
        first.recycle();

        Thread.sleep(180);
        first = captureFrame(null);
        first = recoverBlockingDialog(first);
        ScreenGeometry geometry = ScreenGeometry.detect(first.bitmap(), first.detection().board());

        publish("3/3 対局情報を画像認識中…");
        List<MetadataReader.UiText> accessibilityTexts = collectWindowTextsOnMain();
        List<MetadataReader.UiText> ocrTexts = List.of();
        try {
            ocrTexts = HeaderTextRecognizer.recognize(first.bitmap(), first.detection().board());
        } catch (Throwable ignored) {
            publish("画像OCRを利用できないため、画面テキストで読取を継続します");
        }
        GameMetadata meta;
        try {
            meta = MetadataReader.read(first.bitmap(), first.detection().board(),
                    accessibilityTexts, ocrTexts);
        } finally {
            first.recycle();
        }
        publish("認識結果：黒 " + meta.blackDisplay() + "／白 " + meta.whiteDisplay()
                + (meta.result.isBlank() ? "" : "／結果 " + meta.result));

        Frame initial = rewindToInitial(geometry, meta);
        BoardState current = initial.detection().state();
        BoardFrameFingerprint fingerprint = BoardFrameFingerprint.capture(
                new BitmapPixels(initial.bitmap()), geometry.board);
        meta.initialPosition = current;
        double progress = initial.sliderProgress();
        initial.recycle();

        List<Move> moves = new ArrayList<>();
        byte expected = meta.handicap > 1 ? BoardState.WHITE : BoardState.BLACK;
        for (int turn = 1; turn <= MAX_MOVES; turn++) {
            checkCancelled();
            publish("棋譜読取中：" + moves.size() + "手");
            Outcome outcome = advanceOne(geometry, current, fingerprint, progress, expected);
            if (outcome.type() == OutcomeType.END) {
                saveCompletedGame(meta, moves);
                return;
            }
            if (outcome.type() == OutcomeType.FAILURE) {
                throw new IllegalStateException(outcome.message() + "。SGFは保存していません");
            }
            moves.add(outcome.move());
            current = outcome.after();
            fingerprint = outcome.fingerprint();
            progress = outcome.sliderProgress();
            expected = expected == BoardState.BLACK ? BoardState.WHITE : BoardState.BLACK;
        }
        throw new IllegalStateException(MAX_MOVES + "手に到達しても終局を確認できません。SGFは保存していません");
    }

    private void saveCompletedGame(GameMetadata meta, List<Move> moves) throws Exception {
        publish("SGFを保存中…");
        LocalDate date = LocalDate.now();
        String sgf = SgfWriter.build(meta, moves, date);
        SgfStore.save(this, meta, sgf, date);
        publish("保存完了：Download/" + safeName(meta.blackName) + "_vs_"
                + safeName(meta.whiteName) + "_" + date.toString().replace("-", "")
                + ".sgf（" + moves.size() + "手）");
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

    private Outcome advanceOne(ScreenGeometry geometry, BoardState before,
                               BoardFrameFingerprint beforeFingerprint,
                               double beforeProgress, byte expected) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled();
            terminalSignalUntil = 0;
            gestureTap(geometry, geometry.forward);
            long start = SystemClock.uptimeMillis();
            BoardFrameFingerprint candidate = null;
            int sameCandidate = 0;
            int bannerSightings = 0;
            boolean checkedWindowText = false;

            while (SystemClock.uptimeMillis() - start < 2400) {
                checkCancelled();
                LightFrame frame = captureLightFrame(geometry);
                boolean banner = TerminalBannerDetector.hasBanner(
                        new BitmapPixels(frame.bitmap()), geometry.board);
                if (terminalRecently()) {
                    double p = usableProgress(frame.sliderProgress(), beforeProgress);
                    frame.recycle();
                    return new Outcome(OutcomeType.END, null, before, beforeFingerprint, p,
                            "終局メッセージ");
                }
                if (banner) {
                    bannerSightings++;
                    if (bannerSightings >= 2) {
                        double p = usableProgress(frame.sliderProgress(), beforeProgress);
                        frame.recycle();
                        return new Outcome(OutcomeType.END, null, before, beforeFingerprint, p,
                                "終局バナー");
                    }
                } else {
                    bannerSightings = 0;
                }

                BoardFrameFingerprint observedFingerprint = frame.fingerprint();
                if (!observedFingerprint.equals(beforeFingerprint)) {
                    if (observedFingerprint.equals(candidate)) sameCandidate++;
                    else {
                        candidate = observedFingerprint;
                        sameCandidate = 1;
                    }
                    if (sameCandidate >= 2) {
                        BoardState observed = analyzeLightState(frame, geometry);
                        MoveDetector.Result diff = MoveDetector.between(before, observed, expected);
                        if (diff.legal()) {
                            double p = usableProgress(frame.sliderProgress(), beforeProgress);
                            frame.recycle();
                            return new Outcome(OutcomeType.MOVE, diff.move(), observed,
                                    observedFingerprint, p, "ok");
                        }
                        if (observed.equals(before)
                                && progressAdvanced(beforeProgress, frame.sliderProgress())) {
                            double p = frame.sliderProgress();
                            frame.recycle();
                            return new Outcome(OutcomeType.PASS, Move.pass(expected), before,
                                    observedFingerprint, p, "pass");
                        }
                        candidate = null;
                        sameCandidate = 0;
                    }
                } else if (SystemClock.uptimeMillis() - start > 500) {
                    if (!checkedWindowText) {
                        checkedWindowText = true;
                        if (windowHasTerminalText()) {
                            double p = usableProgress(frame.sliderProgress(), beforeProgress);
                            frame.recycle();
                            return new Outcome(OutcomeType.END, null, before,
                                    beforeFingerprint, p, "終局メッセージ");
                        }
                    }
                    if (progressAdvanced(beforeProgress, frame.sliderProgress())) {
                        BoardState observed = analyzeLightState(frame, geometry);
                        if (observed.equals(before)) {
                            double p = frame.sliderProgress();
                            frame.recycle();
                            return new Outcome(OutcomeType.PASS, Move.pass(expected), before,
                                    observedFingerprint, p, "pass");
                        }
                        MoveDetector.Result diff = MoveDetector.between(before, observed, expected);
                        if (diff.legal()) {
                            double p = frame.sliderProgress();
                            frame.recycle();
                            return new Outcome(OutcomeType.MOVE, diff.move(), observed,
                                    observedFingerprint, p, "ok");
                        }
                    }
                }
                frame.recycle();
                Thread.sleep(35);
            }

            // The sparse fingerprint controls polling only.  Before accepting a move,
            // always run the complete detector against the settled bitmap.
            LightFrame settled = awaitLightStable(geometry, null, 900);
            boolean settledBanner = TerminalBannerDetector.hasBanner(
                    new BitmapPixels(settled.bitmap()), geometry.board);
            if (terminalRecently() || settledBanner || windowHasTerminalText()) {
                double p = usableProgress(settled.sliderProgress(), beforeProgress);
                settled.recycle();
                return new Outcome(OutcomeType.END, null, before, beforeFingerprint, p,
                        "終局メッセージ");
            }
            BoardFrameFingerprint settledFingerprint = settled.fingerprint();
            boolean fingerprintChanged = !settledFingerprint.equals(beforeFingerprint);

            // A changed picture without slider movement is treated as an unfinished render.
            // This is the same guarded retry used by the real-device-tested speed branch.
            if (fingerprintChanged
                    && beforeProgress >= 0
                    && !progressAdvanced(beforeProgress, settled.sliderProgress())) {
                settled.recycle();
                continue;
            }

            BoardState settledState = analyzeLightState(settled, geometry);
            if (settledState.equals(before)) {
                if (progressAdvanced(beforeProgress, settled.sliderProgress())) {
                    double p = settled.sliderProgress();
                    settled.recycle();
                    return new Outcome(OutcomeType.PASS, Move.pass(expected), before,
                            settledFingerprint, p, "pass");
                }
                settled.recycle();
                continue;
            }

            MoveDetector.Result settledDiff = MoveDetector.between(before, settledState, expected);
            if (settledDiff.legal()) {
                double p = usableProgress(settled.sliderProgress(), beforeProgress);
                settled.recycle();
                return new Outcome(OutcomeType.MOVE, settledDiff.move(), settledState,
                        settledFingerprint, p, "ok");
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
                                        long timeout) throws Exception {
        long start = SystemClock.uptimeMillis();
        BoardFrameFingerprint candidate = null;
        int repeats = 0;
        LightFrame held = null;
        while (SystemClock.uptimeMillis() - start < timeout) {
            checkCancelled();
            LightFrame frame = captureLightFrame(geometry);
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
        return BoardAnalyzer.detect(new BitmapPixels(frame.bitmap()), geometry.board).state();
    }

    private LightFrame captureLightFrame(ScreenGeometry geometry) throws Exception {
        Bitmap bitmap = CaptureService.capture(700);
        if (bitmap == null) bitmap = captureWithAccessibility(1200);
        if (bitmap == null) {
            throw new IllegalStateException("画面画像を取得できません。画面読取の許可をやり直してください");
        }
        try {
            BoardFrameFingerprint fingerprint = BoardFrameFingerprint.capture(
                    new BitmapPixels(bitmap), geometry.board);
            return new LightFrame(bitmap, fingerprint, geometry.sliderProgress(bitmap));
        } catch (RuntimeException | Error error) {
            bitmap.recycle();
            throw error;
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
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) collectNodeText(root, result, remaining);
                    if (remaining[0] <= 0) break;
                }
            }
            if (result.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) collectNodeText(root, result, remaining);
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
            if (child != null) collectNodeText(child, out, remaining);
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
                new GestureDescription.StrokeDescription(path, 0, 70)).build());
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
        AtomicReference<Bitmap> result = new AtomicReference<>();
        main.post(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult screenshot) {
                        HardwareBuffer buffer = screenshot.getHardwareBuffer();
                        try {
                            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                            if (wrapped != null) {
                                result.set(wrapped.copy(Bitmap.Config.ARGB_8888, false));
                            }
                        } finally {
                            buffer.close();
                            latch.countDown();
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
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) ? result.get() : null;
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
        main.post(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("操作パネルの制御がタイムアウトしました");
        }
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
