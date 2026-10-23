/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of FloatingAssistant.
 *
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
        run(iterations, 0L, "-");
    }

    /**
     * 带启动延迟与场景标签的采集：先睡 {@code startDelayMs}（留出把目标 App 切到前台的窗口），
     * 再连续采集；结果 {@code RESULT} 行附带 {@code label} 便于区分普通页/视频页/游戏页。
     */
    public static void run(int iterations, long startDelayMs, String label) {
        SphereAccessibilityService service = SphereAccessibilityService.getInstance();
        if (service == null) {
            Log.e(TAG, "RESULT label=" + label + " error=no_service_connected");
            return;
        }
        ScreenCapturer capturer = service.capturer();

        if (startDelayMs > 0) {
            Log.i(TAG, "READY label=" + label + " iterations=" + iterations
                    + " 请在 " + startDelayMs + "ms 内把目标 App 切到前台");
            try {
                Thread.sleep(startDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "RESULT label=" + label + " error=interrupted_before_start");
                return;
            }
        }

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
                int bw = bmp.getWidth();
                int bh = bmp.getHeight();
                bmp.recycle();
                ok++;
                okLatencies.add(ms);
                Log.i(TAG, "attempt=" + i + " label=" + label + " outcome=OK ms=" + ms
                        + " size=" + bw + "x" + bh);
            } catch (ScreenCapturer.CaptureException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                switch (e.outcome) {
                    case BLACK: black++; break;
                    case TIMEOUT: timeout++; break;
                    case SECURED: secured++; break;
                    default: error++; break;
                }
                Log.i(TAG, "attempt=" + i + " label=" + label + " outcome=" + e.outcome + " ms=" + ms
                        + " msg=" + e.getMessage());
            }
        }

        double rate = iterations == 0 ? 0 : (100.0 * ok / iterations);
        String latency = summarize(okLatencies);
        Log.i(TAG, "RESULT label=" + label
                + " iterations=" + iterations
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
