package jp.chisana.foxkifuscanner;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public final class CaptureService extends Service {
    public static final String ACTION_START = "jp.chisana.foxkifuscanner.START_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    private static final String CHANNEL = "capture";
    private static final long LEGACY_FRESH_FRAME_WAIT_MS = 120L;
    private static final AtomicLong FRAME_SEQUENCE = new AtomicLong();
    private static volatile CaptureService instance;

    private volatile MediaProjection projection;
    private VirtualDisplay display;
    private volatile ImageReader reader;
    private HandlerThread imageThread;
    private final Object frameLock = new Object();
    private Image latestImage;
    private long latestSequence;
    private long latestTimestampNanos;
    private int width, height, density;

    /** A caller-owned frame copy and the metadata assigned to that exact image. */
    public record CapturedFrame(Bitmap bitmap, long sequence, long timestampNanos) {
        public void recycle() { CaptureService.recycle(bitmap); }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "画面読取", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("野狐棋譜スキャナー")
                .setContentText("画面読取の準備ができています")
                .setOngoing(true).build();
        startForeground(101, n);
    }

    @SuppressWarnings("deprecation")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            try {
                stopProjection();
                int code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (code != Activity.RESULT_OK || data == null) {
                    throw new IllegalStateException("画面読取の許可情報がありません");
                }
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                MediaProjection activeProjection = manager.getMediaProjection(code, data);
                if (activeProjection == null) {
                    throw new IllegalStateException("画面読取を開始できません");
                }
                projection = activeProjection;
                activeProjection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stopProjectionIfCurrent(activeProjection); }
                }, new Handler(Looper.getMainLooper()));
                DisplayMetrics metrics = new DisplayMetrics();
                ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
                width = metrics.widthPixels;
                height = metrics.heightPixels;
                density = metrics.densityDpi;
                imageThread = new HandlerThread("fox-capture");
                imageThread.start();
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
                reader.setOnImageAvailableListener(this::onImage, new Handler(imageThread.getLooper()));
                display = activeProjection.createVirtualDisplay(
                        "FoxKifuCapture", width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                MainActivity.publishStatus(this, "画面読取の許可済み");
            } catch (Throwable error) {
                stopProjection();
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                MainActivity.publishStatus(this, "画面読取を開始できません：" + detail);
            }
        }
        return START_NOT_STICKY;
    }

    private void onImage(ImageReader source) {
        Image image = null;
        Image oldImage = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            synchronized (frameLock) {
                // A callback from a reader that was closed during projection restart is stale.
                if (source != reader || projection == null) return;
                long timestampNanos = image.getTimestamp();
                if (timestampNanos <= 0L) timestampNanos = SystemClock.elapsedRealtimeNanos();
                if (timestampNanos <= latestTimestampNanos) {
                    timestampNanos = latestTimestampNanos + 1L;
                }
                oldImage = latestImage;
                latestImage = image;
                image = null;
                latestSequence = FRAME_SEQUENCE.incrementAndGet();
                latestTimestampNanos = timestampNanos;
                frameLock.notifyAll();
            }
        } catch (Throwable ignored) {
            // A malformed vendor frame must not terminate the foreground process.
        } finally {
            close(image);
            close(oldImage);
        }
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (pixelStride <= 0 || rowStride < pixelStride * imageWidth) {
            throw new IllegalStateException("Invalid screen frame stride");
        }
        int paddedWidth = imageWidth
                + (rowStride - pixelStride * imageWidth) / pixelStride;
        Bitmap padded = Bitmap.createBitmap(
                paddedWidth, imageHeight, Bitmap.Config.ARGB_8888);
        try {
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);
            if (paddedWidth == imageWidth) return padded;
            return Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight);
        } finally {
            if (paddedWidth != imageWidth) recycle(padded);
        }
    }

    public static boolean isReady() { return instance != null && instance.projection != null; }

    public static Bitmap capture(long timeoutMs) throws InterruptedException {
        CaptureService service = instance;
        if (service == null || service.projection == null) return null;
        long sequence = service.sequence();
        boolean hasCache = service.hasLatestImage();
        long waitMs = Math.max(0L, timeoutMs);
        if (hasCache) waitMs = Math.min(waitMs, LEGACY_FRESH_FRAME_WAIT_MS);
        CapturedFrame fresh = service.awaitFrameAfter(sequence, waitMs);
        return fresh != null ? fresh.bitmap() : service.copyCachedFrame();
    }

    /** Returns the last sequence assigned in this process, or zero before the first frame. */
    public static long currentSequence() {
        CaptureService service = instance;
        return service == null ? FRAME_SEQUENCE.get() : service.sequence();
    }

    /** Returns the monotonic timestamp attached to the currently retained image. */
    public static long currentFrameTimestampNanos() {
        CaptureService service = instance;
        if (service == null) return 0L;
        synchronized (service.frameLock) {
            return service.latestImage == null ? 0L : service.latestTimestampNanos;
        }
    }

    /** Immediately decodes the latest retained image with its exact metadata. */
    public static CapturedFrame latestFrame() {
        CaptureService service = instance;
        return service == null ? null : service.copyCachedCapturedFrame();
    }

    /**
     * Waits for and decodes a frame whose sequence is strictly newer than {@code afterSequence}.
     * Unlike {@link #capture(long)}, this method never falls back to a stale retained image.
     */
    public static CapturedFrame captureAfter(long afterSequence, long timeoutMs)
            throws InterruptedException {
        CaptureService service = instance;
        if (service == null || service.projection == null) return null;
        return service.awaitFrameAfter(afterSequence, Math.max(0L, timeoutMs));
    }

    private long sequence() {
        synchronized (frameLock) {
            return latestImage == null ? FRAME_SEQUENCE.get() : latestSequence;
        }
    }

    private CapturedFrame awaitFrameAfter(long afterSequence, long timeoutMs)
            throws InterruptedException {
        long startedAt = SystemClock.elapsedRealtime();
        long minimumSequence = afterSequence;
        synchronized (frameLock) {
            while (projection != null) {
                if (latestImage != null && latestSequence > minimumSequence) {
                    CapturedFrame frame = decodeLatestFrameLocked();
                    if (frame != null) return frame;
                    // Do not spin on a malformed vendor buffer; wait for its successor.
                    minimumSequence = latestSequence;
                }
                long remaining = timeoutMs - (SystemClock.elapsedRealtime() - startedAt);
                if (remaining <= 0L) return null;
                frameLock.wait(remaining);
            }
            return null;
        }
    }

    private boolean hasLatestImage() {
        synchronized (frameLock) {
            return latestImage != null;
        }
    }

    private Bitmap copyCachedFrame() {
        CapturedFrame frame = copyCachedCapturedFrame();
        return frame == null ? null : frame.bitmap();
    }

    private CapturedFrame copyCachedCapturedFrame() {
        synchronized (frameLock) {
            return decodeLatestFrameLocked();
        }
    }

    /** Must be called while holding {@link #frameLock}. */
    private CapturedFrame decodeLatestFrameLocked() {
        if (latestImage == null) return null;
        try {
            Bitmap bitmap = toBitmap(latestImage);
            return new CapturedFrame(bitmap, latestSequence, latestTimestampNanos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private synchronized void stopProjectionIfCurrent(MediaProjection expected) {
        if (projection == expected) stopProjection();
    }

    public synchronized void stopProjection() {
        VirtualDisplay oldDisplay = display;
        display = null;
        ImageReader oldReader;
        MediaProjection oldProjection;
        Image oldImage;
        synchronized (frameLock) {
            oldReader = reader;
            reader = null;
            oldProjection = projection;
            projection = null;
            oldImage = latestImage;
            latestImage = null;
            frameLock.notifyAll();
        }
        close(oldImage);
        if (oldDisplay != null) oldDisplay.release();
        if (oldReader != null) oldReader.close();
        if (oldProjection != null) try { oldProjection.stop(); } catch (Exception ignored) {}
        if (imageThread != null) { imageThread.quitSafely(); imageThread = null; }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static void close(Image image) {
        if (image != null) try { image.close(); } catch (Throwable ignored) {}
    }

    public static void shutdown(Context context) {
        CaptureService service = instance;
        if (service != null) service.stopProjection();
        context.stopService(new Intent(context, CaptureService.class));
    }

    @Override public void onDestroy() { stopProjection(); instance = null; super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
