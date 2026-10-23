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

package com.flexiatom.floatingassistant.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import ai.onnxruntime.OrtException;

import com.flexiatom.floatingassistant.core.OcrEngine;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * P1-b 测量跑手：在基准机上端到端跑 PP-OCRv4，产出**真实延迟分解 + 中文识别准确率**。
 *
 * <p>两种输入：
 * <ul>
 *   <li>{@code synth}：Canvas 画已知文本（有逐字真值，可算准确率；但渲染态字体/密度
 *       与真实截图不同，是偏乐观的上界，明确标注）。</li>
 *   <li>{@code live}：延时后用无障碍截图抓当前屏（真实输入，量真实端到端延迟；
 *       输入 PNG 落盘到外部 files 供 adb pull 逐字核对）。</li>
 * </ul>
 * 结果打到 logcat tag {@code P1OCR}。
 */
public final class P1Runner {

    private static final String TAG = "P1OCR";

    // stream 过滤 A/B 的"开"档阈值：pass A dump 显示状态栏时钟框 y1=83、通知杂框 y1=58，
    // 正文首行 y0=125（y1=158）。取 100 ∈ (83,125) 干净丢弃两枚伪框、不伤正文。
    private static final int STREAM_SKIP_TOP_PX = 100;
    private static final int STREAM_MIN_BOX_H = 12;

    private P1Runner() {
    }

    public static void run(Context ctx, String mode, long delayMs, int threads, int recMaxW,
                           int detLimit, String detRel, String recRel, String dictRel) {
        File base = ctx.getExternalFilesDir(null);
        File det = new File(base, detRel);
        File rec = new File(base, recRel);
        File dictFile = new File(base, dictRel);
        if (!det.isFile() || !rec.isFile() || !dictFile.isFile()) {
            Log.e(TAG, "RESULT error=missing_inputs det=" + det.isFile()
                    + " rec=" + rec.isFile() + " dict=" + dictFile.isFile()
                    + " base=" + base);
            return;
        }
        List<String> dict = readLines(dictFile);
        Log.i(TAG, "dict entries=" + dict.size() + " (expect 6623 → rec classes 6625)");

        PpOcrEngine engine = null;
        try {
            engine = new PpOcrEngine(det, rec, dict, threads);
            if (recMaxW > 0) {
                engine.recMaxW = recMaxW;
            }
            if (detLimit > 0) {
                engine.detLimitSide = detLimit;
            }
            Log.i(TAG, "engine loaded loadDetMs=" + engine.loadDetMs
                    + " loadRecMs=" + engine.loadRecMs
                    + " threads=" + threads + " recMaxW=" + engine.recMaxW
                    + " detLimit=" + engine.detLimitSide);
            if ("both".equals(mode) || "synth".equals(mode)) {
                runSynth(engine, base);
            }
            if ("dense".equals(mode) || "all".equals(mode)) {
                runDense(engine, base);
            }
            if ("small".equals(mode)) {
                runSmall(engine, base);
            }
            if ("both".equals(mode) || "live".equals(mode) || "all".equals(mode)) {
                runLive(ctx, engine, base, delayMs, detLimit);
            }
            if ("recquant".equals(mode)) {
                runRecQuant(ctx, base, det, rec, dict, threads, delayMs,
                        detLimit > 0 ? detLimit : 640);
            }
            if ("stream".equals(mode)) {
                runStream(ctx, engine, base, delayMs, detLimit);
            }
        } catch (Throwable t) {
            Log.e(TAG, "RESULT error=" + t.getClass().getSimpleName() + " msg=" + t.getMessage(), t);
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    // ---- 合成卡：已知文本 → 准确率 ----

    private static final String[] SYNTH_LINES = {
            "悬浮助手全屏翻译测试",
            "今天天气晴朗适合出行",
            "Hello World OCR 12345",
            "设置无障碍服务权限",
    };

    // 复刻实屏识别失败的**密集/小字/拉丁+符号**用例（字号小、行距紧、含型号串与中英混排）。
    private static final String[] DENSE_LINES = {
            "Redmi 13R 5G",
            "HyperOS 2.0.208.0.VGQCNXM",
            "安全补丁 2026-03-01",
            "Android 15 · 天玑 6100+",
    };

    private static void runSynth(PpOcrEngine engine, File base) {
        runSynthCard(engine, base, SYNTH_LINES, drawCard(SYNTH_LINES, 52f, 140f, 160f),
                "P1_synth", "SYNTH");
    }

    private static void runDense(PpOcrEngine engine, File base) {
        runSynthCard(engine, base, DENSE_LINES, drawCard(DENSE_LINES, 34f, 92f, 120f),
                "P1_dense", "DENSE");
    }

    // 小字密排：8 行 ~18px 正文，模拟真实长文/设置详情页，用于测"分辨率→小字召回"的断崖。
    private static final String[] SMALL_LINES = {
            "本软件按「现状」提供，不担保（GPLv3 §15/§16）。",
            "仅在 Redmi 13R 5G / HyperOS 2.0.208 / Android 15 上验证过。",
            "全屏翻译依赖无障碍截图与端侧 OCR，不上传任何图像内容。",
            "点击悬浮球可即时翻译当前屏，长按进入设置面板。",
            "识别结果以浮层覆盖显示，支持复制与朗读。",
            "端侧模型：PP-OCRv4 检测识别，Hy-MT2 翻译，Piper 语音。",
            "内存占用受档位限制，低端机建议关闭后台常驻。",
            "如遇 FLAG_SECURE 页面（银行/支付）将提示不支持截屏。",
    };

    private static void runSmall(PpOcrEngine engine, File base) {
        Bitmap card = drawCard(SMALL_LINES, 18f, 40f, 60f);
        try {
            File out = new File(base, "P1_small.png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                card.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } catch (IOException e) {
                Log.w(TAG, "small png save failed: " + e.getMessage());
            }
            engine.recognize(card); // 预热去冷-JIT
            int orig = engine.detLimitSide;
            for (int lim : new int[]{960, 736, 640, 512, 448}) {
                engine.detLimitSide = lim;
                long t = System.nanoTime();
                OcrEngine.Result r = engine.recognize(card);
                long totalMs = (System.nanoTime() - t) / 1_000_000L;
                String joined = String.join("\n", r.lines);
                String noSp = joined.replace(" ", "");
                int hit = 0;
                for (String exp : SMALL_LINES) {
                    if (noSp.contains(exp.replace(" ", ""))) hit++;
                }
                Log.i(TAG, "SMALL lim=" + lim + " detHxW=" + engine.lastDetH + "x" + engine.lastDetW
                        + " exactLines=" + hit + "/" + SMALL_LINES.length
                        + " boxes=" + engine.lastNBoxes + " totalMs=" + totalMs
                        + " detail[" + breakdown(engine) + "]");
                Log.i(TAG, "SMALL[" + lim + "] got=" + joined.replace("\n", " ⏎ "));
            }
            engine.detLimitSide = orig;
        } finally {
            card.recycle();
        }
    }

    private static void runSynthCard(PpOcrEngine engine, File base, String[] lines, Bitmap card,
                                     String pngBase, String tag) {
        try {
            File out = new File(base, pngBase + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                card.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } catch (IOException e) {
                Log.w(TAG, tag + " png save failed: " + e.getMessage());
            }
            long t = System.nanoTime();
            OcrEngine.Result r = engine.recognize(card);
            long totalMs = (System.nanoTime() - t) / 1_000_000L;
            String joined = String.join("\n", r.lines);
            String joinedNoSp = joined.replace(" ", "");
            int hit = 0;
            StringBuilder miss = new StringBuilder();
            for (String exp : lines) {
                if (joinedNoSp.contains(exp.replace(" ", ""))) {
                    hit++;
                } else {
                    miss.append('{').append(exp).append("} ");
                }
            }
            Log.i(TAG, tag + " expectedLines=" + lines.length + " exactLines=" + hit
                    + " boxes=" + engine.lastNBoxes + " detHxW=" + engine.lastDetH + "x" + engine.lastDetW
                    + " totalMs=" + totalMs + " detail[" + breakdown(engine) + "]");
            Log.i(TAG, tag + " probMax=" + engine.lastProbMax + " probMean=" + engine.lastProbMean
                    + " probOn(>0.3)=" + engine.lastProbOn);
            if (miss.length() > 0) {
                Log.i(TAG, tag + " MISS=" + miss);
            }
            Log.i(TAG, tag + " got=" + joined.replace("\n", " ⏎ "));
            Log.i(TAG, tag + " expected=" + String.join(" ⏎ ", lines));
            drawBoxes(card, engine.lastBoxes, new File(base, pngBase + "_boxes.png"));
        } finally {
            card.recycle();
        }
    }

    private static void drawBoxes(Bitmap src, List<int[]> boxes, File out) {
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(copy);
        Paint p = new Paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(Color.RED);
        for (int[] b : boxes) {
            c.drawRect(b[0], b[1], b[2], b[3], p);
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            copy.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            Log.w(TAG, "boxes png save failed: " + e.getMessage());
        }
        copy.recycle();
    }

    private static Bitmap drawCard(String[] lines, float textSize, float step, float y0) {
        int w = 720;
        int h = 1600;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.SANS_SERIF);
        p.setTextSize(textSize);
        float y = y0;
        for (String line : lines) {
            c.drawText(line, 48f, y, p);
            y += step;
        }
        return bmp;
    }

    // ---- 实屏：真实延迟 + 落盘供核对 ----

    private static void runLive(Context ctx, PpOcrEngine engine, File base, long delayMs, int detLimit)
            throws InterruptedException, IOException {
        if (delayMs > 0) {
            Log.i(TAG, "live waiting " + delayMs + "ms — 请切到要测的中文页面");
            Thread.sleep(delayMs);
        }
        SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
        if (svc == null) {
            Log.e(TAG, "live skipped: 无障碍服务未连接");
            return;
        }
        Bitmap shot;
        try {
            shot = svc.capturer().capture();
        } catch (Throwable e) {
            Log.e(TAG, "live capture failed: " + e.getMessage());
            return;
        }
        File out = new File(base, "P1_input.png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        try {
            Log.i(TAG, "LIVE captured src=" + shot.getWidth() + "x" + shot.getHeight()
                    + " png=" + out.getAbsolutePath());
            // 丢弃式预热：让 Java 后处理循环先过 JIT，避免首个计时项被冷启动解释执行污染。
            engine.recognize(shot);
            // 同一帧上 A/B det 长边上限：隔离"分辨率→延迟/召回"，其余变量全同。
            int[] limits = detLimit > 0
                    ? new int[]{detLimit, 736, 640, 512}
                    : new int[]{960, 736, 640, 512};
            int orig = engine.detLimitSide;
            for (int lim : limits) {
                engine.detLimitSide = lim;
                long t = System.nanoTime();
                OcrEngine.Result r = engine.recognize(shot);
                long totalMs = (System.nanoTime() - t) / 1_000_000L;
                List<String> trimmed = new ArrayList<>();
                for (String line : r.lines) {
                    String s = line.trim();
                    if (!s.isEmpty()) {
                        trimmed.add(s.length() > 48 ? s.substring(0, 48) + "…" : s);
                    }
                }
                Log.i(TAG, "LIVE lim=" + lim + " detHxW=" + engine.lastDetH + "x" + engine.lastDetW
                        + " lines=" + r.lines.size() + " boxes=" + engine.lastNBoxes
                        + " totalMs=" + totalMs + " detail[" + breakdown(engine) + "]");
                Log.i(TAG, "LIVE[" + lim + "] text=" + String.join(" ⏎ ", trimmed));
            }
            engine.detLimitSide = orig;
        } finally {
            shot.recycle();
        }
    }

    // A/B：同一帧上 fp32 rec vs int8 rec —— 隔离"量化→rec 延迟/准确率"，det 与其余全同。
    private static void runRecQuant(Context ctx, File base, File det, File recFp32,
                                    List<String> dict, int threads, long delayMs, int detLimit)
            throws InterruptedException, IOException, OrtException {
        File recI8 = new File(base, "rec_int8.onnx");
        if (!recI8.isFile()) {
            Log.e(TAG, "RECQUANT missing " + recI8.getAbsolutePath());
            return;
        }
        if (delayMs > 0) {
            Log.i(TAG, "recquant waiting " + delayMs + "ms — 请切到要测的中文页面");
            Thread.sleep(delayMs);
        }
        SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
        if (svc == null) {
            Log.e(TAG, "recquant skipped: 无障碍服务未连接");
            return;
        }
        Bitmap shot;
        try {
            shot = svc.capturer().capture();
        } catch (Throwable e) {
            Log.e(TAG, "recquant capture failed: " + e.getMessage());
            return;
        }
        PpOcrEngine fp = null;
        PpOcrEngine i8 = null;
        try {
            fp = new PpOcrEngine(det, recFp32, dict, threads);
            i8 = new PpOcrEngine(det, recI8, dict, threads);
            fp.detLimitSide = detLimit;
            i8.detLimitSide = detLimit;
            Log.i(TAG, "RECQUANT detLimit=" + detLimit + " threads=" + threads
                    + " fp32loadRecMs=" + fp.loadRecMs + " int8loadRecMs=" + i8.loadRecMs);
            fp.recognize(shot);
            i8.recognize(shot); // 预热去冷-JIT
            Bench bf = bench(fp, shot);
            Bench bi = bench(i8, shot);
            Log.i(TAG, "RECQUANT fp32 rec=" + bf.recMs + " total=" + bf.totalMs + " boxes=" + bf.boxes);
            Log.i(TAG, "RECQUANT int8 rec=" + bi.recMs + " total=" + bi.totalMs + " boxes=" + bi.boxes);
            Log.i(TAG, "RECQUANT fp32 text=" + bf.text);
            Log.i(TAG, "RECQUANT int8 text=" + bi.text);
            Log.i(TAG, "RECQUANT recSpeedup=" + ratio(bf.recMs, bi.recMs)
                    + "x totalSpeedup=" + ratio(bf.totalMs, bi.totalMs) + "x");
        } finally {
            if (fp != null) {
                fp.close();
            }
            if (i8 != null) {
                i8.close();
            }
            shot.recycle();
        }
    }

    private static String ratio(long a, long b) {
        return b > 0 ? String.format("%.2f", (float) a / b) : "?";
    }

    private static final class Bench {
        long recMs;
        long totalMs;
        int boxes;
        String text;
    }

    private static Bench bench(PpOcrEngine e, Bitmap shot) {
        Bench best = new Bench();
        best.recMs = Long.MAX_VALUE;
        best.totalMs = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            OcrEngine.Result r = e.recognize(shot);
            long total = (System.nanoTime() - t) / 1_000_000L;
            if (total < best.totalMs) {
                best.totalMs = total;
                best.recMs = e.lastRecMs;
                best.boxes = e.lastNBoxes;
                StringBuilder sb = new StringBuilder();
                for (String line : r.lines) {
                    String s = line.trim();
                    if (!s.isEmpty()) {
                        sb.append(s.length() > 48 ? s.substring(0, 48) + "…" : s).append('⏎');
                    }
                }
                best.text = sb.toString();
            }
        }
        return best;
    }

    // B3：流式逐框出字。量两件事——① 首行到达延迟 vs 总时长（流式的体感收益）；
    // ② 伪框过滤（状态栏 + 矮框）对"检测框数/总时长/首行"的影响。pass A 逐框 dump 坐标供校准阈值。
    private static void runStream(Context ctx, PpOcrEngine engine, File base, long delayMs, int detLimit)
            throws InterruptedException, IOException {
        if (delayMs > 0) {
            Log.i(TAG, "stream waiting " + delayMs + "ms — 请切到要测的页面（含状态栏/悬浮球）");
            Thread.sleep(delayMs);
        }
        SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
        if (svc == null) {
            Log.e(TAG, "stream skipped: 无障碍服务未连接");
            return;
        }
        Bitmap shot;
        try {
            shot = svc.capturer().capture();
        } catch (Throwable e) {
            Log.e(TAG, "stream capture failed: " + e.getMessage());
            return;
        }
        int origLimit = engine.detLimitSide;
        if (detLimit > 0) {
            engine.detLimitSide = detLimit;
        }
        try {
            Log.i(TAG, "STREAM captured src=" + shot.getWidth() + "x" + shot.getHeight()
                    + " detLimit=" + engine.detLimitSide);
            engine.recognize(shot); // 丢弃式预热，去冷-JIT
            // Pass A：过滤关，逐框 dump 坐标。
            engine.skipTopPx = 0;
            engine.minBoxH = 0;
            StreamProbe a = new StreamProbe();
            engine.recognizeStream(shot, a);
            Log.i(TAG, "STREAM A(off) detBoxes=" + engine.lastDetBoxes
                    + " keptBoxes=" + engine.lastNBoxes + " lines=" + a.lines
                    + " detDoneMs=" + a.boxesMs + " firstLineMs=" + a.firstLineMs
                    + " totalMs=" + a.totalMs);
            // Pass B：过滤开（状态栏区 + 矮框）。
            engine.skipTopPx = STREAM_SKIP_TOP_PX;
            engine.minBoxH = STREAM_MIN_BOX_H;
            StreamProbe b = new StreamProbe();
            engine.recognizeStream(shot, b);
            Log.i(TAG, "STREAM B(on skip=" + STREAM_SKIP_TOP_PX + " minH=" + STREAM_MIN_BOX_H
                    + ") detBoxes=" + engine.lastDetBoxes + " keptBoxes=" + engine.lastNBoxes
                    + " lines=" + b.lines + " detDoneMs=" + b.boxesMs
                    + " firstLineMs=" + b.firstLineMs + " totalMs=" + b.totalMs);
        } finally {
            engine.skipTopPx = 0;
            engine.minBoxH = 0;
            engine.detLimitSide = origLimit;
            shot.recycle();
        }
    }

    /** 流式探测：记录相对起点的每行到达时刻，量"首行延迟 vs 总时长"，并逐框 dump 坐标。 */
    private static final class StreamProbe implements OcrEngine.LineSink {
        final long startNs = System.nanoTime();
        long boxesMs = -1;
        long firstLineMs = -1;
        long totalMs = -1;
        int lines = 0;

        @Override
        public void onBoxes(int total) {
            boxesMs = (System.nanoTime() - startNs) / 1_000_000L;
            Log.i(TAG, "STREAM onBoxes=" + total + " +" + boxesMs + "ms");
        }

        @Override
        public void onLine(int index, int total, int[] box, String text) {
            long off = (System.nanoTime() - startNs) / 1_000_000L;
            if (firstLineMs < 0) {
                firstLineMs = off;
            }
            lines++;
            String coord = box == null ? "?"
                    : ("[" + box[0] + "," + box[1] + "," + box[2] + "," + box[3] + "]");
            String s = text.trim();
            if (s.length() > 40) {
                s = s.substring(0, 40) + "…";
            }
            Log.i(TAG, "STREAM line#" + index + " +" + off + "ms " + coord + " \"" + s + "\"");
        }

        @Override
        public void onDone() {
            totalMs = (System.nanoTime() - startNs) / 1_000_000L;
        }
    }

    private static String breakdown(PpOcrEngine e) {
        return "det_pre=" + e.lastDetPreMs + " det_infer=" + e.lastDetInferMs
                + " db_post=" + e.lastDbMs + " rec=" + e.lastRecMs + " ctc=" + e.lastCtcMs
                + " recClasses=" + e.lastClasses;
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "dict read failed: " + e.getMessage());
        }
        // readLine 不把末尾换行算作空行；但字典文件常有一行是空格字符——保留原样。
        return out;
    }
}
