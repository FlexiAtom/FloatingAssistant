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

/**
 * 端侧翻译引擎接口（提案 §4.2 的 {@code lib:mt} 槽）。
 *
 * <p>与 {@link OcrEngine} 平行：OCR 出原文，MT 把整屏原文译成目标语言。
 * 失败/未就绪一律返回 {@code null}，由调用方保留 OCR 原文，绝不抛穿到覆盖层。
 */
public interface MtEngine {

    /**
     * 整屏文本翻译；未就绪、空输入或引擎异常返回 {@code null}（降级到不覆盖原文）。
     *
     * @param text      OCR 出的原文
     * @param tgtCode   目标语言缩写（{@link LangCatalog} 的 code，如 {@code ja}）；
     *                  不在表内时实现方按默认目标语兜底，**不抛**。源语言不传：官方句式没有源语言槽。
     */
    String translate(String text, String tgtCode);

    /** 引擎是否已加载可用；降级实现返回 false。 */
    default boolean isReady() {
        return false;
    }

    /** 释放本地资源（如 llama model/ctx）；降级实现无操作。 */
    default void close() {
    }
}
