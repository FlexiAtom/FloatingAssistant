package com.flexiatom.floatingassistant.core;

import android.graphics.Bitmap;

import java.util.Collections;

/** OCR 桩实现：仅证明 A 态链路贯通，不产出文本。P1 后端接入后替换。 */
public final class StubOcrEngine implements OcrEngine {

    @Override
    public Result recognize(Bitmap src) {
        return new Result("OCR 引擎待接入(P1)", Collections.emptyList());
    }
}
