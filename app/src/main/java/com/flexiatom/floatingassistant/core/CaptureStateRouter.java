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
import android.os.SystemClock;
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

    /** 框选重试趟（第二趟）补真实页面上下文时的最小边（px）：小选区直接喂 det 会出 0 框。 */
    private static final int MIN_CTX_PX = 320;
    /** 抠图画布的灰边宽（px）：纯色、不含任何页面邻居，只给 det 留呼吸空间。 */
    private static final int CUT_PAD_PX = 24;
    /** 抠图放大目标字高（px），与 rec 输入高 REC_H 同值：小选区抬到这一高度 det 才肯出框。 */
    private static final int CUT_TARGET_H = 48;
    /** 抠图放大上限：字高再小也不无限放大（避免 1~2 px 噪点被撑成一屏）。 */
    private static final float CUT_MAX_SCALE = 4.0f;
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
    /** 截图组参数（严格/宽松降级）；每次失败现读，改完下一次采集即生效。 */
    private final TuningStore tuning;
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
        this.tuning = new TuningStore(service);
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
            // 选区窗由服务侧在派发本次运行**之前**就已移除（框选松手即收），故此处无需特判：
            // hideSelfForCapture 摘的是当时仍挂在 views 里的窗，那时已无遮罩层。
            Bitmap bmp = capturer.capture();
            RuntimeStats.of(service).capture(ScreenCapturer.Outcome.OK);
            return bmp;
        } catch (ScreenCapturer.CaptureException e) {
            // 失败同样入统计：黑屏率/超时率是设备自调页与 M0/M1 收口的输入，只记成功等于没记。
            RuntimeStats.of(service).capture(e.outcome);
            throw e;
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
            long t0 = SystemClock.elapsedRealtime();
            boolean aState = false;
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "识别中…");
                aState = routeOnce();
            } finally {
                RuntimeStats.of(service).run(RuntimeStats.RUN_PAGE,
                        SystemClock.elapsedRealtime() - t0, aState);
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
            long t0 = SystemClock.elapsedRealtime();
            boolean aState = false;
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "识别中…");
                aState = routeOnceInPlace();
            } finally {
                RuntimeStats.of(service).run(RuntimeStats.RUN_INPLACE,
                        SystemClock.elapsedRealtime() - t0, aState);
                busy.set(false);
            }
        });
    }

    /**
     * 框选闭环（§10 M1）：对**已选定的屏幕像素矩形**做一次 截图 → 裁剪 → OCR，把文本交回
     * {@code onText}（在主线程回调，可直接落面板）。
     *
     * <p>复用同一 {@code busy}/协作取消与同一常驻 OCR 引擎：另起一个引擎虽省一次 det，但会顶破
     * §5.5「单引擎驻留」的内存红线，故不这么做。
     * <p>坐标口径：{@code takeScreenshot} 出图与 overlay 同为物理像素（M3 原位贴图实测不错位），
     * 故选区矩形可直接当裁剪盒，不做密度换算。
     */
    public void runOnceRegion(int[] rect, java.util.function.Consumer<String> onText) {
        if (!busy.compareAndSet(false, true)) {
            cancelRequested = true;
            overlayManager.showTextOverlay(RESULT_KEY, "上一次仍在处理中，正在取消…\n再点一次开始新采集");
            return;
        }
        cancelRequested = false;
        worker.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            boolean ok = false;
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "框选识别中…");
                ok = routeOnceRegion(rect, onText);
            } finally {
                // 框选的结果落**面板**，不像整页/原位那样把文本留在状态条上，故状态条在此必须撤掉；
                // 否则「框选识别中…」会一直悬在屏幕顶部（真机实测 2026-10-18 残留）。
                overlayManager.removeOverlay(RESULT_KEY);
                RuntimeStats.of(service).run(RuntimeStats.RUN_REGION,
                        SystemClock.elapsedRealtime() - t0, ok);
                busy.set(false);
            }
        });
    }

    /**
     * 框选 ▸ 部分截图（{@code partial-screenshot}）：对选区矩形裁一张**原分辨率** PNG 存进系统相册，
     * 结果文本（成功含文件名）在主线程回调给浮层回执。
     *
     * <p>刻意与 OCR 路径分开：OCR 那趟为救小字召回会把图抬到 48 px 字高，那是**送检图**的加工，
     * 产物绝不能是它——存出去的必须是屏幕所见的那块像素（2026-10-18 全局坑：对 UI 不能去色或缩放）。
     * <p>复用同一 {@code busy}/单 worker：与 OCR 抢同一个截屏窗口，不允许并行两次采集。
     */
    public void runOnceRegionShot(int[] rect, java.util.function.Consumer<String> onDone) {
        if (!busy.compareAndSet(false, true)) {
            cancelRequested = true;
            overlayManager.showTextOverlay(RESULT_KEY, "上一次仍在处理中，正在取消…\n再点一次开始新采集");
            return;
        }
        cancelRequested = false;
        worker.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            boolean ok = false;
            try {
                overlayManager.showTextOverlay(RESULT_KEY, "选区保存中…");
                ok = routeOnceRegionShot(rect, onDone);
            } finally {
                overlayManager.removeOverlay(RESULT_KEY);
                RuntimeStats.of(service).run(RuntimeStats.RUN_SHOT,
                        SystemClock.elapsedRealtime() - t0, ok);
                busy.set(false);
            }
        });
    }

    /** 部分截图一趟：截屏（排除自家浮层）→ 原分辨率裁剪 → MediaStore 落 PNG。失败给类别文本。 */
    private boolean routeOnceRegionShot(int[] rect, java.util.function.Consumer<String> onDone) {
        Bitmap bmp;
        try {
            bmp = captureExcludingSelf(service.capturer());
        } catch (ScreenCapturer.CaptureException e) {
            Log.i(TAG, "SHOT fail capture=" + e.outcome + " rect=" + rect[0] + "," + rect[1]
                    + "," + rect[2] + "," + rect[3] + " (" + e.getMessage() + ")");
            deliver(onDone, "此页面不支持截图 · 截图失败(" + e.outcome + ")");
            return false;
        }
        int sw = bmp.getWidth();
        int sh = bmp.getHeight();
        int x0 = clamp(Math.min(rect[0], rect[2]), 0, sw);
        int y0 = clamp(Math.min(rect[1], rect[3]), 0, sh);
        int x1 = clamp(Math.max(rect[0], rect[2]), 0, sw);
        int y1 = clamp(Math.max(rect[1], rect[3]), 0, sh);
        int w = x1 - x0;
        int h = y1 - y0;
        if (w < 8 || h < 8) {
            bmp.recycle();
            Log.i(TAG, "SHOT fail tooSmall=" + w + "x" + h);
            deliver(onDone, "选区太小（" + w + "×" + h + " px），未保存");
            return false;
        }
        // createBitmap(src, 全幅) 会**原样返回 src**，所以两处回收都得防重复：满屏框选时只有一张图可回收。
        Bitmap cut = Bitmap.createBitmap(bmp, x0, y0, w, h);
        try {
            String name = "floating-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(new java.util.Date()) + ".png";
            android.net.Uri uri = saveShotToGallery(cut, name);
            if (uri == null) {
                Log.i(TAG, "SHOT fail save rect=" + x0 + "," + y0 + " " + w + "x" + h);
                deliver(onDone, "保存失败 · 相册写入被拒");
                return false;
            }
            Log.i(TAG, "SHOT done rect=" + x0 + "," + y0 + " " + w + "x" + h + " file=" + name);
            deliver(onDone, "已存到相册 · Pictures/悬浮助手/" + name);
            return true;
        } finally {
            if (cut != bmp) {
                bmp.recycle();
            }
            cut.recycle();
        }
    }

    /**
     * 分区存储写相册（minSdk 30 ⇒ 只有这一条路，不申请存储权限）：{@code IS_PENDING} 建 → 写 PNG → 收尾。
     * 任何一步失败都删掉半成品，相册里不留空文件。文件名用 ASCII（部分 ROM 的媒体扫描对中文名不稳）。
     */
    private android.net.Uri saveShotToGallery(Bitmap bmp, String displayName) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName);
        v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/悬浮助手");
        v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        android.content.ContentResolver cr = service.getContentResolver();
        android.net.Uri uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            return null;
        }
        try (java.io.OutputStream out = cr.openOutputStream(uri, "w")) {
            if (out == null) {
                throw new java.io.IOException("openOutputStream 返回空");
            }
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new java.io.IOException("PNG 压缩失败");
            }
            out.flush();
        } catch (java.io.IOException e) {
            Log.i(TAG, "SHOT write fail " + e);
            cr.delete(uri, null, null);
            return null;
        }
        v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    /** 区域 OCR：截图失败沿用三态语义（给出类别文本），成功则只读选区内的行。 */
    private boolean routeOnceRegion(int[] rect, java.util.function.Consumer<String> onText) {
        Bitmap bmp;
        try {
            bmp = captureExcludingSelf(service.capturer());
        } catch (ScreenCapturer.CaptureException e) {
            Log.i(TAG, "REGION fail capture=" + e.outcome + " rect=" + rect[0] + "," + rect[1]
                    + "," + rect[2] + "," + rect[3] + " (" + e.getMessage() + ")");
            deliver(onText, "此页面不支持框选 · 截图失败(" + e.outcome + ")");
            return false;
        }
        int sw = bmp.getWidth();
        int sh = bmp.getHeight();
        int selX0 = clamp(Math.min(rect[0], rect[2]), 0, sw);
        int selY0 = clamp(Math.min(rect[1], rect[3]), 0, sh);
        int selX1 = clamp(Math.max(rect[0], rect[2]), 0, sw);
        int selY1 = clamp(Math.max(rect[1], rect[3]), 0, sh);
        if (selX1 - selX0 < 8 || selY1 - selY0 < 8) {
            bmp.recycle();
            Log.i(TAG, "REGION fail tooSmall=" + (selX1 - selX0) + "x" + (selY1 - selY0));
            deliver(onText, "选区太小（" + (selX1 - selX0) + "×" + (selY1 - selY0) + " px），未识别");
            return false;
        }
        // 用户裁定（2026-10-18）：框选的语义是**抠图**——只有被圈住的那块像素该参与识别。
        // 第一趟只送选区本体（灰边画布，零邻居），空了才走第二趟重试：补真实页面上下文
        // 并按「框中心落在选区内」筛行。两趟都在 bmp 上做，bmp 由最外层 finally 统一回收。
        try {
            final int selW = selX1 - selX0, selH = selY1 - selY0;
            StringBuilder a = new StringBuilder();
            int[] aBoxes = {0};
            Bitmap cut = cutCanvas(bmp, selX0, selY0, selW, selH);
            try {
                collectInto(cut, false, null, a, aBoxes);
            } finally {
                cut.recycle();
            }
            if (a.length() > 0) {
                Log.i(TAG, "REGION done pass=A(cut) sel=" + selX0 + "," + selY0 + " " + selW
                        + "x" + selH + " detBoxes=" + aBoxes[0] + " chars=" + a.length());
                deliver(onText, a.toString());
                return true;
            }

            int[] bBoxes = {0};
            String b = regionWithContext(bmp, selX0, selY0, selX1, selY1, sw, sh, bBoxes);
            if (b == null) {
                Log.i(TAG, "REGION fail empty sel=" + selX0 + "," + selY0 + " " + selW + "x" + selH
                        + " cutBoxes=" + aBoxes[0] + " ctxBoxes=" + bBoxes[0]);
                deliver(onText, "选区 " + selW + "×" + selH + " 未识到文字");
                return false;
            }
            Log.i(TAG, "REGION done pass=B(ctx) sel=" + selX0 + "," + selY0 + " " + selW + "x" + selH
                    + " cutBoxes=" + aBoxes[0] + " ctxBoxes=" + bBoxes[0] + " chars=" + b.length());
            deliver(onText, b);
            return true;
        } finally {
            bmp.recycle();
        }
    }

    /**
     * 抠图画布：把选区**原样**（不缩放）居中贴进纯色灰边画布，短边不足时补到 {@link #MIN_CTX_PX}。
     * 为什么不预先缩放（试过，2026-10-18 实测读出「的验测」这类乱码）：det 的下采样在 {@code run()}
     * 内部完成并把框映回送检图坐标，rec 再从**送检图**裁字——若我先缩 0.4，rec 拿到的就只有 5 px
     * 高的糊字了。保持原分辨率，rec 的取图尺度与整页路径完全一致，只少了页面邻居。
     *
     * <p>但**放大**小选区是安全且必要的：2~3 字的小框（实测 26×21）在灰底上 det 出 0 框，只能退回
     * 补真实邻居的第二趟 → 邻居被并成一个宽框、中心恰落在选区内 → 漏进相邻字（用户反馈③）。
     * 故这里把字高抬到 {@link #CUT_TARGET_H}（双线性）。不损 rec：整页路径的 buildRecInput 本就把
     * 同一条 21 px 的字拉到这同一高度；只是让 det 更容易看到这块小字。上限 {@link #CUT_MAX_SCALE}。
     */
    private static Bitmap cutCanvas(Bitmap src, int x0, int y0, int w, int h) {
        Bitmap raw = Bitmap.createBitmap(src, x0, y0, w, h);
        float s = CUT_MAX_SCALE;
        if (CUT_TARGET_H / (float) h < s) {
            s = CUT_TARGET_H / (float) h;
        }
        if (s < 1f) {
            s = 1f;
        }
        int pw = Math.max(1, Math.round(w * s));
        int ph = Math.max(1, Math.round(h * s));
        Bitmap piece = Bitmap.createScaledBitmap(raw, pw, ph, true);
        if (piece != raw) {
            raw.recycle();
        }
        int canvasW = Math.max(pw + 2 * CUT_PAD_PX, MIN_CTX_PX);
        int canvasH = Math.max(ph + 2 * CUT_PAD_PX, MIN_CTX_PX);
        Bitmap canvas = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas g = new android.graphics.Canvas(canvas);
        g.drawColor(android.graphics.Color.rgb(114, 114, 114));
        g.drawBitmap(piece, (canvasW - pw) / 2f, (canvasH - ph) / 2f, null);
        if (piece != canvas) {
            piece.recycle();
        }
        return canvas;
    }

    /**
     * 第二趟重试：选区太小/单行时 det 常出 0 框（实测 140×54 选区，2026-10-18），故向四周补出
     * <b>真实</b>页面内容凑到至少 {@link #MIN_CTX_PX} 见方再检测，然后只保留<b>框中心落在选区内</b>
     * 的行——补出来的邻居中心在选区外，会被判据丢掉。整页口径的伪框过滤在这趟必须关（选区 y 从 0
     * 起，套状态栏带会把真框全杀）。返回拼接文本；一行不剩时返回 null。
     */
    private String regionWithContext(Bitmap bmp, int selX0, int selY0, int selX1, int selY1,
                                     int sw, int sh, int[] boxCount) {
        int cx0 = selX0, cy0 = selY0, cx1 = selX1, cy1 = selY1;
        int needW = MIN_CTX_PX - (selX1 - selX0);
        int needH = MIN_CTX_PX - (selY1 - selY0);
        if (needW > 0) {
            int grow = needW / 2 + 1;
            cx0 = Math.max(0, selX0 - grow);
            cx1 = Math.min(sw, selX1 + (needW - grow));
            if (cx1 - cx0 < MIN_CTX_PX) {
                cx0 = Math.max(0, Math.min(cx0, sw - MIN_CTX_PX));
                cx1 = Math.min(sw, cx0 + MIN_CTX_PX);
            }
        }
        if (needH > 0) {
            int grow = needH / 2 + 1;
            cy0 = Math.max(0, selY0 - grow);
            cy1 = Math.min(sh, selY1 + (needH - grow));
            if (cy1 - cy0 < MIN_CTX_PX) {
                cy0 = Math.max(0, Math.min(cy0, sh - MIN_CTX_PX));
                cy1 = Math.min(sh, cy0 + MIN_CTX_PX);
            }
        }
        // keep 盒 = 选区在送检图里的位置（送检图原点为 cx0,cy0）
        final int[] keep = {selX0 - cx0, selY0 - cy0, selX1 - cx0, selY1 - cy0};
        int cw = cx1 - cx0;
        int ch = cy1 - cy0;
        Bitmap crop = Bitmap.createBitmap(bmp, cx0, cy0, cw, ch);
        try {
            StringBuilder sb = new StringBuilder();
            collectInto(crop, false, keep, sb, boxCount);
            if (sb.length() == 0) {
                Log.i(TAG, "REGION ctx empty ctx=" + cx0 + "," + cy0 + "," + cw + "x" + ch
                        + " detBoxes=" + boxCount[0]);
                return null;
            }
            Log.i(TAG, "REGION ctx ok lines=" + sb.toString().split("\n").length
                    + " detBoxes=" + boxCount[0] + " ctx=" + cw + "x" + ch);
            return sb.toString();
        } finally {
            // 选区铺满整屏时 createBitmap 直接返回源图，此时不能 recycle（bmp 由外层统一回收）
            if (crop != bmp) {
                crop.recycle();
            }
        }
    }

    /**
     * 一趟识别收口：把非空行拼进 {@code out}，det 总框数写进 {@code boxCount[0]}。
     * {@code keep} 非空时按<b>框中心</b>是否落在该盒内筛行（用中心不用整框包含：手画的框总比
     * 真框紧一点，要求包含会把目标行本身也滤掉，实测 5 框全落空）。
     */
    private void collectInto(Bitmap img, boolean pageFilters, int[] keep,
                             StringBuilder out, int[] boxCount) {
        final List<String> kept = new ArrayList<>();
        engine().recognizeStream(img, new OcrEngine.LineSink() {
            @Override
            public void onBoxes(int total) {
                boxCount[0] = total;
            }

            @Override
            public void onLine(int index, int total, int[] box, String text) {
                String t = text == null ? "" : text.trim();
                if (t.isEmpty()) {
                    return;
                }
                if (box == null || keep == null) {
                    kept.add(t);   // 后端未给框（桩/回退路径）或本趟无需判据，照收。
                    return;
                }
                int bx = (box[0] + box[2]) / 2;
                int by = (box[1] + box[3]) / 2;
                if (bx >= keep[0] && bx <= keep[2] && by >= keep[1] && by <= keep[3]) {
                    kept.add(t);
                }
            }

            @Override
            public void onDone() {
            }
        }, pageFilters);
        for (int i = 0; i < kept.size(); i++) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(kept.get(i));
        }
    }

    /**
     * 把结果交回主线程：调用方（服务）拿到的是可直接操作面板的上下文。
     * 用主 Looper Handler 而非 {@code Service#runOnUiThread}——后者在编译期不可见（基准机实测
     * 「找不到符号」，2026-10-18），与其依赖一个不稳的 SDK 细节，不如自己 post。
     */
    private void deliver(java.util.function.Consumer<String> onText, String text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> onText.accept(text));
    }

    private static int clamp(int value, int min, int max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }

    /** 原位贴图版采集：OCR 收框+文本 → 整页路由选引擎 → 逐框译文按像素矩形原位贴。返回是否走到 A 态并出文本。 */
    private boolean routeOnceInPlace() {
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
                return false;
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
            return true;
        }

        if (tuning.fallbackStrict()) {
            String text = "C 此页面不支持贴图 · 严格模式不读树 · 截图失败(" + capErr.outcome + ")";
            Log.i(TAG, "ROUTE=C(inplace,strict) " + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
            return false;
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
        return false;
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
        // 目标语**整轮读一次**：逐段现取的话，中途改语向会让同一屏混进两种译文。
        final String tgt = service.targetLang();
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
                    String tr = m.translate(texts.get(idx), tgt);
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

    private boolean routeOnce() {
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
                return true;
            }
            return false;
        }

        if (tuning.fallbackStrict()) {
            String text = "C 此页面不支持 · 严格模式不读树 · 截图失败(" + capErr.outcome + ")";
            Log.i(TAG, "ROUTE=C(strict) " + text);
            overlayManager.showTextOverlay(RESULT_KEY, text);
            return false;
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
        return false;
    }

    /**
     * 分段 MT：把 OCR 行按 {@link #SEG_MAX_CHARS} 边界聚成小段，逐段翻译再拼双语。
     * 引擎由中枢按整页路由预先选定（一页一引擎）。全程 try/catch(Throwable)，
     * **绝不让 MT 失败波及 OCR 覆盖层**。未就绪 → 记 "MT off" 直接返回。
     * 逐段增量刷新覆盖层（显示进度 + 已译部分），避免整页等待像卡死；某段空输出则跳过、不影响其余段。
     */
    private void runTranslateSegmented(MtEngine m, String orig, List<String> lines, int w, int h) {
        // 同 runInPlace：语向整轮读一次，别让用户中途改设置把同一页译成两种语言。
        final String tgt = service.targetLang();
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
                String tr = m.translate(seg, tgt);
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
