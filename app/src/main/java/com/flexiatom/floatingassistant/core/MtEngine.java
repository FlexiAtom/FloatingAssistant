package com.flexiatom.floatingassistant.core;

/**
 * 端侧翻译引擎接口（提案 §4.2 的 {@code lib:mt} 槽）。
 *
 * <p>与 {@link OcrEngine} 平行：OCR 出原文，MT 把整屏原文译成目标语言。
 * 失败/未就绪一律返回 {@code null}，由调用方保留 OCR 原文，绝不抛穿到覆盖层。
 */
public interface MtEngine {

    /** 整屏文本翻译；未就绪、空输入或引擎异常返回 {@code null}（降级到不覆盖原文）。 */
    String translate(String text);

    /** 引擎是否已加载可用；降级实现返回 false。 */
    default boolean isReady() {
        return false;
    }

    /** 释放本地资源（如 llama model/ctx）；降级实现无操作。 */
    default void close() {
    }
}
