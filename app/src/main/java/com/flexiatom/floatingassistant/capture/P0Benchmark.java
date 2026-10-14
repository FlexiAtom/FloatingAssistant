package com.flexiatom.floatingassistant.capture;

import android.graphics.Bitmap;
import android.util.Log;

import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P0 前置实测：在基准机上测无障碍截图的跑通率与延迟分布（提案 §4.3 / §10）。
 * 结果以机器可读形式打到 logcat tag {@code P0CAPTURE}。
 */
public final class P0Benchmark {

    private static final String TAG = "P0CAPTURE";

    private P0Benchmark() {
    }

    /** 在后台线程运行 {@code iterations} 次截图并汇总。需在无障碍服务已连接时调用。 */
    public static void run(int iterations) {
        SphereAccessibilityService service = SphereAccessibilityService.getInstance();
        if (service == null) {
            Log.e(TAG, "RESULT error=no_service_connected");
            return;
        }
        ScreenCapturer capturer = service.capturer();

        int ok = 0;
        int black = 0;
        int timeout = 0;
        int secured = 0;
        int error = 0;
        List<Long> okLatencies = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            try {
                Bitmap bmp = capturer.capture();
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                ok++;
                okLatencies.add(ms);
                bmp.recycle();
                Log.i(TAG, "attempt=" + i + " outcome=OK ms=" + ms
                        + " size=" + bmp.getWidth() + "x" + bmp.getHeight());
            } catch (ScreenCapturer.CaptureException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                switch (e.outcome) {
                    case BLACK: black++; break;
                    case TIMEOUT: timeout++; break;
                    case SECURED: secured++; break;
                    default: error++; break;
                }
                Log.i(TAG, "attempt=" + i + " outcome=" + e.outcome + " ms=" + ms
                        + " msg=" + e.getMessage());
            }
        }

        double rate = iterations == 0 ? 0 : (100.0 * ok / iterations);
        String latency = summarize(okLatencies);
        Log.i(TAG, "RESULT iterations=" + iterations
                + " ok=" + ok + " black=" + black + " timeout=" + timeout
                + " secured=" + secured + " error=" + error
                + " successRate=" + String.format("%.1f", rate) + "%"
                + " latencyMs[" + latency + "]");
    }

    private static String summarize(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return "none";
        }
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        long sum = 0;
        for (long v : sorted) {
            sum += v;
        }
        long avg = sum / sorted.size();
        long p50 = percentile(sorted, 50);
        long p90 = percentile(sorted, 90);
        return "min=" + min + " p50=" + p50 + " p90=" + p90 + " max=" + max + " avg=" + avg;
    }

    private static long percentile(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }
}
