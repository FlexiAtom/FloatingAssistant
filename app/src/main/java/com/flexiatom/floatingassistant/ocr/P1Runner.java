package com.flexiatom.floatingassistant.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

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
            if ("both".equals(mode) || "live".equals(mode) || "all".equals(mode)) {
                runLive(ctx, engine, base, delayMs, detLimit);
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
