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

package com.flexiatom.floatingassistant.ocr;

import android.content.Context;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Random;

/**
 * P1-b4 探针：ORT-Android 的 NNAPI Execution Provider 在本机（天玑 6100+ / Android 15，
 * 官方已弃用 NNAPI）能否加载 PP-OCR det、是否**数值正确**、以及延迟。
 *
 * <p>纪律（呼应本会话记入的坑）：先证**正确性**再谈延迟——NNAPI 厂商驱动可能静默出错，
 * 只报"更快"而不核对输出等于把读数当结论。故同一定型输入跑 CPU 与 NNAPI 两路，比 prob 图统计量。
 * 结果打到 logcat tag {@code NNAPI}。
 */
public final class NnapiProbe {

    private static final String TAG = "NNAPI";

    private NnapiProbe() {
    }

    public static void run(Context context, String detRel, int h, int w, int threads, int iterations) {
        java.io.File model = new java.io.File(context.getExternalFilesDir(null), detRel);
        if (!model.isFile()) {
            Log.e(TAG, "RESULT error=model_not_found path=" + model.getAbsolutePath());
            return;
        }
        // 固定伪随机输入（两路共用，保证可比）。
        float[] data = new float[3 * h * w];
        Random rnd = new Random(12345L);
        for (int i = 0; i < data.length; i++) {
            data[i] = rnd.nextFloat() * 2f - 1f;
        }
        long[] shape = {1, 3, h, w};
        String inputName;

        try (OrtEnvironment env = OrtEnvironment.getEnvironment()) {
            // --- CPU 基准 ---
            float[] cpuFlat;
            long cpuInferMs;
            try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
                o.setIntraOpNumThreads(threads);
                long t0 = System.nanoTime();
                try (OrtSession s = env.createSession(model.getAbsolutePath(), o)) {
                    inputName = s.getInputNames().iterator().next();
                    cpuInferMs = timedRun(s, inputName, env, data, shape, iterations);
                    long load = (System.nanoTime() - t0) / 1_000_000L;
                    cpuFlat = runFlat(s, inputName, env, data, shape);
                    Log.i(TAG, "CPU loadMs=" + load + " inferMs=" + cpuInferMs
                            + " " + stats(cpuFlat, "cpu"));
                }
            }

            // --- NNAPI 尝试 ---
            try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
                o.addNnapi();
                long t0 = System.nanoTime();
                try (OrtSession s = env.createSession(model.getAbsolutePath(), o)) {
                    String in = s.getInputNames().iterator().next();
                    long nnapiMs = timedRun(s, in, env, data, shape, iterations);
                    long load = (System.nanoTime() - t0) / 1_000_000L;
                    float[] nn = runFlat(s, in, env, data, shape);
                    Log.i(TAG, "NNAPI loadMs=" + load + " inferMs=" + nnapiMs
                            + " " + stats(nn, "nnapi"));
                    Log.i(TAG, "COMPARE " + compare(cpuFlat, nn)
                            + " speedup=" + (cpuInferMs / Math.max(1, nnapiMs)) + "x");
                }
            } catch (Throwable t) {
                Log.e(TAG, "NNAPI RESULT error=" + t.getClass().getSimpleName()
                        + " msg=" + t.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "CPU RESULT error=" + t.getClass().getSimpleName() + " msg=" + t.getMessage(), t);
        }
    }

    private static long timedRun(OrtSession s, String in, OrtEnvironment env,
                                 float[] data, long[] shape, int iterations) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iterations + 2; i++) {
            long a = System.nanoTime();
            try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
                 OrtSession.Result r = s.run(Collections.singletonMap(in, t))) {
                r.get(0).close();
            }
            long ms = (System.nanoTime() - a) / 1_000_000L;
            if (i >= 2 && ms < best) best = ms;
        }
        return best;
    }

    private static float[] runFlat(OrtSession s, String in, OrtEnvironment env,
                                   float[] data, long[] shape) throws Exception {
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
             OrtSession.Result r = s.run(Collections.singletonMap(in, t))) {
            OnnxValue v = r.get(0);
            float[][][][] o = (float[][][][]) ((OnnxTensor) v).getValue();
            float[][] map = o[0][0];
            int rh = map.length, rw = map[0].length;
            float[] flat = new float[rh * rw];
            for (int y = 0; y < rh; y++) {
                System.arraycopy(map[y], 0, flat, y * rw, rw);
            }
            return flat;
        }
    }

    private static String stats(float[] f, String tag) {
        float mx = -1e9f, mn = 1e9f;
        double sum = 0;
        for (float v : f) {
            if (v > mx) mx = v;
            if (v < mn) mn = v;
            sum += v;
        }
        return tag + "[n=" + f.length + " max=" + fmt(mx) + " min=" + fmt(mn)
                + " mean=" + fmt((float) (sum / f.length)) + "]";
    }

    private static String compare(float[] a, float[] b) {
        if (a.length != b.length) {
            return "LENGTH_MISMATCH " + a.length + " vs " + b.length;
        }
        double maxAbs = 0, sumSq = 0;
        int n = a.length;
        for (int i = 0; i < n; i++) {
            double d = Math.abs(a[i] - b[i]);
            if (d > maxAbs) maxAbs = d;
            sumSq += d * d;
        }
        double rmse = Math.sqrt(sumSq / n);
        String verdict = (maxAbs < 0.05 && rmse < 0.01) ? "MATCH" : "DIVERGE";
        return "maxAbsDiff=" + fmt((float) maxAbs) + " rmse=" + fmt((float) rmse)
                + " -> " + verdict;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }
}
