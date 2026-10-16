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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 悬浮球同心环工具菜单（提案 §10 M0 + #68 径向树）：以球心为原点、向屏幕内展开 180° 半圆，
 * 由**同心圆**界定的若干**半圆环**，每环被 {@code 180/该页条目数} 度均分成若干**楔形分区**（一格一工具）。
 *
 * <p>环数 = 已展开页数（嵌套深度）：最内环 = 第 1 页（顶层工具），向外逐层挂子页，**父环保留可见**
 * （由球心向外 = 选择轨迹）。条目两态：叶子=触发功能、容器=向外再加画一圈同心环展开其子项。
 * 触点按**相对球心的方位**决定：落某环某楔形即选中该格；落最内枢纽（球）= 返回上一层（回根层再点=关闭）；
 * 落最外环之外或左半 = 关闭整个菜单。可点按即选，也可按住拖动实时高亮、松手提交。
 *
 * <p>本视图放在一个**矩形** overlay 窗里，球心对齐到窗左边缘中点（cx=0, cy=高/2）；左边缘时左半出屏不接收触摸，只留右半环带。
 * 外沿半径**按内容自适应**（{@link #fitOuterPx}）：基准 {@code OUTER_BASE_DP} 起步，某格固定字号
 * {@code LABEL_TEXT_SP} 标签放不下时逐步加宽（封顶 {@code OUTER_MAX_DP}），窗尺寸随之联动；最外环外留
 * {@code CLOSING_GAP_DP}「关闭环带」（点此区=关闭）。浅内容时外沿=基准，深度=1 与旧单页扇形逐像素一致（回归基线）。
 */
public final class FanMenuView extends View {

    private static final float SWEEP_DEG = 180f;

    // 几何常量（dp）。外沿半径按内容自适应（见 fitOuterPx）：基准起步、放不下则加宽、封顶为止。
    private static final float INNER_DP = 30f;       // 最内枢纽（= 球心返回/关闭区）半径，覆盖 48dp 球
    private static final float OUTER_BASE_DP = 150f; // 外沿半径基准（下限）；内容挤时向上加宽
    private static final float OUTER_MAX_DP = 240f;  // 外沿半径上限（防超大窗）
    private static final float GROWTH_STEP_DP = 4f;  // 自适应加宽的迭代步长
    private static final float CLOSING_GAP_DP = 28f; // 最外环之外的「关闭环带」宽度（点此区=关闭；须足够厚才点得到）
    private static final float LABEL_PAD_DP = 10f;   // 标签四周留白（参与 fit 判据，与 drawLabel 一致）
    private static final int LABEL_TEXT_SP = 9;      // 标签统一固定字号（不再动态缩字；放不下只靠自适应加宽圆）
    private static final int FULL_RING_COUNT = 2;    // 仅最外两圈显示完整标签；更内的轨迹环缩为「首字+省略号」

    /** 一条菜单项（视图层不依赖 Tool，解耦）：文字 + 是否容器（容器带向右小箭头提示可展开）。 */
    public static final class Entry {
        public final String label;
        public final boolean container;

        public Entry(String label, boolean container) {
            this.label = label;
            this.container = container;
        }
    }

    /** 一页 = 一个同心环上的有序条目（自上而下 = 注册顺序）。 */
    public static final class Page {
        public final List<Entry> entries;

        public Page(List<Entry> entries) {
            this.entries = entries;
        }

        int size() {
            return entries.size();
        }
    }

    /** 选择回调（均在主线程、触摸事件中触发）。 */
    public interface Listener {
        /** 点/拖到第 {@code depth} 环（0=最内）第 {@code index} 格。 */
        void onEntry(int depth, int index);

        /** 点最内枢纽（球心）：服务据此 pop 一层或关闭。 */
        void onHub();

        /** 点最外环之外 / 左半：服务据此关闭整个菜单。 */
        void onOutside();
    }

    /** 该环是否「完整显示标签」：仅最外 {@link #FULL_RING_COUNT} 圈完整，更内的轨迹环缩为首字+省略号。 */
    private static boolean isFullRing(int ring, int d) {
        return ring >= d - FULL_RING_COUNT;
    }

    /**
     * 一条菜单项的显示文本：完整环显示全 {@code label}（容器带「 ▸」）；非完整环（更内的轨迹环）
     * 只显示**首字 + 「…」**、无箭头。{@link #fitOuterPx}/{@link #allFit} 与 {@link #drawLabel} 共用此判据。
     */
    private static String displayText(Entry en, boolean full) {
        String t = en.label == null ? "" : en.label;
        if (!full) {
            return t.isEmpty() ? "…" : t.substring(0, 1) + "…";
        }
        return en.container ? t + " ▸" : t;
    }

    /**
     * 按内容自适应求外沿半径(px)：从 {@code OUTER_BASE_DP} 起，逐格检查标签在固定字号 {@code LABEL_TEXT_SP}
     * 下能否放得进「楔形可用宽 = min(弦长, 径向厚度) - LABEL_PAD」；放不下就 +步长加宽，
     * 直到全部放得下或封顶 {@code OUTER_MAX_DP}。外沿增大⇒各环径向厚度与弦长同步增大⇒可用宽单调增，迭代必收敛。
     */
    private static float fitOuterPx(Context context, List<Page> stack) {
        float innerPx = dp(context, INNER_DP);
        float basePx = dp(context, OUTER_BASE_DP);
        float maxPx = dp(context, OUTER_MAX_DP);
        float stepPx = dp(context, GROWTH_STEP_DP);
        float padPx = dp(context, LABEL_PAD_DP);
        int d = stack == null ? 0 : stack.size();
        if (d <= 0) {
            return basePx;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sp(context, LABEL_TEXT_SP));
        for (float outer = basePx; ; outer += stepPx) {
            float th = (outer - innerPx) / d;
            if (allFit(stack, innerPx, th, p, padPx) || outer >= maxPx) {
                return Math.min(outer, maxPx);
            }
        }
    }

    /** 在给定径向厚度 th 下，所有环所有格的标签能否放得进最小字号宽度（可用宽 = min(弦长, th) - pad）。 */
    private static boolean allFit(List<Page> stack, float innerPx, float th, Paint p, float padPx) {
        int d = stack.size();
        for (int ring = 0; ring < d; ring++) {
            Page page = stack.get(ring);
            int size = page.size();
            if (size <= 0) {
                continue;
            }
            boolean full = isFullRing(ring, d);
            float sector = SWEEP_DEG / size;
            float ri = innerPx + ring * th;
            float midR = ri + th / 2f;
            float chord = 2f * midR * (float) Math.sin(Math.toRadians(sector / 2f));
            float maxW = Math.min(chord, th) - padPx;
            for (int k = 0; k < size; k++) {
                if (p.measureText(displayText(page.entries.get(k), full)) > maxW) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 菜单窗宽度(px) = 自适应外沿半径 + 环外关闭余量。有效扇区全在球心右侧（φ∈[0,180] ⇒ dx≥0），
     * 故窗只需从球心向右铺；最外环外沿到窗边之间是 {@code CLOSING_GAP_DP}「关闭环带」（点此=关闭）。
     */
    public static int measureWidthPx(Context context, List<Page> stack) {
        return (int) Math.ceil(fitOuterPx(context, stack) + dp(context, CLOSING_GAP_DP));
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    private final Listener listener;
    private List<Page> stack = new ArrayList<>(); // 由内向外：stack.get(0)=最内环=第1页

    private final float innerR;
    private final float outerR;

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
    private int activeDepth = -1; // 拖动/点按当前高亮环，-1 = 无
    private int activeIndex = -1; // 当前高亮格，-1 = 无

    public FanMenuView(Context context, List<Page> stack, Listener listener) {
        super(context);
        this.listener = listener;
        this.stack = stack == null ? new ArrayList<>() : new ArrayList<>(stack);

        this.innerR = dp(INNER_DP);
        this.outerR = fitOuterPx(context, this.stack);

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

    /** 每环径向厚度 = (outerR-innerR)/环数。环数=0 时返回 0。 */
    private float ringThickness() {
        int d = stack.size();
        return d <= 0 ? 0f : (outerR - innerR) / d;
    }

    /** 触点命中结果：环内某格返回 {depth,index}；枢纽返回 {-1,-1}（hub）；环外返回 {-2,-2}（outside）。 */
    private int[] hitAt(float px, float py) {
        float dx = px - cx;
        float dy = py - cy;
        float r = (float) Math.hypot(dx, dy);
        if (r < innerR) {
            return new int[]{-1, -1}; // 枢纽
        }
        double phi = Math.toDegrees(Math.atan2(dx, -dy)); // 上=0，顺时针到右=90，到下=180
        if (phi < 0 || phi > SWEEP_DEG) {
            return new int[]{-2, -2}; // 左半
        }
        int d = stack.size();
        float th = ringThickness();
        for (int ring = 0; ring < d; ring++) {
            float ri = innerR + ring * th;
            float ro = innerR + (ring + 1) * th;
            boolean inBand = (ring == d - 1) ? (r >= ri && r <= ro) : (r >= ri && r < ro);
            if (!inBand) {
                continue;
            }
            int size = stack.get(ring).size();
            if (size <= 0) {
                return new int[]{-2, -2};
            }
            float sector = SWEEP_DEG / size;
            int idx = (int) Math.floor(phi / sector);
            idx = Math.max(0, Math.min(size - 1, idx));
            return new int[]{ring, idx};
        }
        return new int[]{-2, -2}; // 最外环之外
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                int[] h = hitAt(e.getX(), e.getY());
                activeDepth = h[0] >= 0 ? h[0] : -1;
                activeIndex = h[1] >= 0 ? h[1] : -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int[] h = hitAt(e.getX(), e.getY());
                activeDepth = -1;
                activeIndex = -1;
                invalidate();
                if (h[0] >= 0) {
                    listener.onEntry(h[0], h[1]);
                } else if (h[0] == -1) {
                    listener.onHub();
                } else {
                    listener.onOutside();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                activeDepth = -1;
                activeIndex = -1;
                invalidate();
                listener.onOutside();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int d = stack.size();
        if (d == 0) {
            return;
        }
        float th = ringThickness();

        // 1) 每环的楔形分区填充（含高亮）
        for (int ring = 0; ring < d; ring++) {
            Page page = stack.get(ring);
            int size = page.size();
            if (size <= 0) {
                continue;
            }
            float ri = innerR + ring * th;
            float ro = innerR + (ring + 1) * th;
            float sector = SWEEP_DEG / size;
            for (int k = 0; k < size; k++) {
                drawWedge(canvas, ri, ro, k * sector, sector, ring == activeDepth && k == activeIndex);
            }
        }

        // 2) 同心圆轮廓：最内枢纽 + 每环外沿（深度=1 → 恰两圈，与旧单页一致）
        canvas.drawCircle(cx, cy, innerR, ringPaint);
        for (int ring = 0; ring < d; ring++) {
            canvas.drawCircle(cx, cy, innerR + (ring + 1) * th, ringPaint);
        }

        // 3) 每环楔形之间的径向分割线
        for (int ring = 0; ring < d; ring++) {
            int size = stack.get(ring).size();
            if (size <= 0) {
                continue;
            }
            float ri = innerR + ring * th;
            float ro = innerR + (ring + 1) * th;
            float sector = SWEEP_DEG / size;
            for (int k = 0; k <= size; k++) {
                double phiB = Math.toRadians(k * sector);
                float sx = (float) (cx + ri * Math.sin(phiB));
                float sy = (float) (cy - ri * Math.cos(phiB));
                float ex = (float) (cx + ro * Math.sin(phiB));
                float ey = (float) (cy - ro * Math.cos(phiB));
                canvas.drawLine(sx, sy, ex, ey, dividerPaint);
            }
        }

        // 4) 每格标签：摆在该分区中心角、该环中线处
        for (int ring = 0; ring < d; ring++) {
            Page page = stack.get(ring);
            int size = page.size();
            if (size <= 0) {
                continue;
            }
            float ri = innerR + ring * th;
            float ro = innerR + (ring + 1) * th;
            float midR = (ri + ro) / 2f;
            float sector = SWEEP_DEG / size;
            boolean full = isFullRing(ring, d);
            for (int k = 0; k < size; k++) {
                Entry en = page.entries.get(k);
                double phiC = Math.toRadians((k + 0.5f) * sector);
                float lx = (float) (cx + midR * Math.sin(phiC));
                float ly = (float) (cy - midR * Math.cos(phiC));
                drawLabel(canvas, en, lx, ly, midR, ro - ri, sector, full, ring == activeDepth && k == activeIndex);
            }
        }
    }

    /** 画一格环形楔形：外弧 a0→a0+sector，折回内弧，闭合；高亮格用亮色。 */
    private void drawWedge(Canvas canvas, float ri, float ro, float a0deg, float sector, boolean active) {
        outerRect.set(cx - ro, cy - ro, cx + ro, cy + ro);
        innerRect.set(cx - ri, cy - ri, cx + ri, cy + ri);
        float a0 = a0deg - 90f; // Android 角：东=0 顺时针，减 90 使 0°=正上方
        sectorPath.reset();
        sectorPath.arcTo(outerRect, a0, sector);
        sectorPath.arcTo(innerRect, a0 + sector, -sector);
        sectorPath.close();
        canvas.drawPath(sectorPath, active ? wedgeActivePaint : wedgePaint);
    }

    /** 把标签单行居中画进楔形：固定字号（不缩字）；仅当达圆上限仍放不下时按可用宽截断加省略号兜底。 */
    private void drawLabel(Canvas canvas, Entry en, float lx, float ly, float midR, float radial,
                           float sector, boolean full, boolean active) {
        String text = displayText(en, full);
        if (text.isEmpty()) {
            return;
        }
        Paint p = active ? labelTextActivePaint : labelTextPaint;
        // 可用宽 = 取「弦长（角宽）」与「径向厚度」中较小者，留边距（与 fitOuterPx 判据逐字一致）
        float chord = 2f * midR * (float) Math.sin(Math.toRadians(sector / 2f));
        float maxW = Math.min(chord, radial) - dp(LABEL_PAD_DP);
        p.setTextSize(sp(LABEL_TEXT_SP));
        if (p.measureText(text) > maxW && maxW > 0) {
            text = ellipsize(p, text, maxW);
        }
        float baseline = ly - (p.ascent() + p.descent()) / 2f;
        canvas.drawText(text, lx, baseline, p);
    }

    /** 把 text 逐字截到 measureText <= maxW，末尾以「…」占位（圆封顶仍放不下时的兜底，正常路径不触发）。 */
    private static String ellipsize(Paint p, String text, float maxW) {
        int n = text.length();
        while (n > 0 && p.measureText(text, 0, n) + p.measureText("…") > maxW) {
            n--;
        }
        return n <= 0 ? "…" : text.substring(0, n) + "…";
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    /** 只读页面栈快照（调试/测试用）。 */
    public List<Page> currentStack() {
        return Collections.unmodifiableList(stack);
    }
}
