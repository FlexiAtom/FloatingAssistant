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
