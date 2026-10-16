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

    /** 原位贴图分组键（与 OverlayManager 共享，页面变化/软过期按此批量移除，§5.2-6）。 */
    private static final String GROUP_MT = OverlayManager.GROUP_MT;
    /** 逐块贴图的间隔，形成"依次贴上"观感（§5.2-2）。仅在工作线程 sleep，不占主线程。 */
    private static final long PLACE_INTERVAL_MS = 40;
    /**
     * 原位贴图软过期兜底（§5.2-5）：本切片取保守 30s，避免测试期译文瞬隐；
     * 主失效仍以 M3-C 的"页面变化自动清除"为准，此为无事件页面的安全网。
     */
    private static final long INPLACE_TTL_MS = 30_000L;

    /** 采样失败（框越界/空）时的兜底底：深灰，配 contrastText 出白字，不崩不透明缺失。 */
    private static final int DEFAULT_BOX_BG = 0xFF202020;

    /**
     * 摘除自家悬浮窗后、真正 takeScreenshot 前的沉降等待：removeViewImmediate 的 SurfaceFlinger 拆层事务是
     * 异步提交的，需给它时间落到显示管线，否则截到的那一帧仍可能残留悬浮球。2.0 屏 60Hz 一帧 ~16ms，
     * 120ms 覆盖 ~7 帧，足够拆层完成；这段时间只在截屏前发生，代价可忽略。
     */
    private static final long DETACH_SETTLE_MS = 120L;

    /** 两框聚为同簇的重叠阈值：交集面积 / 较小框面积 > 此值即视为"互相覆盖"，进同一簇做加权分配。 */
    private static final double OVERLAP_MERGE_RATIO = 0.15;

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

    /**
     * 截屏时临时摘除本 app 的全部悬浮窗（悬浮球/扇形菜单及子页/进度浮层/上一轮译文），拿到帧后立即恢复——
     * 否则自家 UI 会被拍进源图：既被 OCR 误读成文字，又遮住真正的原文。
     * 用 detach（removeViewImmediate）而非 INVISIBLE：后者不消解 surface layer，实测仍被拍进。摘除事务落到
     * SurfaceFlinger 是异步的，故在 capture() 前留 {@link #DETACH_SETTLE_MS} 沉降延时，确保那一帧已无自家窗。
     */
    private Bitmap captureExcludingSelf(ScreenCapturer capturer) throws ScreenCapturer.CaptureException {
        overlayManager.hideSelfForCapture();
        try {
            try {
                Thread.sleep(DETACH_SETTLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return capturer.capture();
        } finally {
            overlayManager.showSelfAfterCapture();
        }
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

    /**
     * 提交一次**原位贴图**采集（M3 演示入口，独立于默认整页路径以保回归基线）；
     * 复用同一 busy/协作取消：已在途则请求取消，不再静默丢弃。
     */
    public void runOnceInPlace() {
        if (!busy.compareAndSet(false, true)) {
            cancelRequested = true;
            overlayManager.showTextOverlay(RESULT_KEY, "上一次仍在处理中，正在取消…\n再点一次开始新采集");
            return;
        }
        cancelRequested = false;
        worker.execute(() -> {
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "识别中…");
                routeOnceInPlace();
            } finally {
                busy.set(false);
            }
        });
    }

    /** 原位贴图版采集：OCR 收框+文本 → 整页路由选引擎 → 逐框译文按像素矩形原位贴。 */
    private void routeOnceInPlace() {
        ScreenCapturer capturer = service.capturer();
        Bitmap bmp = null;
        ScreenCapturer.CaptureException capErr = null;
        try {
            bmp = captureExcludingSelf(capturer);
        } catch (ScreenCapturer.CaptureException e) {
            capErr = e;
        }

        if (bmp != null) {
            final int w = bmp.getWidth();
            final int h = bmp.getHeight();
            final List<int[]> boxes = new ArrayList<>();
            final List<String> texts = new ArrayList<>();
            OcrEngine.LineSink sink = new OcrEngine.LineSink() {
                @Override
                public void onBoxes(int total) {
                    overlayManager.showTextOverlay(RESULT_KEY, "A 可截图 " + w + "×" + h
                            + " · 检测 " + total + " 框，识别中…");
                }

                @Override
                public void onLine(int index, int total, int[] box, String text) {
                    String t = text == null ? "" : text.trim();
                    if (box != null && !t.isEmpty()) {
                        boxes.add(box);
                        texts.add(t);
                    }
                }

                @Override
                public void onDone() {
                }
            };
            engine().recognizeStream(bmp, sink);
            // 采样必须在 recycle 前：趁位图还活着，逐框取页面自己的底色（众数色，稀有极值自然落选）。
            final List<Integer> bgColors = new ArrayList<>(boxes.size());
            for (int i = 0; i < boxes.size(); i++) {
                bgColors.add(sampleBoxBackground(bmp, boxes.get(i), w, h));
            }
            bmp.recycle();
            if (boxes.isEmpty()) {
                overlayManager.showTextOverlay(RESULT_KEY, "A 可截图 " + w + "×" + h + " · 未识到文本");
                return;
            }
            // 阅读顺序：y 主序、x 次序（det 已大致有序，再显式排一次稳态）
            Integer[] order = new Integer[boxes.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> {
                int[] ba = boxes.get(a);
                int[] bb = boxes.get(b);
                return ba[1] != bb[1] ? Integer.compare(ba[1], bb[1]) : Integer.compare(ba[0], bb[0]);
            });
            // 预检测矩形互相覆盖（批处理，先于翻译）：把互相重叠的框聚成簇。密集小字页 OCR 框互相覆盖，聚成多框簇；
            // runInPlace 对多框簇做段落合并——整簇占一块并集区、字号整体缩到放得下，避免逐框切横带导致的"带高<一行字"溢出压邻行。
            List<List<Integer>> clusters = clusterBoxes(boxes, order, OVERLAP_MERGE_RATIO);
            String pageText = String.join("\n", texts);
            runInPlace(mtHub.engineFor(pageText), boxes, texts, bgColors, clusters, w, h);
            return;
        }

        String treeText = firstTextNodeText();
        if (treeText != null && !treeText.isBlank()) {
            String text = "B 树有文本 · " + clip(treeText);
            Log.i(TAG, "ROUTE=B(inplace) outcome=" + capErr.outcome + " text=" + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
        } else {
            String text = "C 此页面不支持贴图 · 截图失败(" + capErr.outcome + ") 且树无可读文本";
            Log.i(TAG, "ROUTE=C(inplace) " + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
        }
    }

    /**
     * 逐簇译文原位贴图（§5.2-1/2/3 + 密集页段落合并）。每簇先逐框 translate（短框恰是轻量模型强项），
     * 空输出的框跳过（保留原文可见）。单框簇（原文行互不重叠，如小说大字）贴回原框；**多框（互相覆盖）簇**
     * = 密集小字：把这些框的译文**并成一段、整簇占一块并集矩形区**，由 {@code showBoxedText} 的 autoSize 把
     * 字号**整体缩到放得下**——单块自洽故不再互相叠加。**（旧法）按译文权重把并集切成逐框横带已废弃**：密集页
     * 会出现"带高 < 一行字"，连最小 6sp 都放不下 → 译文被裁且半行压半行（用户 2026-10-17 报告的"两块译文叠
     * 在一起"）。**落位前经 {@link #resolveDisjoint} 兜底网**：把每块对它之前已贴的译文做差取最大余块，保证
     * 任意两块译文**两两不重叠**（补聚类阈值 0.15 漏掉的亚阈值重叠与并集越界）。簇间/框间响应 cancelRequested；
     * 先清旧组再贴，逐块 40ms 形成"依次贴上"观感。
     */
    private void runInPlace(MtEngine m, List<int[]> boxes, List<String> texts,
                            List<Integer> bgColors, List<List<Integer>> clusters, int w, int h) {
        try {
            if (!m.isReady()) {
                Log.i(TAG, "MT off (engine not ready) → inplace skip");
                overlayManager.showTextOverlay(RESULT_KEY, "A 可截图 " + w + "×" + h + "（MT 未就绪）");
                return;
            }
            overlayManager.removeGroup(GROUP_MT); // 清上一轮
            long t = System.nanoTime();
            int total = 0;
            for (List<Integer> c : clusters) {
                total += c.size();
            }
            int placed = 0;
            int clustered = 0;
            int done = 0;
            int skippedCovered = 0;
            /** 已贴译文矩形（阅读序累积）：每贴一块前把新矩形对它做差，保证落位两两不重叠（覆盖不变量的兜底网）。 */
            List<int[]> committed = new ArrayList<>();
            for (List<Integer> cluster : clusters) {
                int cs = cluster.size();
                if (cs > 1) {
                    clustered++;
                }
                // 1) 先译完全簇（逐框/逐行短段译，避开长输入坍缩）；逐框响应取消。
                String[] trs = new String[cs];
                for (int j = 0; j < cs; j++) {
                    if (cancelRequested) {
                        overlayManager.showTextOverlay(RESULT_KEY,
                                "已取消（贴到 " + placed + "/" + total + "）");
                        Log.i(TAG, "INPLACE cancelled at placed=" + placed + "/" + total);
                        return;
                    }
                    int idx = cluster.get(j);
                    String tr = m.translate(texts.get(idx));
                    trs[j] = (tr == null || tr.isBlank()) ? null : tr.trim();
                    done++;
                    overlayManager.showTextOverlay(RESULT_KEY, "贴图 " + done + "/" + total + " …");
                }
                // 2) 收非空译文（阅读序）；全空则跳过、保留原文可见。
                List<String> parts = new ArrayList<>();
                for (String tr : trs) {
                    if (tr != null) {
                        parts.add(tr);
                    }
                }
                if (parts.isEmpty()) {
                    continue;
                }
                // 3) 落位：
                //    - 单框簇（原文行互不重叠，如小说大字）→ 贴回原框，沿用原行为（回归基线）。
                //    - 多框簇（框互相覆盖 = 密集小字）→ 段落合并：整簇占一块并集区、所有译文并成一段、
                //      字号由 autoSize 整体缩到放得下。**不再逐框切横带**——横带会出现"带高<一行字"，
                //      连 6sp 都放不下 → 译文被裁且半行压半行（即"两块译文叠在一起"，用户 2026-10-17 裁定的修复方向）。
                int anchorIdx = cluster.get(0);
                int[] rect = (cs == 1) ? boxes.get(anchorIdx) : clusterUnion(boxes, cluster);
                String joined = (cs == 1) ? parts.get(0) : String.join("\n", parts);
                // 兜底网：把该矩形对已贴译文做差，取最大余块，保证簇间落位两两不重叠。全被占则跳过、留原文可见。
                rect = resolveDisjoint(rect, committed);
                if (rect == null) {
                    skippedCovered++;
                    continue;
                }
                int bg = bgColors.get(anchorIdx);
                overlayManager.showBoxedText(GROUP_MT, "mt_" + anchorIdx,
                        rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1],
                        joined, bg, contrastText(bg));
                overlayManager.scheduleGroupExpiry(GROUP_MT, INPLACE_TTL_MS);
                committed.add(rect);
                placed += parts.size();
                try {
                    Thread.sleep(PLACE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            overlayManager.removeOverlay(RESULT_KEY); // 撤临时进度条
            long ms = (System.nanoTime() - t) / 1_000_000L;
            Log.i(TAG, "INPLACE done placed=" + placed + "/" + total + " clusters=" + clustered
                    + " coveredSkip=" + skippedCovered + " ms=" + ms
                    + " srcChars=" + String.join("", texts).length());
        } catch (Throwable err) {
            Log.e(TAG, "INPLACE failed (OCR overlay untouched): " + err.getClass().getSimpleName()
                    + ": " + err.getMessage());
        }
    }

    private void routeOnce() {
        ScreenCapturer capturer = service.capturer();
        Bitmap bmp = null;
        ScreenCapturer.CaptureException capErr = null;
        try {
            bmp = captureExcludingSelf(capturer);
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

    /**
     * 把 OCR 行按**标点**切成尽量贴合句读的小段（用户 2026-10-17 指示"根据标点符号短句"）：
     * 先按句末标点（。！？；!?; 换行）断句，一句一段；单句仍超 {@code maxChars} 再按逗号级标点
     * （，、：,:. 且其后为空白，护住小数/千分位/URL）聚合成 ≤maxChars 的片；无标点可断的超长片按空格/字符硬切兜底。
     * 相比旧的"固定 48 字硬聚"，段长由句读决定、普遍更短，落在 1.25-bit 模型稳定区且译文更顺。
     * 空行/空句丢弃；非空内容全部保留且顺序不变。
     */
    private static List<String> segmentLines(List<String> lines, int maxChars) {
        List<String> segs = new ArrayList<>();
        for (String ln : lines) {
            String t = ln == null ? "" : ln.trim();
            if (t.isEmpty()) {
                continue;
            }
            for (String sent : splitAfter(t, SENTENCE_BREAK)) {
                String s = sent.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.length() <= maxChars) {
                    segs.add(s);
                } else {
                    packClauses(s, maxChars, segs); // 长句：再按逗号级标点拆到 ≤maxChars
                }
            }
        }
        return segs;
    }

    /** 长句兜底：按逗号级标点切片后贪心聚到 ≤maxChars；仍无标点可断的超长单片再按空格/字符硬切。 */
    private static void packClauses(String s, int maxChars, List<String> out) {
        StringBuilder cur = new StringBuilder();
        for (String clause : splitAfter(s, CLAUSE_BREAK)) {
            String c = clause;
            while (c.length() > maxChars) {
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                int cut = splitPoint(c, maxChars);
                out.add(c.substring(0, cut).trim());
                c = (cut < c.length() && c.charAt(cut) == ' ')
                        ? c.substring(cut + 1).trim() : c.substring(cut);
            }
            if (cur.length() > 0 && cur.length() + c.length() > maxChars) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
    }

    /** 在断点字符**之后**切开（标点归入前一片）；{@code isBreak.test(当前字符, 后一字符)} 决定该处是否断。 */
    private static List<String> splitAfter(String s, java.util.function.BiPredicate<Character, Character> isBreak) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            char next = (i + 1 < n) ? s.charAt(i + 1) : '\0';
            if (isBreak.test(ch, next)) {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < n) {
            out.add(s.substring(start));
        }
        return out;
    }

    /** 句末标点（强断点）：全角句读总断；半角 {@code ! ? ;} 仅在其后为空白/行尾时断（护缩写）。 */
    private static final java.util.function.BiPredicate<Character, Character> SENTENCE_BREAK = (ch, next) ->
            ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n'
                    || ((ch == '!' || ch == '?' || ch == ';') && (next == '\0' || Character.isWhitespace(next)));

    /**
     * 逗号级标点（弱断点，仅用于拆超长句）：全角总断；半角 {@code , : .} 仅其后为空白/行尾时断
     * ——护住 {@code 3.14159}、{@code 1,000}、{@code example.com} 这类数字/URL 不被切坏。
     */
    private static final java.util.function.BiPredicate<Character, Character> CLAUSE_BREAK = (ch, next) ->
            ch == '，' || ch == '、' || ch == '：'
                    || ((ch == ',' || ch == ':' || ch == '.') && (next == '\0' || Character.isWhitespace(next)));

    /** 断点：优先取 ≤maxChars 内最后一个空格（少切单词），否则在 maxChars 处硬切。 */
    private static int splitPoint(String s, int maxChars) {
        int sp = s.lastIndexOf(' ', maxChars);
        return sp > 0 ? sp : maxChars;
    }

    /**
     * 从截屏位图里采一本框的**页面底色**：把框内像素按每通道 4bit 量化分桶（16³=4096 桶），
     * 取像素最多的众数桶（页面底色占面积主体，字/描边/高光等稀有"极值"色自然落选），返回该桶均值，
     * 强制全不透明（盖住原文）。框越界/空则回退 {@link #DEFAULT_BOX_BG}。纯读像素，不写状态。
     */
    private static int sampleBoxBackground(Bitmap bmp, int[] box, int pw, int ph) {
        int x0 = Math.max(0, Math.min(box[0], pw - 1));
        int y0 = Math.max(0, Math.min(box[1], ph - 1));
        int x1 = Math.max(x0 + 1, Math.min(box[2], pw));
        int y1 = Math.max(y0 + 1, Math.min(box[3], ph));
        int bw = x1 - x0;
        int bh = y1 - y0;
        if (bw <= 0 || bh <= 0 || bw * bh <= 0) {
            return DEFAULT_BOX_BG;
        }
        int[] px;
        try {
            px = new int[bw * bh];
            bmp.getPixels(px, 0, bw, x0, y0, bw, bh);
        } catch (RuntimeException e) {
            Log.w(TAG, "sampleBoxBackground getPixels failed: " + e.getMessage());
            return DEFAULT_BOX_BG;
        }
        int[] cnt = new int[4096];
        int[] sumR = new int[4096];
        int[] sumG = new int[4096];
        int[] sumB = new int[4096];
        int bestKey = -1;
        int bestCnt = 0;
        for (int p : px) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            int c = ++cnt[key];
            sumR[key] += r;
            sumG[key] += g;
            sumB[key] += b;
            if (c > bestCnt) {
                bestCnt = c;
                bestKey = key;
            }
        }
        if (bestKey < 0 || bestCnt <= 0) {
            return DEFAULT_BOX_BG;
        }
        int ar = sumR[bestKey] / bestCnt;
        int ag = sumG[bestKey] / bestCnt;
        int ab = sumB[bestKey] / bestCnt;
        return 0xFF000000 | (ar << 16) | (ag << 8) | ab;
    }

    /** 依底色亮度定前景字色：亮底（Rec.601 亮度≥140）用近黑，暗底用白，保证译文可读。 */
    private static int contrastText(int bg) {
        int r = (bg >> 16) & 0xFF;
        int g = (bg >> 8) & 0xFF;
        int b = bg & 0xFF;
        int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        return lum >= 140 ? 0xFF101010 : 0xFFFFFFFF;
    }

    /** 两像素矩形重叠程度 = 交集面积 / 较小框面积（[0,1]）；不相交为 0。用 long 防大框面积溢出。 */
    private static double overlapRatio(int[] a, int[] b) {
        int iw = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        int ih = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (iw <= 0 || ih <= 0) {
            return 0.0;
        }
        long inter = (long) iw * ih;
        long areaA = (long) (a[2] - a[0]) * (a[3] - a[1]);
        long areaB = (long) (b[2] - b[0]) * (b[3] - b[1]);
        long minArea = Math.min(areaA, areaB);
        return minArea <= 0 ? 0.0 : (double) inter / minArea;
    }

    /**
     * 把互相覆盖的框聚成簇（批处理，先于翻译）：并查集，重叠比 &gt; thr 的两框合并。返回按**原文阅读顺序**
     * 排列的簇列表，每簇是**框原始下标**列表（亦按阅读序）。单框簇=不重叠的普通框，多框簇=会互相遮盖的一组。
     */
    private static List<List<Integer>> clusterBoxes(List<int[]> boxes, Integer[] order, double thr) {
        int n = boxes.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                if (overlapRatio(boxes.get(a), boxes.get(b)) > thr) {
                    ufUnion(parent, a, b);
                }
            }
        }
        // 按 order（阅读序）逐个归位到其根簇：LinkedHashMap 保序 → 簇序与簇内成员序皆为阅读序。
        java.util.LinkedHashMap<Integer, List<Integer>> byRoot = new java.util.LinkedHashMap<>();
        for (int idx : order) {
            byRoot.computeIfAbsent(ufFind(parent, idx), r -> new ArrayList<>()).add(idx);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static int ufFind(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void ufUnion(int[] p, int a, int b) {
        int ra = ufFind(p, a);
        int rb = ufFind(p, b);
        if (ra != rb) {
            p[ra] = rb;
        }
    }

    /** 簇内所有框的并集外接矩形 [x0,y0,x1,y1]。 */
    private static int[] clusterUnion(List<int[]> boxes, List<Integer> cluster) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int idx : cluster) {
            int[] b = boxes.get(idx);
            x0 = Math.min(x0, b[0]);
            y0 = Math.min(y0, b[1]);
            x1 = Math.max(x1, b[2]);
            y1 = Math.max(y1, b[3]);
        }
        return new int[]{x0, y0, x1, y1};
    }

    /**
     * 覆盖不变量的兜底网：把待贴矩形 {@code r} 对**已贴译文** {@code committed} 逐一做差，取面积最大的余块返回，
     * 保证它与所有已贴矩形不重叠；若被完全占满则返回 {@code null}（调用方跳过、保留原文可见）。
     * 按调用方的阅读序贪心执行——先贴者占位、后贴者让位。聚类阈值(0.15)漏掉的亚阈值重叠、以及并集区越界
     * 压到别簇时，这一步都能把重叠消成不重叠（压力测试 /tmp/OverlapStress 复现并验证）。
     */
    private static int[] resolveDisjoint(int[] r, List<int[]> committed) {
        List<int[]> pieces = new ArrayList<>();
        pieces.add(r);
        for (int[] c : committed) {
            List<int[]> next = new ArrayList<>();
            for (int[] p : pieces) {
                next.addAll(subtractRect(p, c));
            }
            pieces = next;
        }
        int[] best = null;
        long bestArea = 0;
        for (int[] p : pieces) {
            if (p[2] <= p[0] || p[3] <= p[1]) {
                continue; // 退化零面积片，丢弃
            }
            long area = (long) (p[2] - p[0]) * (p[3] - p[1]);
            if (area > bestArea) {
                bestArea = area;
                best = p;
            }
        }
        return best;
    }

    /** 矩形差 {@code p \ c}：不相交→[p]；否则返回 p 去掉交集后至多 4 条互不重叠的边带（上/下/左/右）。 */
    private static List<int[]> subtractRect(int[] p, int[] c) {
        List<int[]> out = new ArrayList<>();
        int ix0 = Math.max(p[0], c[0]), iy0 = Math.max(p[1], c[1]);
        int ix1 = Math.min(p[2], c[2]), iy1 = Math.min(p[3], c[3]);
        if (ix0 >= ix1 || iy0 >= iy1) {
            out.add(p); // 不相交，原样保留
            return out;
        }
        if (p[1] < iy0) {
            out.add(new int[]{p[0], p[1], p[2], iy0}); // 上
        }
        if (iy1 < p[3]) {
            out.add(new int[]{p[0], iy1, p[2], p[3]}); // 下
        }
        if (p[0] < ix0) {
            out.add(new int[]{p[0], iy0, ix0, iy1}); // 左（限中间带）
        }
        if (ix1 < p[2]) {
            out.add(new int[]{ix1, iy0, p[2], iy1}); // 右（限中间带）
        }
        return out;
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
