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

import android.util.Log;

import com.flexiatom.floatingassistant.core.MtEngine;

import java.nio.charset.StandardCharsets;

/**
 * llama.cpp STQ 特化引擎的 JNI 封装。
 *
 * <p>加载顺序按 DT_NEEDED 依赖：{@code ggml-base → ggml-cpu → ggml → llama → mtjni}。
 * 全部用 {@code c++_static} 编，故无 {@code libc++_shared.so}，不与 ORT 撞。
 * 任一库缺失/链接失败 → {@link #ensureLibs()} 抛 {@link UnsatisfiedLinkError}，
 * 由 {@link MtEngineFactory} 捕获降级。
 */
public final class LlamaMtEngine implements MtEngine {

    private static final String TAG = "LlamaMt";

    private static volatile boolean libsReady = false;

    /** 幂等加载原生库；失败抛 UnsatisfiedLinkError。仅在工作线程首用时调。 */
    public static synchronized void ensureLibs() {
        if (libsReady) {
            return;
        }
        System.loadLibrary("ggml-base");
        System.loadLibrary("ggml-cpu");
        System.loadLibrary("ggml");
        System.loadLibrary("llama");
        System.loadLibrary("mtjni");
        libsReady = true;
        Log.i(TAG, "native libs loaded");
    }

    private native long nativeInit(String modelPath, int nCtx, int nThreads);

    /** {@code user} 已是完整 user 消息（含指令），原生层不再拼提示词。 */
    private native byte[] nativeTranslate(long handle, String user);
    private native void nativeRelease(long handle);

    private long handle; // 0 = 未就绪

    /**
     * @param modelPath 设备可读绝对路径（getExternalFilesDir 下的 .gguf）
     * @throws IllegalStateException 原生加载失败（句柄 0）
     */
    public LlamaMtEngine(String modelPath, int nCtx, int nThreads) {
        ensureLibs();
        this.handle = nativeInit(modelPath, nCtx, nThreads);
        if (this.handle == 0L) {
            throw new IllegalStateException("nativeInit failed for " + modelPath);
        }
    }

    @Override
    public boolean isReady() {
        return handle != 0L;
    }

    @Override
    public String translate(String text, String tgtCode) {
        if (handle == 0L || text == null || text.isBlank()) {
            return null;
        }
        byte[] out = nativeTranslate(handle, MtPrompt.userMessage(tgtCode, text));
        if (out == null || out.length == 0) {
            return null;
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (handle != 0L) {
            nativeRelease(handle);
            handle = 0L;
        }
    }
}
