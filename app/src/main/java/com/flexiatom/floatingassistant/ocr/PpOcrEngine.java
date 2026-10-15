package com.flexiatom.floatingassistant.ocr;

import android.graphics.Bitmap;

import com.flexiatom.floatingassistant.core.OcrEngine;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * PP-OCRv4 端到端引擎（P1 后端 benchmark 用，纯 Java、不引 OpenCV）。
 *
 * <p>链路：det 预处理(限边 960，round 到 32) → det 推理 → DB 概率图二值化 + 连通域
 * → 轴对齐框 + margin → 每框裁剪缩放到 48 高 → rec 推理 → CTC 贪心解码。
 *
 * <p>口径限制（首切片，故意保守）：文本框为**轴对齐外接矩形 + 固定 margin**，不做
 * box-polygon unclip、不处理旋转文本；crop 走 axis-aligned 子图 + 双线性缩放。
 * 这些会影响倾斜/密集文本的准确率，但足以端到端产出**正确管线**并量出真实延迟。
 * 后端选型判据（ORT vs ncnn）只依赖这条统一管线，切后端仅换 OrtSession 实现。
 */
public final class PpOcrEngine implements OcrEngine {

    private static final int DET_LIMIT_SIDE = 960;
    private static final float DET_BIN_TH = 0.3f;
    private static final int DET_MIN_AREA = 30;
    private static final int REC_H = 48;
    private static final int REC_MIN_W = 16;
    private static final int REC_MAX_W = 320;
    private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment env;
    private final OrtSession det;
    private final OrtSession rec;
    private final String detInput;
    private final String recInput;
    /** CTC 字符表：index0=blank(隐式)，charset[0]=" "，charset[i]=dict[i-1]。 */
    private final String[] charset;

    public final long loadDetMs;
    public final long loadRecMs;

    /** 最近一次 recognize 的分段耗时与统计（供测量跑手打印）。 */
    public long lastDetPreMs, lastDetInferMs, lastDbMs, lastRecMs, lastCtcMs;
    public int lastDetW, lastDetH, lastNBoxes;
    /** rec 模型输出类别数（含 blank），首框推理时从张量形状取；与 charset.length+1 应一致。 */
    public int lastClasses;
    /** 调试：最近一次的文本框（原图像素坐标 x0,y0,x1,y1）与 det 概率图统计。 */
    public List<int[]> lastBoxes = Collections.emptyList();
    public float lastProbMax, lastProbMean;
    public int lastProbOn;

    private boolean closed;

    public PpOcrEngine(File detModel, File recModel, List<String> dict, int threads)
            throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        long a = System.nanoTime();
        try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
            o.setIntraOpNumThreads(threads);
            this.det = env.createSession(detModel.getAbsolutePath(), o);
        }
        this.loadDetMs = (System.nanoTime() - a) / 1_000_000L;
        a = System.nanoTime();
        try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
            o.setIntraOpNumThreads(threads);
            this.rec = env.createSession(recModel.getAbsolutePath(), o);
        }
        this.loadRecMs = (System.nanoTime() - a) / 1_000_000L;
        this.detInput = det.getInputNames().iterator().next();
        this.recInput = rec.getInputNames().iterator().next();
        // PaddleOCR CTC 标签约定：index0=blank，index1..N=dict 逐行，index N+1=空格（追加在末尾）。
        int nd = dict.size();
        this.charset = new String[nd + 2];
        this.charset[0] = "";
        for (int i = 0; i < nd; i++) {
            this.charset[i + 1] = dict.get(i);
        }
        this.charset[nd + 1] = " ";
        this.lastClasses = charset.length;
    }

    @Override
    public Result recognize(Bitmap src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        List<String> lines = new ArrayList<>();
        try {
            // --- det 输入尺寸：长边限 960，宽高各 round 到 32 的倍数 ---
            float scale = DET_LIMIT_SIDE / (float) Math.max(srcW, srcH);
            if (scale > 1f) {
                scale = 1f;
            }
            int rh = Math.max(32, Math.round(Math.round(srcH * scale) / 32f) * 32);
            int rw = Math.max(32, Math.round(Math.round(srcW * scale) / 32f) * 32);

            long t = System.nanoTime();
            float[] detIn = buildDetInput(src, rw, rh);
            lastDetPreMs = msSince(t);
            lastDetW = rw;
            lastDetH = rh;

            t = System.nanoTime();
            float[] probMap = runDet(detIn, rw, rh);
            lastDetInferMs = msSince(t);

            t = System.nanoTime();
            List<int[]> boxes = dbPostprocess(probMap, rw, rh, srcW, srcH);
            lastDbMs = msSince(t);
            lastNBoxes = boxes.size();
            lastBoxes = boxes;

            t = System.nanoTime();
            long recT = 0, ctcT = 0;
            for (int[] b : boxes) {
                long r0 = System.nanoTime();
                float[] recIn = buildRecInput(src, b);
                int recW = (b[2] > b[0]) ? recWidthFor(b[3] - b[1], b[2] - b[0]) : 4;
                float[] recOut = runRec(recIn, recW);
                long r1 = System.nanoTime();
                recT += (r1 - r0) / 1_000_000L;
                String line = ctcDecode(recOut);
                long r2 = System.nanoTime();
                ctcT += (r2 - r1) / 1_000_000L;
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
            lastRecMs = recT;
            lastCtcMs = ctcT;
        } catch (Throwable err) {
            return new Result("P1 管线异常: " + err.getClass().getSimpleName() + ": " + err.getMessage(),
                    Collections.emptyList());
        }
        return new Result("P1 PP-OCRv4", lines);
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ---- det ----

    private float[] buildDetInput(Bitmap src, int rw, int rh) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, rw, rh, true);
        int n = rw * rh;
        int[] px = new int[n];
        scaled.getPixels(px, 0, rw, 0, 0, rw, rh);
        if (scaled != src) {
            scaled.recycle();
        }
        float[] out = new float[3 * n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            float r = (((c >> 16) & 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0];
            float g = (((c >> 8) & 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1];
            float b = ((c & 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2];
            out[i] = r;
            out[n + i] = g;
            out[2 * n + i] = b;
        }
        return out;
    }

    private float[] runDet(float[] input, int rw, int rh) throws OrtException {
        long[] shape = {1, 3, rh, rw};
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result res = det.run(Collections.singletonMap(detInput, in))) {
            OnnxValue v = res.get(0);
            float[][][][] o = (float[][][][]) ((OnnxTensor) v).getValue();
            float[][] map = o[0][0]; // [rh][rw]
            float[] flat = new float[rh * rw];
            for (int y = 0; y < rh; y++) {
                System.arraycopy(map[y], 0, flat, y * rw, rw);
            }
            return flat;
        }
    }

    // ---- DB 后处理：二值化 + 8 邻域连通域 → 轴对齐框 + margin → 原图坐标 ----

    private List<int[]> dbPostprocess(float[] prob, int rw, int rh, int srcW, int srcH) {
        int n = rw * rh;
        // 单次扫描：同时算概率图统计（max/mean/on）并做 8-邻接连通域。
        // 不再预建 boolean[] bin（省一次 430k 分配+写）；前景判定在触碰处直接比 prob。
        int[] labels = new int[n];
        java.util.Arrays.fill(labels, -1);
        int[] qx = new int[n];
        int[] qy = new int[n];

        float fx = srcW / (float) rw;
        float fy = srcH / (float) rh;
        List<int[]> boxes = new ArrayList<>();
        float mx = -1e9f, sum = 0f;
        int on = 0;
        int label = 0;
        for (int y = 0; y < rh; y++) {
            int rowBase = y * rw;
            for (int x = 0; x < rw; x++) {
                int idx = rowBase + x;
                float p = prob[idx];
                if (p > mx) mx = p;
                sum += p;
                if (p <= DET_BIN_TH || labels[idx] >= 0) {
                    continue;
                }
                on++;
                int head = 0, tail = 0;
                qx[tail] = x;
                qy[tail] = y;
                tail++;
                labels[idx] = label;
                int minX = x, maxX = x, minY = y, maxY = y, area = 0;
                while (head < tail) {
                    int cx = qx[head];
                    int cy = qy[head];
                    head++;
                    area++;
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;
                    int yLo = Math.max(0, cy - 1), yHi = Math.min(rh - 1, cy + 1);
                    int xLo = Math.max(0, cx - 1), xHi = Math.min(rw - 1, cx + 1);
                    for (int ny = yLo; ny <= yHi; ny++) {
                        int nb = ny * rw;
                        for (int nx = xLo; nx <= xHi; nx++) {
                            int ni = nb + nx;
                            if (labels[ni] < 0 && prob[ni] > DET_BIN_TH) {
                                labels[ni] = label;
                                on++;
                                qx[tail] = nx;
                                qy[tail] = ny;
                                tail++;
                            }
                        }
                    }
                }
                label++;
                if (area < DET_MIN_AREA) {
                    continue;
                }
                // DB unclip 距离：area·ratio/perimeter（细长按比例扩张更多），替代固定 margin
                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;
                int m = Math.max(2, Math.round((bw * bh * 1.5f) / (2f * (bw + bh))));
                int x0 = Math.max(0, minX - m);
                int y0 = Math.max(0, minY - m);
                int x1 = Math.min(rw - 1, maxX + m);
                int y1 = Math.min(rh - 1, maxY + m);
                // 回原图像素坐标
                int ox0 = Math.round(x0 * fx);
                int oy0 = Math.round(y0 * fy);
                int ox1 = Math.round(x1 * fx);
                int oy1 = Math.round(y1 * fy);
                if (ox1 - ox0 < 3 || oy1 - oy0 < 2) {
                    continue;
                }
                boxes.add(new int[]{ox0, oy0, ox1, oy1});
            }
        }
        lastProbMax = mx;
        lastProbMean = sum / n;
        lastProbOn = on;
        // 阅读顺序：先上后下，再左后右
        Collections.sort(boxes, (a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1])
                : Integer.compare(a[0], b[0]));
        return boxes;
    }

    // ---- rec ----

    private static int recWidthFor(int boxH, int boxW) {
        int w = Math.round(REC_H * (boxW / (float) boxH));
        if (w < REC_MIN_W) {
            w = REC_MIN_W;
        }
        if (w > REC_MAX_W) {
            w = REC_MAX_W;
        }
        w = Math.max(4, w & ~3); // rec 卷积按 4 下采样，宽度取 4 的倍数
        return w;
    }

    private float[] buildRecInput(Bitmap src, int[] box) {
        int x0 = Math.max(0, box[0]);
        int y0 = Math.max(0, box[1]);
        int x1 = Math.min(src.getWidth(), box[2]);
        int y1 = Math.min(src.getHeight(), box[3]);
        int bw = Math.max(1, x1 - x0);
        int bh = Math.max(1, y1 - y0);
        int recW = recWidthFor(bh, bw);
        Bitmap crop = Bitmap.createBitmap(src, x0, y0, bw, bh);
        Bitmap scaled = Bitmap.createScaledBitmap(crop, recW, REC_H, true);
        if (scaled != crop) {
            crop.recycle();
        } else if (crop != src) {
            crop.recycle();
        }
        int n = recW * REC_H;
        int[] px = new int[n];
        scaled.getPixels(px, 0, recW, 0, 0, recW, REC_H);
        if (scaled != src) {
            scaled.recycle();
        }
        // RapidOCR 的 rec ONNX 以 cv2(BGR) 导出：channel0=B, 1=G, 2=R
        float[] out = new float[3 * n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            float r = (((c >> 16) & 0xFF) / 127.5f) - 1f;
            float g = (((c >> 8) & 0xFF) / 127.5f) - 1f;
            float b = ((c & 0xFF) / 127.5f) - 1f;
            out[i] = b;
            out[n + i] = g;
            out[2 * n + i] = r;
        }
        return out;
    }

    private float[] runRec(float[] input, int recW) throws OrtException {
        long[] shape = {1, 3, REC_H, recW};
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result res = rec.run(Collections.singletonMap(recInput, in))) {
            OnnxValue v = res.get(0);
            float[][][] o = (float[][][]) ((OnnxTensor) v).getValue();
            this.lastClasses = o[0][0].length;
            return flatten(o[0]); // [T*C]
        }
    }

    private static float[] flatten(float[][] rows) {
        int t = rows.length;
        int c = rows[0].length;
        float[] out = new float[t * c];
        for (int i = 0; i < t; i++) {
            System.arraycopy(rows[i], 0, out, i * c, c);
        }
        return out;
    }

    private String ctcDecode(float[] flat) {
        // flat = [T][C]；C = rec 模型输出类别数（含 blank=0）
        int c = lastClasses;
        int t = flat.length / c;
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        for (int i = 0; i < t; i++) {
            int base = i * c;
            int best = 0;
            float bv = flat[base];
            for (int k = 1; k < c; k++) {
                float v = flat[base + k];
                if (v > bv) {
                    bv = v;
                    best = k;
                }
            }
            if (best != 0 && best != prev) {
                if (best < charset.length) {
                    sb.append(charset[best]);
                }
            }
            prev = best;
        }
        return sb.toString();
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            det.close();
            rec.close();
        } catch (OrtException ignored) {
            // 探针式释放，忽略
        }
    }
}
