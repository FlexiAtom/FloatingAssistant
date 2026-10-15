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
