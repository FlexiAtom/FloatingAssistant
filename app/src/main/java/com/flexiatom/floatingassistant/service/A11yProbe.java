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

    public static void run(AccessibilityService svc) {
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
