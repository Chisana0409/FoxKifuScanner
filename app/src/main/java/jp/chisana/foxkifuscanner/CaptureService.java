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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CaptureService extends Service {
    public static final String ACTION_START = "jp.chisana.foxkifuscanner.START_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    private static final String CHANNEL = "capture";
    private static volatile CaptureService instance;

    private volatile MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread imageThread;
    private volatile FrameRequest pending;
    private final Object cacheLock = new Object();
    private Bitmap cachedFrame;
    private int width, height, density;

    private static final class FrameRequest {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Bitmap bitmap;
        volatile boolean abandoned;
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
                projection = manager.getMediaProjection(code, data);
                if (projection == null) throw new IllegalStateException("画面読取を開始できません");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stopProjection(); }
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
                display = projection.createVirtualDisplay("FoxKifuCapture", width, height, density,
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
        FrameRequest request = pending;
        try {
            image = source.acquireLatestImage();
            if (image == null || request == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap result = Bitmap.createBitmap(padded, 0, 0, width, height);
            padded.recycle();
            if (request.abandoned) {
                result.recycle();
            } else {
                request.bitmap = result;
                updateCache(result);
            }
        } catch (Throwable ignored) {
            // A malformed vendor frame must not terminate the foreground process.
        } finally {
            if (image != null) image.close();
            if (request != null) {
                if (pending == request) pending = null;
                request.latch.countDown();
            }
        }
    }

    public static boolean isReady() { return instance != null && instance.projection != null; }

    public static Bitmap capture(long timeoutMs) throws InterruptedException {
        CaptureService service = instance;
        if (service == null || service.projection == null) return null;
        FrameRequest request = new FrameRequest();
        if (service.pending != null) return service.copyCachedFrame();
        boolean hasCache = service.hasCachedFrame();
        service.pending = request;
        long waitMs = hasCache ? Math.min(timeoutMs, 650) : timeoutMs;
        if (!request.latch.await(waitMs, TimeUnit.MILLISECONDS)) {
            request.abandoned = true;
            if (service.pending == request) service.pending = null;
            return service.copyCachedFrame();
        }
        return request.bitmap != null ? request.bitmap : service.copyCachedFrame();
    }

    private boolean hasCachedFrame() {
        synchronized (cacheLock) {
            return cachedFrame != null && !cachedFrame.isRecycled();
        }
    }

    private Bitmap copyCachedFrame() {
        synchronized (cacheLock) {
            if (cachedFrame == null || cachedFrame.isRecycled()) return null;
            try {
                return cachedFrame.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private void updateCache(Bitmap source) {
        Bitmap copy;
        try {
            copy = source.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable ignored) {
            return;
        }
        synchronized (cacheLock) {
            Bitmap old = cachedFrame;
            cachedFrame = copy;
            if (old != null && !old.isRecycled()) old.recycle();
        }
    }

    public synchronized void stopProjection() {
        FrameRequest request = pending;
        pending = null;
        if (request != null) {
            request.abandoned = true;
            request.latch.countDown();
        }
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        MediaProjection old = projection; projection = null;
        if (old != null) try { old.stop(); } catch (Exception ignored) {}
        if (imageThread != null) { imageThread.quitSafely(); imageThread = null; }
        synchronized (cacheLock) {
            if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
            cachedFrame = null;
        }
    }

    public static void shutdown(Context context) {
        CaptureService service = instance;
        if (service != null) service.stopProjection();
        context.stopService(new Intent(context, CaptureService.class));
    }

    @Override public void onDestroy() { stopProjection(); instance = null; super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
