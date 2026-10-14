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

    public static void run(Context ctx, String mode, long delayMs, int threads,
                           String detRel, String recRel, String dictRel) {
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
            Log.i(TAG, "engine loaded loadDetMs=" + engine.loadDetMs
                    + " loadRecMs=" + engine.loadRecMs);
            if ("both".equals(mode) || "synth".equals(mode)) {
                runSynth(engine, base);
            }
            if ("both".equals(mode) || "live".equals(mode)) {
                runLive(ctx, engine, base, delayMs);
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

    private static void runSynth(PpOcrEngine engine, File base) {
        Bitmap card = drawCard();
        try {
            File out = new File(base, "P1_synth.png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                card.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } catch (IOException e) {
                Log.w(TAG, "synth png save failed: " + e.getMessage());
            }
            long t = System.nanoTime();
            OcrEngine.Result r = engine.recognize(card);
            long totalMs = (System.nanoTime() - t) / 1_000_000L;
            String joined = String.join("\n", r.lines);
            int hit = 0;
            for (String exp : SYNTH_LINES) {
                if (joined.replace(" ", "").contains(exp.replace(" ", ""))) {
                    hit++;
                }
            }
            Log.i(TAG, "SYNTH expectedLines=" + SYNTH_LINES.length + " exactLines=" + hit
                    + " boxes=" + engine.lastNBoxes + " detHxW=" + engine.lastDetH + "x" + engine.lastDetW
                    + " totalMs=" + totalMs + " detail[" + breakdown(engine) + "]");
            Log.i(TAG, "SYNTH probMax=" + engine.lastProbMax + " probMean=" + engine.lastProbMean
                    + " probOn(>0.3)=" + engine.lastProbOn);
            StringBuilder bs = new StringBuilder();
            for (int[] b : engine.lastBoxes) {
                bs.append('[').append(b[0]).append(',').append(b[1]).append(',')
                        .append(b[2]).append(',').append(b[3]).append("] ");
            }
            Log.i(TAG, "SYNTH boxes=" + bs);
            drawBoxes(card, engine.lastBoxes, new File(base, "P1_boxes.png"));
            Log.i(TAG, "SYNTH got=" + joined.replace("\n", " ⏎ "));
            Log.i(TAG, "SYNTH expected=" + String.join(" ⏎ ", SYNTH_LINES));
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

    private static Bitmap drawCard() {        int w = 720;
        int h = 1600;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.SANS_SERIF);
        p.setTextSize(52f);
        float y = 160f;
        for (String line : SYNTH_LINES) {
            c.drawText(line, 48f, y, p);
            y += 140f;
        }
        return bmp;
    }

    // ---- 实屏：真实延迟 + 落盘供核对 ----

    private static void runLive(Context ctx, PpOcrEngine engine, File base, long delayMs)
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
            long t = System.nanoTime();
            OcrEngine.Result r = engine.recognize(shot);
            long totalMs = (System.nanoTime() - t) / 1_000_000L;
            Log.i(TAG, "LIVE src=" + shot.getWidth() + "x" + shot.getHeight()
                    + " png=" + out.getAbsolutePath()
                    + " lines=" + r.lines.size() + " boxes=" + engine.lastNBoxes
                    + " detHxW=" + engine.lastDetH + "x" + engine.lastDetW
                    + " totalMs=" + totalMs + " detail[" + breakdown(engine) + "]");
            List<String> trimmed = new ArrayList<>();
            for (String line : r.lines) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    trimmed.add(s.length() > 60 ? s.substring(0, 60) + "…" : s);
                }
            }
            Log.i(TAG, "LIVE text=" + String.join(" ⏎ ", trimmed));
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
