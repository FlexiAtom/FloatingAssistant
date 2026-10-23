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
import java.util.Map;

/**
 * P1-a 探针：验证 onnxruntime-android 能在基准机上加载并推理 PP-OCR det 模型，
 * 量出单次前推理耗时。仅跑推理、不做 DB 后处理（正确 OCR 管线为后续切片）。
 * 结果打到 logcat tag {@code ORTPROBE}。
 */
public final class OcrOrtProbe {

    private static final String TAG = "ORTPROBE";

    private OcrOrtProbe() {
    }

    /**
     * @param modelRelPath 相对 getExternalFilesDir(null) 的模型路径
     * @param h            det 输入高（32 的倍数）
     * @param w            det 输入宽（32 的倍数）
     * @param threads      intra-op 线程数
     * @param iterations   计时次数（前置 2 次预热）
     */
    public static void run(Context context, String modelRelPath, int h, int w,
                           int threads, int iterations) {
        java.io.File model = new java.io.File(context.getExternalFilesDir(null), modelRelPath);
        if (!model.isFile()) {
            Log.e(TAG, "RESULT error=model_not_found path=" + model.getAbsolutePath());
            return;
        }
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setIntraOpNumThreads(threads);
            long t0 = System.nanoTime();
            try (OrtSession session = env.createSession(model.getAbsolutePath(), opts)) {
                long loadMs = (System.nanoTime() - t0) / 1_000_000L;
                String inputName = session.getInputNames().iterator().next();
                long[] shape = {1, 3, h, w};
                float[] data = new float[3 * h * w]; // 零输入即可测纯推理耗时

                long[] latencies = new long[iterations];
                String outShape = "?";
                for (int i = 0; i < iterations + 2; i++) {
                    long a = System.nanoTime();
                    try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
                         OrtSession.Result res = session.run(Collections.singletonMap(inputName, input))) {
                        OnnxValue out = res.get(0);
                        long[] os = ((OnnxTensor) out).getInfo().getShape();
                        outShape = os[0] + "x" + os[1] + "x" + os[2] + "x" + os[3];
                    }
                    long ms = (System.nanoTime() - a) / 1_000_000L;
                    if (i >= 2) {
                        latencies[i - 2] = ms;
                    }
                    Log.i(TAG, "iter=" + i + " ms=" + ms + (i < 2 ? " (warmup)" : ""));
                }
                java.util.Arrays.sort(latencies);
                long min = latencies[0];
                long max = latencies[latencies.length - 1];
                long sum = 0;
                for (long v : latencies) {
                    sum += v;
                }
                long avg = sum / latencies.length;
                long p50 = latencies[latencies.length / 2];
                Log.i(TAG, "RESULT model=" + model.getName()
                        + " input=1x3x" + h + "x" + w
                        + " threads=" + threads
                        + " loadMs=" + loadMs
                        + " outShape=" + outShape
                        + " iterations=" + iterations
                        + " inferMs[min=" + min + " p50=" + p50 + " avg=" + avg + " max=" + max + "]");
            }
        } catch (Throwable t) {
            Log.e(TAG, "RESULT error=" + t.getClass().getSimpleName() + " msg=" + t.getMessage(), t);
        }
    }
}
