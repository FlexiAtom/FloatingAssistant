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

package com.flexiatom.floatingassistant.mt;

import android.content.Context;
import android.util.Log;

import com.flexiatom.floatingassistant.core.MtEngine;

import java.io.File;

/**
 * MT 引擎装配：按 {@link MtModelKind} 懒建 {@link LlamaMtEngine}（首用加载模型，避开服务启动峰值内存）。
 *
 * <p>模型位于 {@code getExternalFilesDir(null)} 根（与 OCR 模型同一约定）。缺模型/
 * 加载失败 → {@link PassthroughMtEngine}，A 态链路仍只显 OCR 原文、绝不崩（保守降级）。
 */
public final class MtEngineFactory {

    private static final String TAG = "MtFactory";

    // 模型自带 n_ctx=61952 太大、KV 白占内存；2048 容 ~1500 token 页 + 256 生成余量。
    private static final int N_CTX = 2048;
    // 拓扑 8 核仅 2 大核；对齐 OCR THREADS=2 与已证 -t2 档。满核留后续按场景调。
    private static final int N_THREADS = 2;

    private MtEngineFactory() {
    }

    public static MtEngine create(Context ctx, MtModelKind kind) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.w(TAG, "no external files dir → passthrough");
            return new PassthroughMtEngine();
        }
        File model = new File(base, kind.fileName);
        if (!model.isFile()) {
            Log.w(TAG, kind + " model missing " + model.getAbsolutePath() + " → passthrough");
            return new PassthroughMtEngine();
        }
        try {
            long t = System.nanoTime();
            LlamaMtEngine engine = new LlamaMtEngine(model.getAbsolutePath(), N_CTX, N_THREADS);
            Log.i(TAG, kind + " ready loadMs=" + ((System.nanoTime() - t) / 1_000_000L)
                    + " nCtx=" + N_CTX + " nThreads=" + N_THREADS);
            return engine;
        } catch (Throwable err) {
            Log.e(TAG, kind + " load failed → passthrough: " + err.getClass().getSimpleName()
                    + ": " + err.getMessage());
            return new PassthroughMtEngine();
        }
    }
}
