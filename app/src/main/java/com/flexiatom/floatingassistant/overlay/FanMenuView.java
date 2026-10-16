package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.IntConsumer;

/**
 * 悬浮球扇形工具菜单（提案 §10 M0）：以球心为原点、向屏幕内展开 180° 半圆，
 * 由**两个同心圆**（内圈枢纽 + 外圈外沿）界定的半圆环，被 {@code 180/N} 度均分成 N 个
 * **楔形分区**（每个工具一格，自上而下 = 注册顺序）。
 *
 * <p>选择按**触点相对球心的方位角**决定（不做逐图标包围盒命中）：纯点按即按落点角度选；
 * 也可按住拖动，拖动中实时高亮当前扇区、松手提交。点中心枢纽（球）或环带外 = 关闭。
 *
 * <p>本视图被放在一个**矩形** overlay 窗里，球心对齐到窗左边缘中点；左边缘时左半出屏不接收触摸，只留右半环带。
 */
public final class FanMenuView extends View {

    private static final float SWEEP_DEG = 180f;

    // 几何常量（dp）——菜单窗尺寸须与之同步，故对外暴露 menuWidthPx/menuHeightPx。
    private static final float INNER_DP = 30f;     // 内圈枢纽（= 球心关闭区）半径，覆盖 48dp 球
    private static final float OUTER_DP = 150f;    // 外圈扇形外沿半径
    private static final float OUTER_PAD_DP = 6f;  // 有效触摸外沿余量

    /**
     * 菜单窗宽度（px）= 外沿半径 + 余量：有效扇区全在球心右侧（φ∈[0,180] ⇒ dx≥0），
     * 故窗只需从球心向右铺 outerR，球心对齐到窗**左边缘中点**（cx=0, cy=高/2），无需负偏移。
     */
    public static int menuWidthPx(Context context) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                OUTER_DP + OUTER_PAD_DP, context.getResources().getDisplayMetrics());
    }

    /** 菜单窗高度（px）= 2×外沿半径（上下各铺到 chip 外沿）。 */
    public static int menuHeightPx(Context context) {
        return 2 * menuWidthPx(context);
    }

    private final String[] labels;
    private final int n;
    private final IntConsumer onSelect;
    private final Runnable onDismiss;

    private final float innerR;   // 内圈枢纽半径（触点更近即关闭）
    private final float outerR;   // 外圈外沿半径（触点更远即关闭）
    private final float midR;     // 标签所在环半径 = 内外圈中线

    private final Paint wedgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedgeActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outerRect = new RectF();
    private final RectF innerRect = new RectF();
    private final Path sectorPath = new Path();

    private float cx;
    private float cy;
    private float sector;         // 每扇区角度 = 180 / n
    private int activeIndex = -1; // 拖动/点按当前高亮扇区，-1 = 无

    public FanMenuView(Context context, String[] labels, IntConsumer onSelect, Runnable onDismiss) {
        super(context);
        this.labels = labels;
        this.n = labels.length;
        this.onSelect = onSelect;
        this.onDismiss = onDismiss;
        this.sector = n > 0 ? SWEEP_DEG / n : SWEEP_DEG;

        this.innerR = dp(INNER_DP);
        this.outerR = dp(OUTER_DP);
        this.midR = (this.innerR + this.outerR) / 2f;

        wedgePaint.setStyle(Paint.Style.FILL);
        wedgePaint.setColor(Color.parseColor("#D96750A4")); // 实心楔形（MD3 主色，略透）
        wedgeActivePaint.setStyle(Paint.Style.FILL);
        wedgeActivePaint.setColor(Color.parseColor("#F0A184E0")); // 高亮楔形（更亮）
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        ringPaint.setColor(Color.parseColor("#80FFFFFF"));
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(dp(1f));
        dividerPaint.setColor(Color.parseColor("#55FFFFFF"));
        labelTextPaint.setStyle(Paint.Style.FILL);
        labelTextPaint.setColor(Color.WHITE);
        labelTextPaint.setTextAlign(Paint.Align.CENTER);
        labelTextActivePaint.set(labelTextPaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = 0f;        // 球心贴窗左边缘（环带全在右侧）
        cy = h / 2f;    // 上下居中
    }

    /** 触点 → 扇区索引；落在枢纽内圈、环带外或左半（phi 出 [0,180]）返回 -1。 */
    private int indexAt(float px, float py) {
        float dx = px - cx;
        float dy = py - cy;
        float r = (float) Math.hypot(dx, dy);
        if (r < innerR || r > outerR) {
            return -1;
        }
        double phi = Math.toDegrees(Math.atan2(dx, -dy)); // 上=0，顺时针到右=90，到下=180
        if (phi < 0 || phi > SWEEP_DEG) {
            return -1;
        }
        int idx = (int) Math.floor(phi / sector);
        return Math.max(0, Math.min(n - 1, idx));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeIndex = indexAt(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                activeIndex = indexAt(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int idx = indexAt(e.getX(), e.getY());
                activeIndex = -1;
                invalidate();
                if (idx >= 0) {
                    onSelect.accept(idx);
                } else {
                    onDismiss.run(); // 枢纽（球）或环带外 → 关闭
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                activeIndex = -1;
                invalidate();
                onDismiss.run();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (n == 0) {
            return;
        }
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);

        // 1) 逐个楔形分区（两同心圆之间的半圆环，按 180/N 均分）填充
        for (int k = 0; k < n; k++) {
            drawWedge(canvas, k, k == activeIndex);
        }

        // 2) 两个同心圆轮廓（内圈枢纽 + 外圈外沿）——仅此两圈
        canvas.drawCircle(cx, cy, innerR, ringPaint);
        canvas.drawCircle(cx, cy, outerR, ringPaint);

        // 3) 楔形之间的径向分割线（沿每个分区边界，从内圈到外圈）
        for (int k = 0; k <= n; k++) {
            double phiB = Math.toRadians(k * sector);
            float sx = (float) (cx + innerR * Math.sin(phiB));
            float sy = (float) (cy - innerR * Math.cos(phiB));
            float ex = (float) (cx + outerR * Math.sin(phiB));
            float ey = (float) (cy - outerR * Math.cos(phiB));
            canvas.drawLine(sx, sy, ex, ey, dividerPaint);
        }

        // 4) 每个楔形的标签：摆在该分区中心角、内外圈中线处
        for (int k = 0; k < n; k++) {
            double phiC = Math.toRadians((k + 0.5f) * sector);
            float lx = (float) (cx + midR * Math.sin(phiC));
            float ly = (float) (cy - midR * Math.cos(phiC));
            drawLabel(canvas, labels[k], lx, ly, k == activeIndex);
        }
    }

    /** 画第 k 个楔形：外弧 a0→a0+sector，折回内弧，闭合；高亮格用亮色。 */
    private void drawWedge(Canvas canvas, int k, boolean active) {
        float a0 = k * sector - 90f; // Android 角：东=0 顺时针，减 90 使 0°=正上方
        sectorPath.reset();
        sectorPath.arcTo(outerRect, a0, sector);
        sectorPath.arcTo(innerRect, a0 + sector, -sector);
        sectorPath.close();
        canvas.drawPath(sectorPath, active ? wedgeActivePaint : wedgePaint);
    }

    /** 把标签单行居中画进楔形，字号自动缩到放得进该格可用宽度。 */
    private void drawLabel(Canvas canvas, String text, float lx, float ly, boolean active) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Paint p = active ? labelTextActivePaint : labelTextPaint;
        // 可用宽 = 取「弦长（角宽）」与「径向厚度」中较小者，留边距
        float chord = 2f * midR * (float) Math.sin(Math.toRadians(sector / 2f));
        float radial = outerR - innerR;
        float maxW = Math.min(chord, radial) - dp(10);
        p.setTextSize(sp(14));
        float wpx = p.measureText(text);
        if (wpx > maxW) {
            p.setTextSize(p.getTextSize() * maxW / wpx);
        }
        float baseline = ly - (p.ascent() + p.descent()) / 2f;
        canvas.drawText(text, lx, baseline, p);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
