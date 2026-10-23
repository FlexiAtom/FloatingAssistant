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
