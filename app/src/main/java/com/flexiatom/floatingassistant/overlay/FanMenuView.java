package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.flexiatom.floatingassistant.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * **悬浮球本体 + 它的同心环工具菜单**（提案 §10 M0 + #68 径向树）：一个视图同时画球与扇形，
 * 球即**环 N=0**（枢纽）；扇形以球心为原点、向屏幕内展开 180° 半圆，
 * 由**同心圆**界定的若干**半圆环**，每环被 {@code 180/该页条目数} 度均分成若干**楔形分区**（一格一工具）。
 *
 * <p>球**不是**另一个子视图（用户 2026-10-18：「就不应该分两个，球是 N=0」）。两子视图＝两个触摸归属：
 * 收起扇形的那趟 {@code removeView} 会向当时的触摸目标同步派发 {@code ACTION_CANCEL}，其回调再入
 * {@link OverlayManager} 改子视图树，真机上出现过「窗还在、子里没球」＝收起后悬浮球凭空消失（卡片 3b）。
 * 合成一个视图后，开合菜单只是同一视图换绘制内容，派发途中没有任何可摘的子视图 ⇒ 那类洞**结构性不存在**，
 * 不靠守卫兜。
 *
 * <p>环数 = 已展开页数（嵌套深度）：最内环 = 第 1 页（顶层工具），向外逐层挂子页，**父环保留可见**
 * （由球心向外 = 选择轨迹）。条目两态：叶子=触发功能、容器=向外再加画一圈同心环展开其子项。
 * 触点按**相对球心的方位**决定：落某环某楔形即选中该格；落最内枢纽（球）= 返回上一层（回根层再点=关闭）；
 * 落最外环之外或背侧半圆 = 关闭整个菜单。可点按即选，也可按住拖动实时高亮、松手提交。
 *
 * <p>本视图放在一个**矩形** overlay 窗里，窗尺寸随菜单开合由 {@link OverlayManager} 换算：
 * **收起**时窗就是球那么大、球心＝窗心；**展开**时窗为扇形外接盒，向右侧展开球心对齐窗**左**边缘中点，
 * 向左侧展开（{@code facingLeft}，球被拖到屏幕右半边时）则球心对齐窗**右**边缘中点——窗内的绘制锚点
 * 由 {@link #centerFor(int)} 现算，不缓存。
 * <p>**命中判定不走窗内坐标**：{@link #hitAtScreen} 拿屏幕坐标（{@code getRawX/getRawY}）对球的屏幕圆心
 * 算方位，理由与实测数字见那里的注释（本机开合后送进来的窗内坐标带滞后原点的残差，实测最大 236px）。
 * 窗几何只决定"哪些触摸会分派到这扇窗"。
 * 出屏的那一半不接收触摸，只留环带那一侧。角度一律以「正上方」为 0°、向**开口侧**增大到 180°。
 * **每环宽度按内容独立解算**（{@link #fitRingWidths}）：第 k 环只量**自己页内最长的标签**在固定字号
 * {@code LABEL_TEXT_SP} 下所需宽度，与他环无关、也不凑任何统一尺寸；总外沿仅受上限 {@code OUTER_MAX_DP} 约束，
 * 触顶时退化为均分并由标签省略号兜底；窗尺寸随总外沿联动。最外环外留 {@code CLOSING_GAP_DP}「关闭环带」（点此区=关闭）。
 * 故菜单整体尺寸随内容深浅与文字长短变化（不再有固定基准外沿）。
 */
public final class FanMenuView extends View {

    private static final float SWEEP_DEG = 180f;

    // 几何常量（dp）。每环宽度按**该环自己页内最长标签**逐环解算（见 fitRingWidths）；总外沿只受上限约束，无基准保底。
    private static final float BALL_DP = 48f;        // 球（= 环 N=0 的枢纽本体）直径，也是收起时窗的边长
    private static final float INNER_DP = 30f;       // 最内枢纽（= 球心返回/关闭区）半径，包住 48dp 球还留一圈可点边
    private static final float OUTER_MAX_DP = 240f;  // 外沿半径上限（防超大窗）
    private static final float GROWTH_STEP_DP = 2f;  // 逐环加宽的迭代步长（越小越贴合文字）
    private static final float CLOSING_GAP_DP = 28f; // 最外环之外的「关闭环带」宽度（点此区=关闭；须足够厚才点得到）
    private static final float LABEL_PAD_DP = 4f;    // 标签到环边的留白（参与 fit 判据，与 drawLabel 一致）
    private static final int LABEL_TEXT_SP = 9;      // 标签统一固定字号（不再动态缩字；放不下只靠自适应加宽圆）
    private static final int FULL_RING_COUNT = 2;    // 仅最外两圈显示完整标签；更内的轨迹环缩为「首字+省略号」
    private static final float DRAG_SLOP_DP = 8f;    // 按在球上位移不超过它=点按，超过=拖窗（#68-T9 实测阈值）

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

        /** 点最内枢纽（球体外那一圈，或菜单收起时球体自身的角）：服务据此 pop 一层或关闭。 */
        void onHub();

        /** 点最外环之外 / 背侧半圆：服务据此关闭整个菜单。 */
        void onOutside();
    }

    /**
     * 球体（环 N=0）被按住时的手势回调，交给 {@link OverlayManager}——它才是窗原点的持有者，
     * 本视图只负责判定「这一下是按在球上」并报告相对按下处的位移。
     */
    public interface BallGesture {
        /** 手指按在球体上（记下此刻的球屏幕位置，作为拖曳起点）。 */
        void onBallDragStart();

        /** 按在球体上且位移已超 slop：报告累计位移(px)，服务据此移动窗原点（扇形同窗，必跟着走）。 */
        void onBallDrag(float dxPx, float dyPx);

        /** 拖曳结束（松手/取消）：服务据此决定要不要翻开口朝向。 */
        void onBallDragEnd();

        /** 按在球体上但**没**拖开：菜单收起时＝请服务展开菜单（球是唯一的入口）。 */
        void onBallTap();
    }

    /** 该环是否「完整显示标签」：仅最外 {@link #FULL_RING_COUNT} 圈完整，更内的轨迹环缩为首字+省略号。 */
    private static boolean isFullRing(int ring, int d) {
        return ring >= d - FULL_RING_COUNT;
    }

    /**
     * 一条菜单项的显示文本：完整环显示全 {@code label}（容器带「 ▸」）；非完整环（更内的轨迹环）
     * 只显示**首字 + 「…」**、无箭头。{@link #fitRingWidths} 与 {@link #drawLabel} 共用此判据。
     */
    private static String displayText(Entry en, boolean full) {
        String t = en.label == null ? "" : en.label;
        if (!full) {
            return t.isEmpty() ? "…" : t.substring(0, 1) + "…";
        }
        return en.container ? t + " ▸" : t;
    }

    /**
     * 按内容自适应求外沿半径(px) = 最内枢纽 + 各环自适应宽度之和（逐环解算见 {@link #fitRingWidths}，
     * 封顶 {@code OUTER_MAX_DP}）。{@link #measureWidthPx} 与构造共用此解，保证窗尺寸与绘制边界一致。
     */
    private static float fitOuterPx(Context context, List<Page> stack) {
        float innerPx = dp(context, INNER_DP);
        float maxPx = dp(context, OUTER_MAX_DP);
        float stepPx = dp(context, GROWTH_STEP_DP);
        float padPx = dp(context, LABEL_PAD_DP);
        if (stack == null || stack.isEmpty()) {
            return innerPx;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sp(context, LABEL_TEXT_SP));
        float[] widths = fitRingWidths(stack, innerPx, maxPx, stepPx, padPx, p);
        float total = 0f;
        for (float w : widths) {
            total += w;
        }
        return Math.min(innerPx + total, maxPx);
    }

    /**
     * 逐环求宽度：每环**只按自己页内最长的标签**取宽，与他环无关，也不凑任何统一尺寸；
     * 从步长起逐环生长到放得下（内环先定稿：加宽只外推更外环的内边界，不会破坏已满足的环）。
     * 总带宽封顶 {@code maxPx - innerPx}：若按各自需求排不下，剩余空间均分给尚未放得下的环（退化），
     * 极端时各环均分，由 drawLabel 的省略号兜底。
     * 判据与 {@link #drawLabel} 一致：可用宽 = min(弦长, 本环宽) - pad，弦长 = 2·midR·sin(扇区/2)。
     */
    private static float[] fitRingWidths(List<Page> stack, float innerPx, float maxPx,
                                         float stepPx, float padPx, Paint p) {
        int d = stack.size();
        float[] w = new float[d];
        boolean[] settled = new boolean[d];
        float budget = Math.max(0f, maxPx - innerPx);
        for (int i = 0; i < d; i++) {
            w[i] = stepPx;
        }
        final int maxIter = (int) Math.ceil(budget / stepPx) + d + 2;
        for (int iter = 0; iter < maxIter; iter++) {
            // 按当前累计边界测每环是否放得下
            boolean[] fits = new boolean[d];
            boolean allFit = true;
            float sum = 0f;
            for (int k = 0; k < d; k++) {
                Page page = stack.get(k);
                int size = page.size();
                if (size <= 0) {
                    fits[k] = true;
                    settled[k] = true;
                } else {
                    float ri = innerPx + sum;
                    float midR = ri + w[k] / 2f;
                    float sector = SWEEP_DEG / size;
                    float chord = 2f * midR * (float) Math.sin(Math.toRadians(sector / 2f));
                    float maxW = Math.min(chord, w[k]) - padPx;
                    boolean full = isFullRing(k, d);
                    boolean ok = true;
                    for (int i2 = 0; i2 < size; i2++) {
                        if (p.measureText(displayText(page.entries.get(i2), full)) > maxW) {
                            ok = false;
                            break;
                        }
                    }
                    fits[k] = ok;
                    if (ok) {
                        settled[k] = true;
                    }
                    allFit &= ok;
                }
                sum += w[k];
            }
            float total = sum;
            if (allFit) {
                return w;
            }
            boolean grew = false;
            for (int k = 0; k < d; k++) {
                if (!fits[k] && total + stepPx <= budget + 1f) {
                    w[k] += stepPx;
                    total += stepPx;
                    grew = true;
                }
            }
            if (!grew) {
                // 触顶：已定宽的环保留其解，剩余带宽在其余环之间均分
                float used = 0f;
                int rest = 0;
                for (int k = 0; k < d; k++) {
                    if (settled[k]) {
                        used += w[k];
                    } else {
                        rest++;
                    }
                }
                float share = rest > 0 ? Math.max(stepPx, (budget - used) / rest) : 0f;
                for (int k = 0; k < d; k++) {
                    if (!settled[k]) {
                        w[k] = share;
                    }
                }
                return w;
            }
        }
        return w;
    }

    /**
     * 供构造使用：按 content 解出每环宽度，并把它们**整体缩放**到恰好铺满 {@code [innerR, outerR]}
     * （触顶时 {@link #fitOuterPx} 的钳位会砍掉超额部分，这里等比回缩保持边界一致）。
     */
    private static float[] solveRingWidths(Context context, List<Page> stack, float outerR) {
        int d = stack.size();
        float[] out = new float[d];
        if (d == 0) {
            return out;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sp(context, LABEL_TEXT_SP));
        float[] w = fitRingWidths(stack, dp(context, INNER_DP),
                dp(context, OUTER_MAX_DP), dp(context, GROWTH_STEP_DP), dp(context, LABEL_PAD_DP), p);
        float total = 0f;
        for (float x : w) {
            total += x;
        }
        float avail = Math.max(0f, outerR - dp(context, INNER_DP));
        float scale = total > 0f ? Math.min(1f, avail / total) : 0f;
        for (int k = 0; k < d; k++) {
            out[k] = w[k] * scale;
        }
        return out;
    }

    /** 球（= 环 N=0）的直径(px)：收起时窗的边长，也是拖曳钳位用的球尺寸。 */
    public static int ballSizePx(Context context) {
        return (int) dp(context, BALL_DP);
    }

    /** 球体半径(px)：按下落在它之内就算「按在球上」（可拖、可点），与窗大小无关。 */
    public static float ballRadiusPx(Context context) {
        return dp(context, BALL_DP) / 2f;
    }

    /**
     * N=0 层级的**命中**半径(px)＝最内枢纽半径：球体＋外圈一圈都算 N=0（点＝返回），所以它比
     * {@link #ballRadiusPx} 大。展开时窗在背侧必须留满这个半径，否则背侧那一圈落在窗 surface
     * 之外，N=0 的触摸目标左右不对称（画得出、点不到）。
     */
    public static float hubRadiusPx(Context context) {
        return dp(context, INNER_DP);
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

    private final BallGesture ballGesture;
    /** 服务侧选择回调，仅菜单展开期间非空：收起时球体只走 {@link BallGesture}（点=展开）。 */
    private Listener listener;
    private List<Page> stack = new ArrayList<>(); // 由内向外：stack.get(0)=最内环=第1页
    /** 开口朝向：false=向右（球心贴窗左边）、true=向左（球心贴窗右边）。球被拖到屏幕右半边时镜像。 */
    private boolean facingLeft;
    /** 角度增长方向：与 {@link #facingLeft} 对应，φ 从正上方向开口侧增大到 180°。 */
    private int dir = 1;

    private final float innerR;
    private final float ballR;
    private float outerR;
    /** 每环的径向宽度（由内容逐环解算，见 {@link #fitRingWidths}），环边界 = innerR + 前缀和。 */
    private float[] ringWidths = new float[0];

    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedgeActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outerRect = new RectF();
    private final RectF innerRect = new RectF();
    private final Path sectorPath = new Path();

    /** 按在球体上时的按下点（raw px）与拖曳判定：球可拖（N=0 也是入口），扇形只管高亮。 */
    private float ballDownRawX;
    private float ballDownRawY;
    private boolean pressedBall;
    private boolean ballDragging;
    private int activeDepth = -1; // 拖动/点按当前高亮环，-1 = 无
    private int activeIndex = -1; // 当前高亮格，-1 = 无

    /**
     * 球的**屏幕**圆心（px）——{@link OverlayManager#applySphereWindow} 每次推窗时给的最新真值。
     * 命中判定用它、不用 {@link #getWidth()} 换算的窗内圆心（{@code ball-tap-hits-fan-zone} 的正证）：
     * 本机实测（2026-10-20，{@code P5HIT} 216 个可对齐的 DOWN + {@code getevent} 触摸板绝对坐标做外证）
     * 触摸送进这扇窗时，**窗内坐标是按一个滞后的旧原点换算的**——{@code raw−local} 与刚推给 WMS 的
     * {@code lp.x,y} 差可达 236px（正好是收起→展开的那次 {@code Δorigin}），且手指不动、每次按下都不同。
     * 于是命中判定拿着对的圆心、错的触点，整个命中图被平移 ⇒ 可见球上落到环区。
     * {@code getRawX/getRawY} 与外证的偏差中位 **1px** ⇒ 屏幕坐标这一路是可信的，命中就改在它上面算。
     */
    private int ballTruthCx;
    private int ballTruthCy;

    /**
     * 推窗的同帧把球的屏幕圆心交给命中判定（{@code Δorigin} 那一族错位的唯一收口点）。
     *
     * <p>参数只有圆心：触点窗自己的矩形**不参与**命中算术——它只决定哪块屏幕区域把事件派进这扇窗，
     * 而派进来的事件一律先换算到屏幕坐标再解。所以这里没有 {@code lp.x/y/w/h} 可缓存，也就没有
     * 「视图布局与刚推给 WMS 的那对几何谁追上了谁」这种需要按帧去猜的东西。
     */
    void setBallCenterInScreen(int truthCx, int truthCy) {
        ballTruthCx = truthCx;
        ballTruthCy = truthCy;
    }

    public FanMenuView(Context context, BallGesture ballGesture) {
        super(context);
        this.ballGesture = ballGesture;
        this.innerR = dp(INNER_DP);
        this.ballR = dp(BALL_DP) / 2f;
        this.outerR = innerR;

        // 颜色全部取 M3 角色（md3-dynamic-color）：未选中格用 primaryContainer、高亮格翻成 primary，
        // 靠「容器↔主色」的明度反转表达选中，比原先「同一紫再亮一档」更贴 M3 的语义。
        ballPaint.setStyle(Paint.Style.FILL);
        ballPaint.setColor(M3.color(context, M3.PRIMARY));
        wedgePaint.setStyle(Paint.Style.FILL);
        wedgePaint.setColor(M3.alpha(M3.color(context, M3.PRIMARY_CONTAINER), 0.8f));
        wedgeActivePaint.setStyle(Paint.Style.FILL);
        wedgeActivePaint.setColor(M3.color(context, M3.PRIMARY));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        ringPaint.setColor(M3.alpha(M3.color(context, M3.OUTLINE), 0.7f));
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(dp(1f));
        dividerPaint.setColor(M3.alpha(M3.color(context, M3.OUTLINE_VARIANT), 0.7f));
        labelTextPaint.setStyle(Paint.Style.FILL);
        labelTextPaint.setColor(M3.color(context, M3.ON_PRIMARY_CONTAINER));
        labelTextPaint.setTextAlign(Paint.Align.CENTER);
        labelTextActivePaint.set(labelTextPaint);
        labelTextActivePaint.setColor(M3.color(context, M3.ON_PRIMARY));

        installAccessibility();
    }

    /**
     * 虚拟节点的 id：枢纽与环外各占一格，条目按页栈线性排（偏移见 {@link #entryOffset}）。
     * **球不在这套 id 里**——它就是宿主节点，读屏走进来的第一格（F4 已验的那条通路），不再复制一个自己。
     */
    private static final int VID_HUB = 0;
    private static final int VID_OUTSIDE = 1;
    private static final int VID_ENTRY_BASE = 2;

    private FanA11y a11y;
    /** {@link #getLocationOnScreen} 的复用缓冲：无障碍回调会成批要来边界，不每次新建数组。 */
    private final int[] a11yScreen = new int[2];
    private final Rect a11yBounds = new Rect();

    /**
     * 悬浮球**与扇形每一格**的无障碍语义（{@code ui-blind-operation} F4 + 卡⑩「走不进扇形那些格子」，
     * 人 2026-10-22 裁「补虚拟节点 {@code ExploreByTouchHelper}」）。
     *
     * <p>本视图全自绘、零子节点，实测（服务侧 {@code A11YPROBE}）在无障碍树里原先是
     * {@code cd=[] click=false foc=false kids=0} 的一个 View——读屏**整扇跳过**，等于视障用户连入口都摸不到；
     * 补上宿主名字后球可读可点（已结案），但环上的格子仍不在树里，于是"有哪些工具"这件事问不出来。
     *
     * <p>这条路是**加节点、不动触摸**：虚拟节点挂在同一个 View 上，没有 {@code addView}、不换窗类型、
     * 不碰 {@link #onTouchEvent}。对比之下 {@code setOnClickListener} 会把 {@code clickable} 真打开、连带改
     * 触摸事件的吃掉行为，而球上的拖曳/点按判定是 {@code ball-tap-hits-fan-zone} 一路实机调出来的，
     * 不为无障碍去动它。宿主节点（球）的"双击执行"由 {@link FanA11y#performAccessibilityAction} 接回
     * **手指按球那同一套判定**：收起＝{@link BallGesture#onBallTap()}（入口），展开＝{@link Listener#onHub()}（返回上一层）。
     *
     * <p>坐标换算只走**屏幕真值**这一路（{@link #hitAtLocal}／{@link #boundsOf} 都先减
     * {@link #getLocationOnScreen}）：命中判定的既有正证是「窗内坐标带滞后原点，残差实测最大 236px」，
     * 无障碍这条路若改用 {@link #centerX()} 换算等于把同一个坑再踩一遍。
     *
     * <p>**没解决的写明白**：① 格子是圆环扇区，虚拟节点的边界只能是矩形（{@link #arcBox} 取包围盒），
     * 读屏的高亮框会比格子大一圈，相邻格的框必然重叠——这是 {@code ExploreByTouchHelper} 的固有近似；
     * ② 环外那格的边界只框住最外环以外的关闭环带，实际还能命中的**背侧半圆**不在这个矩形里；
     * ③ {@link #getVirtualViewAt} 只服务指针悬停，TalkBack 的手指探索按各节点的**边界**寻物，
     * 所以决定"摸不摸得到"的是 {@link #boundsOf} 不是命中表；④ 念出来顺不顺仍只有人耳能判（F5）。
     */
    private void installAccessibility() {
        a11y = new FanA11y(this);
        ViewCompat.setAccessibilityDelegate(this, a11y);
    }

    /** 树里的格子数与画面上的格子数必须同步：换页/收起之后旧 id 就不存在了，得叫helper 重取。 */
    private void invalidateA11yTree() {
        if (a11y != null) {
            a11y.invalidateRoot();
        }
    }

    /**
     * 宿主节点（球）的读音名：开合两种状态、以及展开后所处层深都读出来不一样，全部在节点被要的那一刻
     * 现算，不用谁去刷它。根层的球按下去是**收整个菜单**（{@code popOrCloseMenu} 那侧 size≤1 走 hide），
     * 所以名字得跟着说关闭——说"返回上一层"会把人往一个不存在的更外层引。
     */
    private String ballDescription() {
        if (!menuOpen()) {
            return getContext().getString(R.string.ball_cd_collapsed);
        }
        return getContext().getString(stack.size() <= 1
                ? R.string.ball_cd_open_root : R.string.ball_cd_open);
    }

    /** 第 {@code ring} 环之前已排了多少格＝这一环第一格的 id 偏移。 */
    private int entryOffset(int ring) {
        int off = 0;
        for (int i = 0; i < ring && i < stack.size(); i++) {
            off += stack.get(i).size();
        }
        return off;
    }

    /** 虚拟 id → {@code {环, 环内序号}}；不是条目（或页栈已换、这个 id 掉出界）时返回 {@code null}。 */
    private int[] entryOfId(int id) {
        int t = id - VID_ENTRY_BASE;
        if (t < 0) {
            return null;
        }
        for (int ring = 0; ring < stack.size(); ring++) {
            int size = stack.get(ring).size();
            if (t < size) {
                return new int[]{ring, t};
            }
            t -= size;
        }
        return null;
    }

    /**
     * 一格读什么。条目用**完整标签**：画到弧上的「首字+…」（{@link #displayText}）只是为了塞进弦长，
     * 读屏没有理由跟着吃省略号。容器额外报「可展开」，对应画面上的「 ▸」。
     */
    private String descriptionOf(int id) {
        Context context = getContext();
        if (id == VID_HUB) {
            // 根层的枢纽按下去是**收整个菜单**（服务那侧「回根层再点=关闭」），名字得跟着说这件事，
            // 否则读屏在最外层报"返回上一层"会把人往一个不存在的更外层引
            return context.getString(stack.size() <= 1 ? R.string.fan_cd_hub_root : R.string.fan_cd_hub);
        }
        if (id == VID_OUTSIDE) {
            return context.getString(R.string.fan_cd_close);
        }
        int[] ri = entryOfId(id);
        if (ri == null) {
            return "";
        }
        Entry en = stack.get(ri[0]).entries.get(ri[1]);
        return context.getString(en.container ? R.string.fan_cd_entry_container : R.string.fan_cd_entry,
                en.label, ri[0] + 1, stack.size());
    }

    /** 悬停/局部坐标 → 命中区：先把局部点抬回屏幕坐标，再交给唯一那份命中判定 {@link #hitAtScreen}。 */
    private int[] hitAtLocal(float x, float y) {
        getLocationOnScreen(a11yScreen);
        return hitAtScreen(x + a11yScreen[0], y + a11yScreen[1]);
    }

    /**
     * 节点边界（**宿主视图的局部坐标**）：圆心＝「球屏幕真值 − 本视图在屏幕上的位置」，两边同在一个
     * 屏幕坐标系里，这个减法自己抵消掉窗原点追没追上这件事。
     */
    private void boundsOf(int id, Rect out) {
        getLocationOnScreen(a11yScreen);
        float cx = ballTruthCx - a11yScreen[0];
        float cy = ballTruthCy - a11yScreen[1];
        if (id == VID_HUB) {
            // 枢纽是**整圆**（{@link #hitAtScreen} 在角度检验之前就判 r<innerR，背侧那半边也算），
            // 所以取样角度得走满 360°：按扇形的 180° 取会把矩形切成半宽，框住球右边的弧边而非球本身
            arcBox(out, cx, cy, innerR, innerR, 0f, 360f);
        } else if (id == VID_OUTSIDE) {
            arcBox(out, cx, cy, outerR, outerR + dp(CLOSING_GAP_DP), 0f, SWEEP_DEG);
        } else if (entryOfId(id) == null) {
            out.set(0, 0, getWidth(), getHeight());
        } else {
            int[] ri = entryOfId(id);
            float sector = SWEEP_DEG / Math.max(1, stack.get(ri[0]).size());
            arcBox(out, cx, cy, ringInner(ri[0]), ringOuter(ri[0]), ri[1] * sector, (ri[1] + 1) * sector);
        }
        if (out.isEmpty()) {
            // 夹进视图尺寸后有可能整格被挤出窗外（真值圆心与视图位置暂时对不上那一两帧）。空矩形在树里
            // 等于这格**不存在**，宁可退回整扇窗——多给一格顶位，也别让人摸到"少了一格"而以为是功能没了。
            out.set(0, 0, getWidth(), getHeight());
        }
    }

    /**
     * 扇区（半径 {@code ri..ro}、角度 {@code a0..a1}，角度口径与 {@link #hitAtScreen} 同源：正上=0°、向开口侧增大）
     * 的包围盒。极值只可能出现在取样角与两端角上，16 档把误差压到 1px 内；最后夹进视图尺寸——
     * 展开态的窗是扇形外接盒，圆角之外的像素本来就不属于这扇窗。
     */
    private void arcBox(Rect out, float cx, float cy, float ri, float ro, float a0, float a1) {
        float minX = cx, minY = cy, maxX = cx, maxY = cy;
        for (int i = 0; i <= 16; i++) {
            double phi = Math.toRadians(a0 + (a1 - a0) * i / 16f);
            float sx = (float) Math.sin(phi);
            float sy = (float) Math.cos(phi);
            for (int k = 0; k < 2; k++) {
                float r = k == 0 ? ri : ro;
                float x = cx + dir * r * sx;
                float y = cy - r * sy;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        out.set(clampX(minX), clampY(minY), clampX(maxX), clampY(maxY));
    }

    private int clampX(float v) {
        return Math.max(0, Math.min(getWidth(), Math.round(v)));
    }

    private int clampY(float v) {
        return Math.max(0, Math.min(getHeight(), Math.round(v)));
    }

    /** 读屏"双击执行"落到某一格：接回既有的 {@link Listener}，不另开第二条动作通路。 */
    private void activateVirtualView(int id) {
        if (listener == null) {
            return; // 收起态没有可提交的目标（球那一路由 performAccessibilityAction 应答）
        }
        if (id == VID_HUB) {
            listener.onHub();
        } else if (id == VID_OUTSIDE) {
            listener.onOutside();
        } else {
            int[] ri = entryOfId(id);
            if (ri != null) {
                listener.onEntry(ri[0], ri[1]);
            }
        }
    }

    /**
     * 扇形的虚拟节点表：id 由本类自定（{@code VID_*}），边界由 {@link #boundsOf} 现算，动作由
     * {@link #activateVirtualView} 转给触摸那套既有回调——三条通路共用同一份 {@link #stack} 真值，
     * 所以"读到的"与"画出的"与"点得到的"不可能各说一套。
     */
    private final class FanA11y extends ExploreByTouchHelper {
        FanA11y(View host) {
            super(host);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            if (!menuOpen()) {
                return INVALID_ID; // 收起时整扇窗就是球＝宿主本身，树里没有第二格
            }
            int[] h = hitAtLocal(x, y);
            if (h[0] >= 0) {
                return VID_ENTRY_BASE + entryOffset(h[0]) + h[1];
            }
            if (h[0] == -1) {
                return VID_HUB;
            }
            if (h[0] == -2) {
                return VID_OUTSIDE;
            }
            return INVALID_ID; // ZONE_BALL
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
            if (!menuOpen()) {
                return;
            }
            virtualViewIds.add(VID_HUB);
            for (int ring = 0; ring < stack.size(); ring++) {
                int off = entryOffset(ring);
                int size = stack.get(ring).size();
                for (int i = 0; i < size; i++) {
                    virtualViewIds.add(VID_ENTRY_BASE + off + i);
                }
            }
            virtualViewIds.add(VID_OUTSIDE);
        }

        @Override
        protected void onPopulateNodeForHost(AccessibilityNodeInfoCompat info) {
            info.setClassName(Button.class.getName());
            info.setClickable(true);
            info.setContentDescription(ballDescription());
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, AccessibilityNodeInfoCompat info) {
            // 必须整只交回去：getBoundsInParent() 给的是**副本**（AccessibilityNodeInfo 内部那份 Rect
            // 不会被就地改到），照着它 set 等于没设，节点会以空边界进树、读屏摸不到任何东西
            boundsOf(id, a11yBounds);
            info.setBoundsInParent(a11yBounds);
            info.setClassName(Button.class.getName());
            info.setClickable(true);
            info.setContentDescription(descriptionOf(id));
            info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        }

        @Override
        protected void onPopulateEventForVirtualView(int id, AccessibilityEvent event) {
            // 只报"是谁被点了"，不塞正文：setText/getContent 在 AccessibilityRecord 上是 @hide，
            // android-35 桩里取不到；名字读屏自己会从节点（onPopulateNodeForVirtualView）拿。
            event.setClassName(Button.class.getName());
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle args) {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) {
                return false;
            }
            activateVirtualView(id);
            // helper 自己不发包（performAction 只是转调本方法），点了得自己说一声，否则读屏无声无息
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
            return true;
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                // 与**手指按球**同一条判定（见 onTouchEvent 的 pressedBall 分支）：收起＝入口，展开＝枢纽（返回上一层）。
                // 旧实现无条件走 onBallTap()，于是读屏在展开态点球是整个收扇，而手指点球是退一层——两条通路各说一套。
                if (menuOpen() && listener != null) {
                    listener.onHub();
                } else {
                    ballGesture.onBallTap();
                }
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }

    /**
     * 像素宿主（恒原点像素层）的重绘入口。本视图是**透明触点层**：它只判命中、不吃绘制，
     * 所以任何"画面要变了"的状态迁移（换页/高亮/收起）都必须叫宿主重绘，而不是自己的
     * {@code invalidate()}——自己重绘只会画一张空 surface。未挂宿主时退回自身（单测与旧路径）。
     */
    public interface PixelSink {
        void requestRedraw();
    }

    private PixelSink pixelSink;

    public void setPixelSink(PixelSink sink) {
        this.pixelSink = sink;
    }

    private void requestPixels() {
        PixelSink sink = pixelSink;
        if (sink != null) {
            sink.requestRedraw();
        } else {
            invalidate();
        }
    }

    /**
     * 展开/换页：换掉页栈与朝向，并按新内容重解外沿与各环宽度。
     * 同一视图内完成，没有任何 {@code addView}/{@code removeView} ⇒ 触摸派发途中不会动子视图树。
     * 窗尺寸由调用方（{@link OverlayManager}）按 {@link #measureWidthPx} 同步换算。
     */
    public void setMenu(List<Page> stack, Listener listener, boolean facingLeft) {
        this.listener = listener;
        this.stack = stack == null ? new ArrayList<>() : new ArrayList<>(stack);
        this.facingLeft = facingLeft;
        this.dir = facingLeft ? -1 : 1;
        this.outerR = fitOuterPx(getContext(), this.stack);
        this.ringWidths = solveRingWidths(getContext(), this.stack, this.outerR);
        invalidateA11yTree(); // 格子数量随页栈变，树里那份必须跟着重取
        requestPixels();
    }

    /** 收起菜单：只留球（环 N=0）。回调一并作废，收起后的球只应答 {@link BallGesture}。 */
    public void clearMenu() {
        this.listener = null;
        this.stack = new ArrayList<>();
        this.ringWidths = new float[0];
        this.outerR = innerR;
        resetTouchState();
        invalidateA11yTree();
        requestPixels();
    }

    public boolean menuOpen() {
        return !stack.isEmpty();
    }

    /**
     * 球心/扇形圆心的窗内 x(px)：**展开时圆心距背侧窗边留 N=0 的命中半径 {@code innerR}**，不是贴窗边——
     * 窗只往开口侧铺到「最外环＋关闭带」，背侧再多出 innerR。留一个球半径（视觉 48px）不够：球画全了，
     * 但背侧 48–60px 那一圈返回区仍在窗外（2026-10-18 像素实测两事——留 0px 被裁成半圆；留 48px 那圈点不到）。
     * 收起时窗即球、圆心在窗心。现算不缓存。
     *
     * <p>分母是**本窗此刻的实际宽度**，不是 {@link #menuOpen()}：WMS 先把新 frame 变成触摸的分派范围，
     * 而"菜单已清、窗还是展开宽"的那一两帧里按状态算会得到 {@code 288/2=144} 而不是展开态的 60
     * （2026-10-19 录屏实测 17/245 帧，其中 2 帧原位还透出下层 App）。
     * <p>但**命中判定已经不吃这个量了**（{@code ball-tap-hits-fan-zone}，10-20 正证：触摸送进来的窗内
     * 坐标带着一个滞后的旧原点，残差实测最大 236px）——{@link #hitAtScreen} 改用屏幕坐标对
     * {@link #ballTruthCx} 算。这一对 {@code centerX/centerY} 现在的职责是**机读判据**：
     * {@code p5_ball_state} 的 {@code concentric} 拿它跟「球屏幕真值换算回窗内的点」比，
     * 也就是"我们的窗几何与真值是否还自洽"，画错的闸仍在这里。
     */
    public int centerX() {
        return centerFor(getWidth());
    }

    /**
     * 按**给定窗宽**算圆心 x——与 {@link #centerX()} 同一条式子，只是分母由调用方给。
     * 恒原点像素层必须用它：它画的是屏幕绝对坐标，窗宽取自"刚推给 WMS 的那对几何"，
     * 而不是自己那扇全屏窗的宽度（后者恒等于屏宽，用它就把扇形画到屏外去了）。
     */
    public int centerFor(int w) {
        if (w <= ballSizePx(getContext())) {
            return w / 2; // 窗＝球
        }
        return facingLeft ? Math.round(w - innerR) : Math.round(innerR); // 窗＝扇形外接盒
    }

    /** 球心/扇形圆心的窗内 y(px)：两种状态都落在窗的竖直中线上（窗原点由球心反推，见 OverlayManager）。 */
    public int centerY() {
        return getHeight() / 2;
    }

    public int centerForY(int h) {
        return h / 2;
    }

    private void resetTouchState() {
        pressedBall = false;
        ballDragging = false;
        activeDepth = -1;
        activeIndex = -1;
    }

    /** 第 {@code k} 环的内边界（r 坐标）：innerR + 前 k 环宽度之和。d 很小（≤ 树深），直接累加。 */
    private float ringInner(int k) {
        float ri = innerR;
        for (int i = 0; i < k && i < ringWidths.length; i++) {
            ri += ringWidths[i];
        }
        return ri;
    }

    /** 第 {@code k} 环的外边界（r 坐标）。 */
    private float ringOuter(int k) {
        float ro = ringInner(k);
        return ro + (k < ringWidths.length ? ringWidths[k] : 0f);
    }

    /** 球体（环 N=0）的命中码：可拖、可点，与环/枢纽/环外并列。 */
    private static final int ZONE_BALL = -3;

    /**
     * 触点命中结果：{@code h[0] >= 0} = 第 {@code h[0]} 环第 {@code h[1]} 格；{@code -1} = 枢纽（球外圈，返回上层）；
     * {@code -2} = 环外/背侧（关闭）；{@code ZONE_BALL} = 球体本身（拖或点）。
     * <p>菜单收起时整扇窗就是球 ⇒ 任何触点都算按球（窗是方的、球是圆的，窗角也得应答，否则点角落失灵）。
     *
     * <p>**入参是屏幕坐标**（{@code MotionEvent.getRawX/getRawY}），圆心是球的屏幕真值——不是窗内坐标
     * 与 {@link #centerX()}。理由见 {@link #ballTruthCx}：本机在开合后送进来的窗内坐标带着一个滞后的
     * 旧原点（实测残差最大 236px），拿它算命中会把整个命中图平移，于是**看得见的位置点不到、
     * 看得见的球落进环区**（人报「狂点圆球有概率点到扇形」）。屏幕坐标与触摸板外证差中位 1px，
     * 且像素层本来就画在屏幕真值上 ⇒ 这一改让「画在哪」与「点在哪」真的共用同一个整数。
     * 触点落在窗的哪块矩形由 WMS 的分派决定，与本函数无关（它只回答"这一下算哪一区"）。
     */
    private int[] hitAtScreen(float sx, float sy) {
        float dx = sx - ballTruthCx;
        float dy = sy - ballTruthCy;
        float r = (float) Math.hypot(dx, dy);
        if (!menuOpen() || r <= ballR) {
            return new int[]{ZONE_BALL, -1}; // 球体（环 N=0）
        }
        if (r < innerR) {
            return new int[]{-1, -1}; // 枢纽
        }
        double phi = Math.toDegrees(Math.atan2(dir * dx, -dy)); // 上=0，向开口侧顺时针到 180
        if (phi < 0 || phi > SWEEP_DEG) {
            return new int[]{-2, -2}; // 背侧半圆
        }
        int d = stack.size();
        for (int ring = 0; ring < d; ring++) {
            float ri = ringInner(ring);
            float ro = ringOuter(ring);
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
            case MotionEvent.ACTION_DOWN: {
                int[] h = hitAtScreen(e.getRawX(), e.getRawY());
                pressedBall = h[0] == ZONE_BALL;
                if (pressedBall) {
                    ballDownRawX = e.getRawX();
                    ballDownRawY = e.getRawY();
                    ballDragging = false;
                    ballGesture.onBallDragStart(); // 记下球的起始屏幕位置，供位移钳位
                } else {
                    highlight(h);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressedBall) {
                    float dx = e.getRawX() - ballDownRawX;
                    float dy = e.getRawY() - ballDownRawY;
                    if (!ballDragging && Math.hypot(dx, dy) < dp(DRAG_SLOP_DP)) {
                        return true; // 没过 slop 仍是点按候选，不动窗
                    }
                    ballDragging = true;
                    ballGesture.onBallDrag(dx, dy);
                } else {
                    highlight(hitAtScreen(e.getRawX(), e.getRawY()));
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int[] h = hitAtScreen(e.getRawX(), e.getRawY());
                clearHighlight();
                if (pressedBall) {
                    // 按在球上松手：拖过=一次搬窗（末了才翻朝向），没拖过=点球（展开着即枢纽，收起即入口）
                    if (ballDragging) {
                        ballDragging = false;
                        ballGesture.onBallDragEnd();
                    } else if (menuOpen()) {
                        if (listener != null) {
                            listener.onHub();
                        }
                    } else {
                        ballGesture.onBallTap();
                    }
                } else if (listener == null) {
                    // 不该出现（没页栈就没得可提交）：按结构洞处理，静默
                } else if (h[0] >= 0) {
                    listener.onEntry(h[0], h[1]);
                } else if (h[0] == -1) {
                    listener.onHub();
                } else {
                    listener.onOutside();
                }
                pressedBall = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                // **不再**把取消当成「点了环外」：旧实现这里无条件 onOutside()，于是任何外部取消
                // （子视图被摘、手势被抢）都会误触发关闭，并在关闭途中再入改树＝结构洞 2。
                if (ballDragging) {
                    ballDragging = false;
                    ballGesture.onBallDragEnd();
                }
                pressedBall = false;
                clearHighlight();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void highlight(int[] h) {
        activeDepth = h[0] >= 0 ? h[0] : -1;
        activeIndex = h[1] >= 0 ? h[1] : -1;
        requestPixels();
    }

    private void clearHighlight() {
        activeDepth = -1;
        activeIndex = -1;
        requestPixels();
    }

    /**
     * 触点层不画任何像素（恒原点像素层才是唯一绘制层，见 {@link OverlayManager}）。
     * 保留这个空重写是为了让"只点不画"这件事写在类自己身上，而不是靠调用方记得别画。
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    /**
     * 把球与扇形画到**屏幕绝对坐标**上：由恒原点像素层在自己的全屏 canvas 上调用。
     *
     * <p>{@code (winX, winY, winW, winH)} 必须是调用方**刚推给 WMS 的那对触点窗几何**（唯一真值，
     * 见 {@code OverlayManager.applySphereWindow}），于是画出来的圆心 {@code = (winX + centerFor(winW),
     * winY + centerForY(winH)) = (球屏幕真值)}，同心由构造保证。命中判定现在直接锚那个真值
     * （{@link #hitAtScreen} 吃屏幕坐标），所以"画在哪"与"点在哪"共用的是**同一个球心整数**，
     * 不再依赖窗内坐标那一路换算。半径/环带/角度的算术与这里同源，没改；改的只是坐标系。
     */
    public void drawContent(Canvas canvas, int winX, int winY, int winW, int winH) {
        float cx = centerFor(winW);
        float cy = centerForY(winH);
        int d = stack.size();
        if (d == 0) {
            canvas.drawCircle(winX + cx, winY + cy, ballR, ballPaint); // 收起：只画那颗球
            return;
        }
        canvas.save();
        canvas.translate(winX, winY);

        // 1) 每环的楔形分区填充（含高亮）
        for (int ring = 0; ring < d; ring++) {
            Page page = stack.get(ring);
            int size = page.size();
            if (size <= 0) {
                continue;
            }
            float ri = ringInner(ring);
            float ro = ringOuter(ring);
            float sector = SWEEP_DEG / size;
            for (int k = 0; k < size; k++) {
                drawWedge(canvas, cx, cy, ri, ro, k * sector, sector, ring == activeDepth && k == activeIndex);
            }
        }

        // 2) 同心圆轮廓：最内枢纽 + 每环外沿（深度=1 → 恰两圈，与旧单页一致）
        canvas.drawCircle(cx, cy, innerR, ringPaint);
        for (int ring = 0; ring < d; ring++) {
            canvas.drawCircle(cx, cy, ringOuter(ring), ringPaint);
        }

        // 3) 每环楔形之间的径向分割线
        for (int ring = 0; ring < d; ring++) {
            int size = stack.get(ring).size();
            if (size <= 0) {
                continue;
            }
            float ri = ringInner(ring);
            float ro = ringOuter(ring);
            float sector = SWEEP_DEG / size;
            for (int k = 0; k <= size; k++) {
                double phiB = Math.toRadians(k * sector);
                float sx = (float) (cx + dir * ri * Math.sin(phiB));
                float sy = (float) (cy - ri * Math.cos(phiB));
                float ex = (float) (cx + dir * ro * Math.sin(phiB));
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
            float ri = ringInner(ring);
            float ro = ringOuter(ring);
            float midR = (ri + ro) / 2f;
            float sector = SWEEP_DEG / size;
            boolean full = isFullRing(ring, d);
            for (int k = 0; k < size; k++) {
                Entry en = page.entries.get(k);
                double phiC = Math.toRadians((k + 0.5f) * sector);
                float lx = (float) (cx + dir * midR * Math.sin(phiC));
                float ly = (float) (cy - midR * Math.cos(phiC));
                drawLabel(canvas, en, lx, ly, midR, ro - ri, sector, full, ring == activeDepth && k == activeIndex);
            }
        }

        // 5) 球体（环 N=0）最后画、压在枢纽圈里：展开着它仍是那颗可拖可点的球
        canvas.drawCircle(cx, cy, ballR, ballPaint);
        canvas.restore();
    }

    /** 画一格环形楔形：外弧走 sector 到内弧折回，闭合；高亮格用亮色。向左展开时角度镜像、扫过取负。 */
    private void drawWedge(Canvas canvas, float cx, float cy,
                           float ri, float ro, float a0deg, float sector, boolean active) {
        outerRect.set(cx - ro, cy - ro, cx + ro, cy + ro);
        innerRect.set(cx - ri, cy - ri, cx + ri, cy + ri);
        // Android 角：东=0 顺时针。向右展开 φ 顺时针 ⇒ a0=φ-90；向左展开 φ 逆时针 ⇒ a0=-90-φ 且扫过为负
        float a0 = facingLeft ? -90f - a0deg : a0deg - 90f;
        float sweep = facingLeft ? -sector : sector;
        sectorPath.reset();
        sectorPath.arcTo(outerRect, a0, sweep);
        sectorPath.arcTo(innerRect, a0 + sweep, -sweep);
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
