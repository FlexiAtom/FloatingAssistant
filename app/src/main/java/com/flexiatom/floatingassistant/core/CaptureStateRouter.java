package com.flexiatom.floatingassistant.core;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.flexiatom.floatingassistant.capture.ScreenCapturer;
import com.flexiatom.floatingassistant.mt.MtModelHub;
import com.flexiatom.floatingassistant.overlay.OverlayManager;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
    /**
     * 分段翻译的单段字符上限。1.25-bit 模型在整屏长输入上实测坍缩（空输出/乱码/复读），
     * 故按行边界把 OCR 文本切成 ≤此值的短段逐段翻译再拼回。48 字 ≈ 40 内容 token + 模板，
     * 落在模型稳定区内；Q4_K_M 能力更强但仍沿用同一分段口径。
     */
    private static final int SEG_MAX_CHARS = 48;

    private final SphereAccessibilityService service;
    private final OverlayManager overlayManager;
    private final Supplier<OcrEngine> engineFactory;
    /** 分场景模型中枢：整页级路由、单引擎驻留、换面即 close 旧 create 新。 */
    private final MtModelHub mtHub;
    /** 懒建：模型加载 ~0.5s，放到工作线程首用时再做，别卡服务主线程。单工作线程串行，故无竞态。 */
    private OcrEngine ocrEngine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /**
     * 协作式取消：Q4 整页翻译在基准机上实测可达 68–252s，期间 busy 一直为真，
     * 悬浮球再点会被 runOnce 直接丢弃、覆盖层停留在上一次结果（看起来像"显示旧结果"）。
     * 置此标志后，翻译在**段间边界**尽快中断并释放 busy，让下一次点击能立即开始新采集。
     * 由主线程（点击回调）写、工作线程读，故 volatile。
     */
    private volatile boolean cancelRequested;

    public CaptureStateRouter(SphereAccessibilityService service, OverlayManager overlayManager,
                              Supplier<OcrEngine> engineFactory, MtModelHub mtHub) {
        this.service = service;
        this.overlayManager = overlayManager;
        this.engineFactory = engineFactory;
        this.mtHub = mtHub;
    }

    private OcrEngine engine() {
        if (ocrEngine == null) {
            ocrEngine = engineFactory.get();
        }
        return ocrEngine;
    }

    /** 提交一次采集-判定-展示；已有进行中的运行则请求取消，不再静默丢弃。 */
    public void runOnce() {
        if (!busy.compareAndSet(false, true)) {
            // 上一次（多为 Q4 整页翻译，可达数分钟）仍在跑：置取消标志，
            // 让在途翻译在段间边界尽快中断释放 busy；覆盖层即时反馈，避免"点了没反应/看着像旧结果"。
            cancelRequested = true;
            overlayManager.showTextOverlay(RESULT_KEY, "上一次仍在处理中，正在取消…\n待其停下后再点一次即可开始新采集");
            return;
        }
        cancelRequested = false;
        worker.execute(() -> {
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "识别中…");
                routeOnce();
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * 前台 App 切走时释放常驻模型内存（提案 §5.5 修订：前台切走即卸载）。
     * 空闲 → 投递到工作线程 close 引擎并返回 true；翻译进行中 → 不打断、返回 false
     * （调用方保留状态，下次顶层变化再试；进行中的长翻译可被悬浮球取消）。
     * {@link MtModelHub#close()} 必须在本类单工作线程上跑，故经 worker 投递。
     */
    public boolean unloadIfIdle() {
        if (busy.get()) {
            return false;
        }
        worker.execute(() -> {
            if (!busy.get()) {
                mtHub.close();
                Log.i(TAG, "engine unloaded (foreground left)");
            }
        });
        return true;
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
            final int w = bmp.getWidth();
            final int h = bmp.getHeight();
            final StringBuilder acc = new StringBuilder();
            final List<String> lines = new ArrayList<>();
            final int[] boxCount = {0};
            // 逐框流式：首行 ~0.2s 即到、逐行补全（B3）；overlay 同 key 原位更新，非阻塞主线程。
            OcrEngine.LineSink sink = new OcrEngine.LineSink() {
                @Override
                public void onBoxes(int total) {
                    boxCount[0] = total;
                    overlayManager.showTextOverlay(RESULT_KEY,
                            "A 可截图 " + w + "×" + h + " · 检测 " + total + " 框，识别中…");
                }

                @Override
                public void onLine(int index, int total, int[] box, String text) {
                    if (acc.length() > 0) {
                        acc.append('\n');
                    }
                    acc.append(text.trim());
                    lines.add(text.trim());
                    overlayManager.showTextOverlay(RESULT_KEY,
                            "识别中 " + (index + 1) + "/" + total + "\n" + acc);
                }

                @Override
                public void onDone() {
                    String head = "A 可截图 " + w + "×" + h + " · " + boxCount[0] + " 框";
                    String body = acc.length() == 0 ? head + " · 未识到文本" : head + "\n" + acc;
                    overlayManager.showTextOverlay(RESULT_KEY, body);
                    Log.i(TAG, "ROUTE=A " + head + " chars=" + acc.length());
                }
            };
            engine().recognizeStream(bmp, sink);
            bmp.recycle();
            // 分段翻译：整屏一次性喂会超模型长输入能力悬崖（实测坍缩），故按行切成小段逐段翻。
            // 整页级路由：拿整页文本让中枢决定用轻量还是升 Q4，一页只用一个引擎。
            if (!lines.isEmpty()) {
                String pageText = String.join("\n", lines);
                runTranslateSegmented(mtHub.engineFor(pageText), pageText, lines, w, h);
            }
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

    /**
     * 分段 MT：把 OCR 行按 {@link #SEG_MAX_CHARS} 边界聚成小段，逐段翻译再拼双语。
     * 引擎由中枢按整页路由预先选定（一页一引擎）。全程 try/catch(Throwable)，
     * **绝不让 MT 失败波及 OCR 覆盖层**。未就绪 → 记 "MT off" 直接返回。
     * 逐段增量刷新覆盖层（显示进度 + 已译部分），避免整页等待像卡死；某段空输出则跳过、不影响其余段。
     */
    private void runTranslateSegmented(MtEngine m, String orig, List<String> lines, int w, int h) {
        try {
            if (!m.isReady()) {
                Log.i(TAG, "MT off (engine not ready)");
                return;
            }
            List<String> segs = segmentLines(lines, SEG_MAX_CHARS);
            long t = System.nanoTime();
            StringBuilder transAll = new StringBuilder();
            int okSegs = 0;
            for (int i = 0; i < segs.size(); i++) {
                if (cancelRequested) {
                    overlayManager.showTextOverlay(RESULT_KEY,
                            "已取消（译到 " + i + "/" + segs.size() + " 段）\n\n译文：\n" + transAll);
                    Log.i(TAG, "MT cancelled at " + i + "/" + segs.size() + " outChars=" + transAll.length());
                    return;
                }
                String seg = segs.get(i);
                String tr = m.translate(seg);
                if (tr != null && !tr.isBlank()) {
                    if (transAll.length() > 0) {
                        transAll.append('\n');
                    }
                    transAll.append(tr.trim());
                    okSegs++;
                }
                overlayManager.showTextOverlay(RESULT_KEY,
                        "翻译中 " + (i + 1) + "/" + segs.size() + "\n\n原文：\n" + seg
                                + "\n\n译文：\n" + transAll);
            }
            long transMs = (System.nanoTime() - t) / 1_000_000L;
            if (transAll.length() == 0) {
                Log.i(TAG, "MT empty transMs=" + transMs + " segs=" + segs.size() + " → 保留原文");
                overlayManager.showTextOverlay(RESULT_KEY,
                        "A 可截图 " + w + "×" + h + "（MT 无输出）\n" + orig);
                return;
            }
            overlayManager.showTextOverlay(RESULT_KEY, "原文：\n" + orig + "\n\n译文：\n" + transAll);
            Log.i(TAG, "MT done transMs=" + transMs + " segs=" + segs.size() + " okSegs=" + okSegs
                    + " srcChars=" + orig.length() + " outChars=" + transAll.length());
            Log.i(TAG, "译文: " + transAll);
        } catch (Throwable err) {
            Log.e(TAG, "MT failed (OCR overlay untouched): " + err.getClass().getSimpleName()
                    + ": " + err.getMessage());
        }
    }

    /** 按行边界把行聚成 ≤maxChars 的段；单行超上限自成一段（OCR 行通常很短，罕见）。 */
    private static List<String> segmentLines(List<String> lines, int maxChars) {
        List<String> segs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String ln : lines) {
            String t = ln.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (cur.length() > 0 && cur.length() + 1 + t.length() > maxChars) {
                segs.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(t);
        }
        if (cur.length() > 0) {
            segs.add(cur.toString());
        }
        return segs;
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
        if (ocrEngine != null) {
            ocrEngine.close();
        }
        mtHub.close();
    }

    private static String clip(String s) {
        s = s.replace("\n", " ").trim();
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
