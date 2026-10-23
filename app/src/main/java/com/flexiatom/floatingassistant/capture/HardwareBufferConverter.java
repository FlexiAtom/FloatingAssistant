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

package com.flexiatom.floatingassistant.capture;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;

import java.util.Objects;

/**
 * 把 takeScreenshot 返回的 HardwareBuffer 转成可用的软件 ARGB_8888 位图
 * （提案 §4.3 / §3.1e 逐字采纳的写法）。HardwareBuffer 由调用方 try-with-resources 负责 close。
 */
public final class HardwareBufferConverter {

    private HardwareBufferConverter() {
    }

    /**
     * @param buffer takeScreenshot 结果里的硬件缓冲，调用方保证随后 close
     * @return 新建的软件位图，调用方负责 recycle
     */
    public static Bitmap toSoftwareBitmap(HardwareBuffer buffer,
                                          android.graphics.ColorSpace colorSpace) {
        Objects.requireNonNull(buffer, "buffer");
        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
        if (hw == null) {
            throw new IllegalStateException("wrapHardwareBuffer returned null");
        }
        try {
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, true);
            if (sw == null) {
                throw new IllegalStateException("copy(ARGB_8888) returned null");
            }
            return sw;
        } finally {
            hw.recycle();
        }
    }
}
