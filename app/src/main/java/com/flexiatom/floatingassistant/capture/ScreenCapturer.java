package com.flexiatom.floatingassistant.capture;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;

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

    private static final int DISPLAY_ID = 0;

    private final AccessibilityService service;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private long lastCaptureAtMs;

    public ScreenCapturer(AccessibilityService service) {
        this.service = service;
    }

    /**
     * 截取整屏并返回软件位图（调用方负责 {@code recycle()}）。
     *
     * @throws CaptureException 带 {@link Outcome} 说明失败类别
     */
    public Bitmap capture() throws CaptureException {
        throttle();
        CaptureException last = new CaptureException(Outcome.ERROR, "not attempted");
        for (int attempt = 0; attempt <= CapturePolicy.RETRY; attempt++) {
            try {
                return attemptCapture();
            } catch (CaptureException e) {
                last = e;
                if (e.outcome == Outcome.SECURED || e.outcome == Outcome.BLACK) {
                    throw e; // 受保护/黑屏重试无意义，直接上抛供三态判定
                }
                // TIMEOUT / ERROR → 重试
            }
        }
        throw last;
    }

    private Bitmap attemptCapture() throws CaptureException {
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
            result = future.get(CapturePolicy.TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
            if (isBlack(sw)) {
                sw.recycle();
                throw new CaptureException(Outcome.BLACK, "captured frame is black/empty");
            }
            return sw;
        }
    }

    private void throttle() {
        long wait = CapturePolicy.MIN_INTERVAL_MS - (System.currentTimeMillis() - lastCaptureAtMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 采样网格判定是否纯黑/空图（§4.3 状态 A 要求非纯黑）。 */
    static boolean isBlack(Bitmap bitmap) {
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
                if (r > CapturePolicy.BLACK_LUMA_THRESHOLD
                        || g > CapturePolicy.BLACK_LUMA_THRESHOLD
                        || b > CapturePolicy.BLACK_LUMA_THRESHOLD) {
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
