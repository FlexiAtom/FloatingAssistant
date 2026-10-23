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

package com.flexiatom.floatingassistant.core;

import android.graphics.Bitmap;

import java.util.Collections;
import java.util.List;

/**
 * OCR 引擎接口（提案 §4.2 的 {@code lib:ocr}/{@code OcrEngine} 槽）。
 * 当前仅有桩实现；真正的 PP-OCR + ORT/ncnn 后端由 P1 benchmark 定选型后接入，
 * 届时把本接口与其实现迁到 {@code lib:ocr} 模块。
 */
public interface OcrEngine {

    Result recognize(Bitmap src);

    /**
     * 逐框流式识别：det 先出全部框并回调 {@link LineSink#onBoxes}，随后每识别完一框回调
     * {@link LineSink#onLine}，最后 {@link LineSink#onDone}。用于把"一次阻塞到全完"
     * 改成"首字 ~0.4s 即出、逐行补全"，降感知延迟而不改模型准确率。
     * 默认实现回退到阻塞 {@link #recognize}（桩/无流式后端仍可用）。
     */
    default void recognizeStream(Bitmap src, LineSink sink) {
        recognizeStream(src, sink, true);
    }

    /**
     * 同上，但可关掉**整页坐标口径**的伪框过滤（状态栏带、矮框阈值）。
     *
     * <p>M1 框选喂给引擎的是选区裁剪：它的 y 从 0 起，若照整屏口径过滤，"状态栏带"会盖住整个小图，
     * 于是**全部真框被误杀**——真机实测表现为「选区文字少就报没有文字」（2026-10-18 用户人工复验）。
     * 区域路径传 {@code pageFilters=false}。
     */
    default void recognizeStream(Bitmap src, LineSink sink, boolean pageFilters) {
        Result r = recognize(src);
        int n = r.lines.size();
        sink.onBoxes(n);
        for (int i = 0; i < n; i++) {
            sink.onLine(i, n, null, r.lines.get(i));
        }
        sink.onDone();
    }

    /** 流式回调；实现方保证在单一线程按序调用。 */
    interface LineSink {
        void onBoxes(int total);
        /** box 为原图像素坐标 [x0,y0,x1,y1]，回退路径可能为 null。 */
        void onLine(int index, int total, int[] box, String text);
        void onDone();
    }

    /** 释放引擎持有的本地资源（如 ONNX 会话）；桩默认无操作。 */
    default void close() {
    }

    final class Result {
        /** 人读结论（桩为"待接入"说明）。 */
        public final String note;
        /** 识别出的文本行（桩为空）。 */
        public final List<String> lines;

        public Result(String note, List<String> lines) {
            this.note = note;
            this.lines = lines == null ? Collections.emptyList() : lines;
        }
    }
}
