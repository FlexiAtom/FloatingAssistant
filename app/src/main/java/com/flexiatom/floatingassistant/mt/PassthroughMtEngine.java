package com.flexiatom.floatingassistant.mt;

import com.flexiatom.floatingassistant.core.MtEngine;

/**
 * MT 降级实现：不翻译，永远返回 {@code null}（等价 OCR 侧 {@link com.flexiatom.floatingassistant.core.StubOcrEngine}）。
 * 覆盖层保留 OCR 原文，链路仍贯通、绝不崩。
 */
public final class PassthroughMtEngine implements MtEngine {

    @Override
    public String translate(String text, String tgtCode) {
        return null;
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
