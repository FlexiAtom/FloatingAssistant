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

import com.flexiatom.floatingassistant.core.OcrEngine;
import com.flexiatom.floatingassistant.core.StubOcrEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产 OCR 引擎装配：懒建 {@link PpOcrEngine}（首用加载模型，避开服务启动峰值内存）。
 *
 * <p>模型位于 {@code getExternalFilesDir(null)} 根（与 P1 测量同一约定）。缺模型/加载失败
 * → 回退 {@link StubOcrEngine}，A 态链路仍贯通、绝不崩（生产保守降级）。
 */
public final class PpOcrEngineFactory {

    private static final String TAG = "OcrFactory";

    private static final String DET = "ch_PP-OCRv4_det_infer.onnx";
    private static final String REC = "ch_PP-OCRv4_rec_infer.onnx";
    private static final String DICT = "ppocr_keys_v1.txt";
    // 拓扑 8 核、仅 2 大核：intra=2 实测优于 4（P1-b）。
    private static final int THREADS = 2;

    private PpOcrEngineFactory() {
    }

    public static OcrEngine create(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.w(TAG, "no external files dir → stub");
            return new StubOcrEngine();
        }
        File det = new File(base, DET);
        File rec = new File(base, REC);
        File dictFile = new File(base, DICT);
        if (!det.isFile() || !rec.isFile() || !dictFile.isFile()) {
            Log.w(TAG, "models missing det=" + det.isFile() + " rec=" + rec.isFile()
                    + " dict=" + dictFile.isFile() + " base=" + base + " → stub");
            return new StubOcrEngine();
        }
        try {
            long t = System.nanoTime();
            PpOcrEngine engine = new PpOcrEngine(det, rec, readLines(dictFile), THREADS);
            // 伪框过滤：丢弃竖直中心落在系统状态栏带内者（比底边更稳，时钟框常略探出名义栏底）。
            // 取真实状态栏高度，非魔数。
            engine.skipTopPx = statusBarHeightPx(ctx);
            Log.i(TAG, "PpOcrEngine ready loadMs=" + ((System.nanoTime() - t) / 1_000_000L)
                    + " skipTopPx=" + engine.skipTopPx
                    + " detLimit=" + engine.detLimitSide + " recMaxW=" + engine.recMaxW);
            return engine;
        } catch (Throwable err) {
            Log.e(TAG, "engine load failed → stub: " + err.getClass().getSimpleName()
                    + ": " + err.getMessage());
            return new StubOcrEngine();
        }
    }

    /** 系统状态栏像素高；取不到返回 0（=关过滤）。 */
    private static int statusBarHeightPx(Context ctx) {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
    }

    private static List<String> readLines(File f) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
        }
        return out;
    }
}
