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

package com.flexiatom.floatingassistant.overlay;

import android.view.inputmethod.InputConnection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 流畅模式的输入法闸门（m5-editor-asr-notes E6·⋮）。
 *
 * <p>口径照人给的 MT 原文（2026-10-22）：「切换到流畅模式后，可极大提高编辑大文本的流畅度，但会导致
 * 输入法部分功能受到限制（如复制剪切，自带菜单的功能不受影响）」——**受限的只有输入法那一侧**，
 * 我们自己的选择气泡与 {@code /} 菜单照旧。所以这一档只掐「读文本」这一路，显示与输入标志一个不动。
 *
 * <p>大文本上贵的是读、不是写：{@code getExtractedText} 与 {@code takeSnapshot} 一次把整篇搬进输入法，
 * {@code getTextBeforeCursor} 那几条被候选轮询反复调，每次都是 O(n) 子串再加一次跨进程往返。写的路
 * （{@code commitText}／{@code setComposingText}／{@code deleteSurroundingText}／{@code sendKeyEvent}）
 * 原样转发，打字手感不变。选区读空＝输入法自带的复制/剪切当场拿不到东西，那句「受限」就是这么来的。
 *
 * <p><b>为什么是动态代理而不是手写全量委托</b>（禁忌 4 的对账）：{@code InputConnection} 现有 27 个抽象
 * 方法，另有一批 {@code default}（带 {@code TextAttribute} 的那几个重载、{@code takeSnapshot}、
 * {@code requestTextBoundsInfo}…，34 与 35 各添了几条）。手写委托只要漏盖一条 {@code default}，落下的
 * 就是接口里那个「不支持」的默认体——输入法的新通路会在没人改过代码的情况下静默退化，比代理这点反射开销
 * （微秒级，被它省下的 O(n) 完全盖住）难查得多。代价照付：方法名从字符串匹配，按名字 grep 找不到调用点。
 */
final class SmoothInputConnection {

    /** 光标两侧最多报这么多字：够输入法做退格删词与候选，又不至于每轮都子串整篇。 */
    private static final int SURROUNDING_CAP = 2048;

    private SmoothInputConnection() {
    }

    /** 档读自外层（⋮ 里那一格），每次调用现问——连接因此不必随翻档重建。 */
    interface Gate {
        boolean restricted();
    }

    /** 包一层；真连接为空就照空，不造一个只会挡路的壳。 */
    static InputConnection of(InputConnection connection, Gate gate) {
        if (connection == null || gate == null) {
            return connection;
        }
        return (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(),
                new Class<?>[] { InputConnection.class },
                new Forward(connection, gate));
    }

    private record Forward(InputConnection delegate, Gate gate) implements InvocationHandler {

        private static final Object[] NO_ARGS = new Object[0];

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!gate.restricted()) {
                return forward(method, args);
            }
            switch (method.getName()) {
                case "getSelectedText", "getExtractedText", "takeSnapshot" -> {
                    return null; // 整段选区与整篇都不外送：输入法自带的复制/剪切就此拿不到东西
                }
                case "getTextBeforeCursor", "getTextAfterCursor" -> {
                    return forward(method, capLengths(args, 0));
                }
                case "getSurroundingText" -> {
                    return forward(method, capLengths(args, 0, 1));
                }
                case "performContextMenuAction" -> {
                    int id = (Integer) args[0];
                    // 复制与剪切明确回绝（回 false 比让它读到空更像「这功能没了」）；粘贴与全选照放。
                    if (id == android.R.id.copy || id == android.R.id.cut) {
                        return false;
                    }
                    return forward(method, args);
                }
                default -> {
                    return forward(method, args);
                }
            }
        }

        /** 只改长度形参，且返回新数组：{@code args} 是调用方给的，就地改会脏了它。 */
        private static Object[] capLengths(Object[] args, int... indices) {
            Object[] copy = args.clone();
            for (int i : indices) {
                if ((Integer) copy[i] > SURROUNDING_CAP) {
                    copy[i] = SURROUNDING_CAP;
                }
            }
            return copy;
        }

        private Object forward(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args == null ? NO_ARGS : args);
            } catch (InvocationTargetException e) {
                throw e.getCause(); // 递出真异常，别让调用方只看见反射层这一层壳
            }
        }
    }
}
