package com.flexiatom.floatingassistant.mt;

import android.content.Context;
import android.util.Log;

import com.flexiatom.floatingassistant.core.MtEngine;

import java.io.File;

/**
 * MT 引擎装配：懒建 {@link LlamaMtEngine}（首用加载模型，避开服务启动峰值内存）。
 *
 * <p>模型位于 {@code getExternalFilesDir(null)} 根（与 OCR 模型同一约定）。缺模型/
 * 加载失败 → {@link PassthroughMtEngine}，A 态链路仍只显 OCR 原文、绝不崩（保守降级）。
 */
public final class MtEngineFactory {

    private static final String TAG = "MtFactory";

    private static final String MODEL = "Hy-MT2-1.8B-1.25Bit.gguf";
    // 模型自带 n_ctx=61952 太大、KV 白占内存；2048 容 ~1500 token 页 + 256 生成余量。
    private static final int N_CTX = 2048;
    // 拓扑 8 核仅 2 大核；对齐 OCR THREADS=2 与已证 -t2 档。满核留后续按场景调。
    private static final int N_THREADS = 2;

    private MtEngineFactory() {
    }

    public static MtEngine create(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.w(TAG, "no external files dir → passthrough");
            return new PassthroughMtEngine();
        }
        File model = new File(base, MODEL);
        if (!model.isFile()) {
            Log.w(TAG, "model missing " + model.getAbsolutePath() + " → passthrough");
            return new PassthroughMtEngine();
        }
        try {
            long t = System.nanoTime();
            LlamaMtEngine engine = new LlamaMtEngine(model.getAbsolutePath(), N_CTX, N_THREADS);
            Log.i(TAG, "LlamaMtEngine ready loadMs=" + ((System.nanoTime() - t) / 1_000_000L)
                    + " nCtx=" + N_CTX + " nThreads=" + N_THREADS);
            return engine;
        } catch (Throwable err) {
            Log.e(TAG, "mt engine load failed → passthrough: " + err.getClass().getSimpleName()
                    + ": " + err.getMessage());
            return new PassthroughMtEngine();
        }
    }
}
