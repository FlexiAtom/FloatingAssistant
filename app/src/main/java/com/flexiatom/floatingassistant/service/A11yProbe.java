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

package com.flexiatom.floatingassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 无障碍**自证**探针（{@code ui-blind-operation} F1c）：把本服务自己看得见的窗与节点树打进 logcat，
 * 用来回答「悬浮 UI 在无障碍树里到底存不存在」——这问题以前只能靠人开 TalkBack 才答得出。
 *
 * <p>只读：不改视图、不注入事件。隐私面上只走一步——**别人的窗只报窗级事实**（类型/边界/root 有没有），
 * 节点级明细只展开自己包的那几扇，免得把用户正文念进日志。
 */
public final class A11yProbe {

    private static final String TAG = "A11YPROBE";

    /** 节点级只走到这个深度：我们的面板本身就浅，深了多半是别人家的树。 */
    private static final int MAX_DEPTH = 10;

    /** 一次探针的节点预算，防极端大 tree 把日志刷爆。 */
    private static final int MAX_NODES = 240;

    private A11yProbe() {
    }

    public static void run(AccessibilityService svc, boolean tapBall, int tapChildIndex) {
        list(svc);
        if (tapBall) {
            tapBallNode(svc);
        }
        if (tapChildIndex >= 0) {
            tapChildNode(svc, tapChildIndex);
        }
    }

    /**
     * 机测「读屏双击到底能不能**操作**」而不只是"能不能听见"：给自己那扇覆盖窗的根节点发
     * {@code ACTION_CLICK}，再把同一扇窗的根节点重取一次。我们的 delegate 是**现算**名字的
     * （{@code FanMenuView#installAccessibility}），所以名字翻没翻＝这条动作路通没通的判据。
     */
    private static void tapBallNode(AccessibilityService svc) {
        String self = svc.getPackageName();
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                continue;
            }
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !self.equals(String.valueOf(root.getPackageName()))
                    || !root.isClickable()) {
                continue;
            }
            String before = String.valueOf(root.getContentDescription());
            boolean delivered = root.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo after = w.getRoot();
            Log.i(TAG, "tap id=" + w.getId() + " before=[" + before + "]"
                    + " performAction=" + delivered
                    + " after=[" + (after == null ? "NULL" : after.getContentDescription()) + "]");
            return;
        }
        Log.i(TAG, "tap: 没有可点的覆盖窗根节点（球没在窗上？）");
    }

    /**
     * 机测「扇形那一格能不能被**触发**」：对自己覆盖窗根节点的第 {@code index} 个子节点（虚拟节点）发
     * {@code ACTION_CLICK}，再把重取到的子节点名单打一行。
     * <p>为什么只能这样量：这些格子是 {@code ExploreByTouchHelper} 造的虚拟节点，画面与树之间没有真实
     * View 可点，而触摸注入是本项的禁区（{@code ui-blind-operation} 禁忌）——不经过无障碍树，机侧就没有
     * 任何一条路能碰到它们。"名字在树里" 与 "点得动、点完树会跟着换页" 是两件事，这条只答后者。
     */
    private static void tapChildNode(AccessibilityService svc, int index) {
        String self = svc.getPackageName();
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                continue;
            }
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !self.equals(String.valueOf(root.getPackageName()))) {
                continue;
            }
            if (index >= root.getChildCount()) {
                Log.i(TAG, "child#" + index + " 越界 kids=" + root.getChildCount());
                return;
            }
            AccessibilityNodeInfo c = root.getChild(index);
            if (c == null) {
                Log.i(TAG, "child#" + index + "=NULL（虚拟节点解不出来＝provider 没挂上）");
                return;
            }
            String before = String.valueOf(c.getContentDescription());
            boolean delivered = c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo after = w.getRoot();
            StringBuilder names = new StringBuilder();
            if (after != null) {
                for (int i = 0; i < after.getChildCount(); i++) {
                    AccessibilityNodeInfo k = after.getChild(i);
                    names.append(i).append('=').append(k == null ? "?" : clip(k.getContentDescription())).append(' ');
                }
            }
            Log.i(TAG, "child#" + index + " cd=[" + before + "] click=" + c.isClickable()
                    + " b=" + bounds(c, new Rect())
                    + " performAction=" + delivered
                    + " after kids=" + (after == null ? "NULL" : after.getChildCount()) + " " + names);
            return;
        }
        Log.i(TAG, "child: 没有自己的覆盖窗（球没上窗？）");
    }

    private static void list(AccessibilityService svc) {
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        Rect box = new Rect();
        String self = svc.getPackageName();
        AccessibilityNodeInfo active = svc.getRootInActiveWindow();
        Log.i(TAG, "windows=" + windows.size() + " self=" + self
                + " rootInActive=" + (active == null ? "NULL" : nodeBrief(active)));
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            String pkg = root == null ? "?" : String.valueOf(root.getPackageName());
            Log.i(TAG, "win id=" + w.getId() + " type=" + typeName(w.getType())
                    + " layer=" + w.getLayer() + " active=" + w.isActive()
                    + " focused=" + w.isFocused() + " bounds=" + bounds(w, box)
                    + " pkg=" + pkg + " root=" + (root == null ? "NULL" : "ok"));
            // 窗级事实全打，节点级只打自己包的：这是这条探针唯一的隐私边界
            if (root != null && self.equals(pkg)) {
                walk(root, 1, new int[]{MAX_NODES});
            }
        }
    }

    private static void walk(AccessibilityNodeInfo n, int depth, int[] budget) {
        if (budget[0]-- <= 0 || depth > MAX_DEPTH) {
            return;
        }
        Log.i(TAG, "  ".repeat(depth) + nodeBrief(n));
        int children = n.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                walk(c, depth + 1, budget);
            }
        }
    }

    /** 一行够判读：有没有可读名字、能不能被焦点走到、系统认不认为它可见。 */
    private static String nodeBrief(AccessibilityNodeInfo n) {
        String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
        int dot = cls.lastIndexOf('.');
        return "node " + (dot >= 0 ? cls.substring(dot + 1) : cls)
                + " cd=[" + clip(n.getContentDescription()) + "]"
                + " text=[" + clip(n.getText()) + "]"
                + " click=" + n.isClickable()
                + " foc=" + n.isFocusable()
                + " vis=" + n.isVisibleToUser()
                + " kids=" + n.getChildCount()
                + " b=" + bounds(n, new Rect());
    }

    /** 两个 API 都是"填进传进去的 Rect"，没有返回值，所以统一走这里。 */
    private static String bounds(AccessibilityWindowInfo w, Rect out) {
        w.getBoundsInScreen(out);
        return out.toString();
    }

    private static String bounds(AccessibilityNodeInfo n, Rect out) {
        n.getBoundsInScreen(out);
        return out.toString();
    }

    private static String clip(CharSequence s) {
        if (s == null) {
            return "";
        }
        String t = s.toString();
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }

    private static String typeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "A11Y_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "SPLIT_DIVIDER";
            case AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY:
                return "MAGNIFICATION";
            default:
                return "OTHER(" + type + ")";
        }
    }
}
