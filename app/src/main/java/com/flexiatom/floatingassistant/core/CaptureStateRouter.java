package com.flexiatom.floatingassistant.core;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.flexiatom.floatingassistant.capture.ScreenCapturer;
import com.flexiatom.floatingassistant.overlay.OverlayManager;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 截图 → 三态判定 → 展示型覆盖层（提案 §4.3 A/B/C 路由）。
 *
 * <ul>
 *   <li>A 可截图：{@link ScreenCapturer#capture()} 成功且非黑屏 → 走 OCR（当前为桩）。</li>
 *   <li>B 不可截图但树有文本：截图失败，取当前窗口节点树首个文本。</li>
 *   <li>C 两者皆无：截图失败且树无文本 → 明确提示"此页面不支持"，不做绕行。</li>
 * </ul>
 */
public final class CaptureStateRouter {

    private static final String TAG = "CaptureRouter";
    private static final String RESULT_KEY = "capture_result";
    private static final int TREE_NODE_BUDGET = 800;

    private final SphereAccessibilityService service;
    private final OverlayManager overlayManager;
    private final OcrEngine ocrEngine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public CaptureStateRouter(SphereAccessibilityService service, OverlayManager overlayManager,
                              OcrEngine ocrEngine) {
        this.service = service;
        this.overlayManager = overlayManager;
        this.ocrEngine = ocrEngine;
    }

    /** 提交一次采集-判定-展示；已有进行中的运行则忽略（配合截图节流，避免叠加）。 */
    public void runOnce() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "识别中…");
                routeOnce();
            } finally {
                busy.set(false);
            }
        });
    }

    private void routeOnce() {
        ScreenCapturer capturer = service.capturer();
        Bitmap bmp = null;
        ScreenCapturer.CaptureException capErr = null;
        try {
            bmp = capturer.capture();
        } catch (ScreenCapturer.CaptureException e) {
            capErr = e;
        }

        if (bmp != null) {
            OcrEngine.Result r = ocrEngine.recognize(bmp);
            int w = bmp.getWidth(), h = bmp.getHeight();
            bmp.recycle();
            String text = "A 可截图 " + w + "×" + h + " · " + r.note + " · 文本行=" + r.lines.size();
            Log.i(TAG, "ROUTE=A " + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
            return;
        }

        String treeText = firstTextNodeText();
        if (treeText != null && !treeText.isBlank()) {
            String text = "B 树有文本 · " + clip(treeText);
            Log.i(TAG, "ROUTE=B outcome=" + capErr.outcome + " text=" + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
        } else {
            String text = "C 此页面不支持 · 截图失败(" + capErr.outcome + ") 且树无可读文本";
            Log.i(TAG, "ROUTE=C " + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
        }
    }

    /** 深度优先遍历活动窗口节点树，返回首个非空文本；受限节点预算防卡。 */
    private String firstTextNodeText() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        int visited = 0;
        try {
            while (!stack.isEmpty() && visited++ < TREE_NODE_BUDGET) {
                AccessibilityNodeInfo node = stack.pop();
                if (node == null) {
                    continue;
                }
                CharSequence t = node.getText();
                if (t != null && t.length() > 0) {
                    return t.toString();
                }
                CharSequence d = node.getContentDescription();
                if (d != null && d.length() > 0) {
                    return d.toString();
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        } finally {
            root.recycle();
        }
        return null;
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private static String clip(String s) {
        s = s.replace("\n", " ").trim();
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
