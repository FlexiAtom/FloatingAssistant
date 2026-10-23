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

/** OCR 桩实现：仅证明 A 态链路贯通，不产出文本。P1 后端接入后替换。 */
public final class StubOcrEngine implements OcrEngine {

    @Override
    public Result recognize(Bitmap src) {
        return new Result("OCR 引擎待接入(P1)", Collections.emptyList());
    }
}
