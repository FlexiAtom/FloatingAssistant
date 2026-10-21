package com.flexiatom.floatingassistant.capture;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.util.Log;

import com.flexiatom.floatingassistant.core.TuningStore;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 无障碍截图封装（提案 §4.3）：带超时 + 重试 + 节流 + 黑屏判定，
 * 区别于 TouchTool 的裸 {@code future.join()}——这里用 {@code future.get(timeout)}。
 */
public final class ScreenCapturer {

    /** 一次截图的最终归类，供三态判定（§4.3）使用。 */
    public enum Outcome {
        /** A：成功且非纯黑/空图 */
        OK,
        /** 截图返回但画面纯黑/空（受保护或自绘画面） */
        BLACK,
        /** 回调超时 */
        TIMEOUT,
        /** 系统返回受保护错误（安全窗口） */
        SECURED,
        /** 其它系统错误（含限流过频，可重试） */
        ERROR
    }

    /** 截图失败异常，携带 {@link Outcome} 供上层判定。 */
    public static final class CaptureException extends Exception {
        public final Outcome outcome;

        CaptureException(Outcome outcome, String message) {
            super(message);
            this.outcome = outcome;
        }
    }

    /** 自检用的单趟回调：生产路径传 null，不产生额外状态。 */
    public interface AttemptListener {
        void onAttempt(int attempt, Outcome outcome, long ms);
    }

    private static final int DISPLAY_ID = 0;
    private static final String TAG = "Capture";

    private final AccessibilityService service;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final TuningStore tuning;
    private long lastCaptureAtMs;
    /** 预热是进程内一次性的；服务重连/进程重建后重新计。 */
    private boolean warmedUp;

    public ScreenCapturer(AccessibilityService service) {
        this.service = service;
        this.tuning = new TuningStore(service);
    }

    /**
     * 截取整屏并返回软件位图（调用方负责 {@code recycle()}）。
     *
     * @throws CaptureException 带 {@link Outcome} 说明失败类别
     */
    public Bitmap capture() throws CaptureException {
        return capture(null);
    }

    /**
     * 带单趟回调的截图。参数在**每次调用开头**从 {@link TuningStore} 快照一次：
     * 一轮重试内不因用户中途改设置而变语义，下一次截图自然读到新值（热生效）。
     * {@code synchronized} 让自检与生产路由共用同一个截图窗口时串行等待，而不是互相限流报错。
     */
    public synchronized Bitmap capture(AttemptListener listener) throws CaptureException {
        final long timeoutMs = tuning.timeoutMs();
        final int retry = tuning.retryCount();
        final boolean detectBlank = tuning.detectBlank();
        final int blackThreshold = tuning.blackThreshold();

        warmUpIfNeeded(timeoutMs, detectBlank, blackThreshold);
        throttle(tuning.minIntervalMs());

        CaptureException last = new CaptureException(Outcome.ERROR, "not attempted");
        for (int attempt = 0; attempt <= retry; attempt++) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                Bitmap bmp = attemptCapture(timeoutMs, detectBlank, blackThreshold);
                if (listener != null) {
                    listener.onAttempt(attempt, Outcome.OK, SystemClock.elapsedRealtime() - t0);
                }
                return bmp;
            } catch (CaptureException e) {
                last = e;
                if (listener != null) {
                    listener.onAttempt(attempt, e.outcome, SystemClock.elapsedRealtime() - t0);
                }
                if (e.outcome == Outcome.SECURED || e.outcome == Outcome.BLACK) {
                    throw e; // 受保护/黑屏重试无意义，直接上抛供三态判定
                }
                // TIMEOUT / ERROR → 重试
            }
        }
        throw last;
    }

    /**
     * 首次截图预热（提案 §7.3 `capture.warmup`）：拍一张立即丢弃，让部分 ROM 的首次慢调用先吃掉。
     * 预热失败不改变本次截图结果——它只是优化，不是前置条件；失败原因记日志供自检判读。
     * 默认关：当前没有「首次明显更慢」的实测，不为未证收益给每次冷启动加一趟。
     */
    private void warmUpIfNeeded(long timeoutMs, boolean detectBlank, int blackThreshold) {
        if (warmedUp || !tuning.warmup()) {
            return;
        }
        warmedUp = true;
        long t0 = SystemClock.elapsedRealtime();
        try {
            Bitmap bmp = attemptCapture(timeoutMs, detectBlank, blackThreshold);
            bmp.recycle();
            Log.i(TAG, "warmup ok ms=" + (SystemClock.elapsedRealtime() - t0));
        } catch (CaptureException e) {
            Log.i(TAG, "warmup ignored outcome=" + e.outcome + " ms="
                    + (SystemClock.elapsedRealtime() - t0));
        } catch (Throwable t) {
            Log.i(TAG, "warmup ignored error=" + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private Bitmap attemptCapture(long timeoutMs, boolean detectBlank, int blackThreshold)
            throws CaptureException {
        lastCaptureAtMs = System.currentTimeMillis();
        final CompletableFuture<Object> future = new CompletableFuture<>();
        try {
            service.takeScreenshot(DISPLAY_ID, ioExecutor,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            future.complete(result);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            future.complete(errorCode);
                        }
                    });
        } catch (IllegalStateException e) {
            throw new CaptureException(Outcome.ERROR, "takeScreenshot rejected: " + e.getMessage());
        }

        Object result;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CaptureException(Outcome.TIMEOUT, "takeScreenshot timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException(Outcome.ERROR, "interrupted");
        } catch (CancellationException | java.util.concurrent.ExecutionException e) {
            throw new CaptureException(Outcome.ERROR, "future failed: " + e.getMessage());
        }

        if (result instanceof Integer) {
            int code = (Integer) result;
            Outcome o = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                    ? Outcome.SECURED : Outcome.ERROR;
            throw new CaptureException(o, "errorCode=" + code);
        }
        if (!(result instanceof AccessibilityService.ScreenshotResult)) {
            throw new CaptureException(Outcome.ERROR, "unexpected callback result: " + result);
        }

        AccessibilityService.ScreenshotResult shot = (AccessibilityService.ScreenshotResult) result;
        HardwareBuffer buffer = shot.getHardwareBuffer();
        ColorSpace colorSpace = shot.getColorSpace();
        if (buffer == null) {
            throw new CaptureException(Outcome.ERROR, "null hardware buffer");
        }
        try (HardwareBuffer hb = buffer) {
            Bitmap sw = HardwareBufferConverter.toSoftwareBitmap(hb, colorSpace);
            if (detectBlank && isBlack(sw, blackThreshold)) {
                sw.recycle();
                throw new CaptureException(Outcome.BLACK, "captured frame is black/empty");
            }
            return sw;
        }
    }

    private void throttle(long minIntervalMs) {
        if (minIntervalMs <= 0L) {
            return;
        }
        long wait = minIntervalMs - (System.currentTimeMillis() - lastCaptureAtMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 采样网格判定是否纯黑/空图（§4.3 状态 A 要求非纯黑）。阈值由调参页给出。 */
    static boolean isBlack(Bitmap bitmap, int threshold) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w == 0 || h == 0) {
            return true;
        }
        int n = CapturePolicy.BLACK_SAMPLE_GRID;
        for (int gx = 0; gx < n; gx++) {
            for (int gy = 0; gy < n; gy++) {
                int x = (int) ((gx + 0.5) * w / n);
                int y = (int) ((gy + 0.5) * h / n);
                int color = bitmap.getPixel(Math.min(x, w - 1), Math.min(y, h - 1));
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;
                if (r > threshold || g > threshold || b > threshold) {
                    return false;
                }
            }
        }
        return true;
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
    }
}
