package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 悬浮窗唯一出口（提案 §4.1 约定）。内部维护 Map&lt;String,View&gt;，
 * 服务销毁/重建时通过 {@link #removeAllImmediate()} 清理，避免 WindowLeaked/幽灵窗。
 *
 * <p>窗口分两类：悬浮球为交互型（可触摸、非聚焦）；文本提示为展示型
 * （FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE，不拦截下层操作）。
 */
public class OverlayManager {

    private static final String KEY_SPHERE = "sphere";

    /** 原位贴图分组名：路由贴入、页面变化/软过期移除，统一走这一组（§5.2-4/5/6）。 */
    public static final String GROUP_MT = "mt_overlay";

    /** 工具扇形菜单窗的 key（§10 M0）；登记进 views 供 removeAllImmediate 兜底清理。 */
    private static final String KEY_MENU = "tool_menu";

    /** 就地编辑面板（黑名单，#68-T8b）的 key：全 app 至多一扇，且**不参与截屏摘窗**（见 {@link #applyDetach}）。 */
    private static final String KEY_PANEL = "edit_panel";

    /** 全屏框选层（§10 M1）的 key：模态触摸窗，选中/取消即收，也进 removeAllImmediate 兜底。 */
    private static final String KEY_SELECT = "region_select";

    /** 悬浮球拖曳判定阈值（dp）：位移不超过它=点击，超过=拖窗。 */
    private static final int SPHERE_DRAG_SLOP_DP = 8;

    /** 编辑面板默认距屏幕顶部的距离（dp）：顶部锚定让输入行避开软键盘。 */
    private static final int PANEL_TOP_DP = 32;

    /** 悬浮球屏幕几何（px），供扇形菜单锚定；{@link SphereDrag} 拖曳时实时更新，菜单随之跟随。 */
    private int sphereX;
    private int sphereY;
    private int sphereSizePx;

    /** 编辑面板窗参数（拖曳手柄改 x/y 后 {@code updateViewLayout} 用）；面板关闭即置空。 */
    private WindowManager.LayoutParams panelLp;

    private final Context context;
    /**
     * 带 app 主题 + 壁纸动态叠加的上下文（{@link M3#themed}）：所有 overlay 视图都用它构造，
     * 服务侧建面板时也经 {@link #themedContext()} 取同一份。窗口几何/flag 仍走 {@link #context}。
     */
    private final Context overlayContext;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, View> views = new HashMap<>();
    /** 分组注册表：group → 该组所有 key，供 removeGroup 一次性批量移除（§5.2-6）。 */
    private final Map<String, Set<String>> groups = new HashMap<>();
    /** 每组待触发的软过期移除任务，重排期时先撤旧的（§5.2-5）。 */
    private final Map<String, Runnable> expiry = new HashMap<>();
    /** 截屏期间临时 detach 的窗（key/View/params），供 {@link #showSelfAfterCapture()} 原样挂回。 */
    private final List<Detached> detachedForCapture = new ArrayList<>();

    private Runnable onSphereTap;

    public OverlayManager(Context context) {
        this.context = context;
        this.overlayContext = M3.themed(context);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 供服务侧构造 overlay 内容视图（如黑名单面板）用同一份 M3 上下文。 */
    public Context themedContext() {
        return overlayContext;
    }

    public void setOnSphereTap(Runnable onSphereTap) {
        this.onSphereTap = onSphereTap;
    }

    public void showSphere() {
        if (views.containsKey(KEY_SPHERE)) {
            return;
        }
        FloatingBallView sphere = new FloatingBallView(overlayContext);
        sphere.setClickable(true);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 交互型：可触摸，但不抢焦点（点击/拖曳由无障碍窗接收，下层仍可操作）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 初始停在左边缘、垂直居中：给向右展开的 180° 扇形菜单留出上下空间（球贴顶会使上方楔形被裁）。
        // 之后由用户拖到任意位置（{@link SphereDrag}），菜单纯几何跟随球心并按最近边缘决定开口朝向。
        sphereSizePx = dp(48);
        sphereX = 0;
        sphereY = (context.getResources().getDisplayMetrics().heightPixels - sphereSizePx) / 2;
        lp.x = sphereX;
        lp.y = sphereY;
        // 触摸自处理：按下即消费，故不再挂 OnClickListener（否则拖曳与点击会双触发）
        sphere.setOnTouchListener(new SphereDrag(sphere, lp));
        addView(KEY_SPHERE, sphere, lp);
    }

    /**
     * 悬浮球拖曳（#68-T9）：位移超过 {@code touchSlop} 才认定为拖窗——期间逐帧 {@code updateViewLayout}
     * 并把新位置同步进 {@link #sphereX}/{@link #sphereY}（扇形菜单锚点即读即跟随）；未超过则松手算点击。
     * 位置只限在本进程本次服务生命周期内，不写 prefs（服务重连回默认左缘中点）。
     */
    private final class SphereDrag implements View.OnTouchListener {
        private final View target;
        private final WindowManager.LayoutParams lp;
        private float downRawX;
        private float downRawY;
        private float startLpX;
        private float startLpY;
        private boolean dragging;

        SphereDrag(View target, WindowManager.LayoutParams lp) {
            this.target = target;
            this.lp = lp;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startLpX = lp.x;
                    startLpY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) < dp(SPHERE_DRAG_SLOP_DP)) {
                        return true;
                    }
                    dragging = true;
                    DisplayMetrics dm = context.getResources().getDisplayMetrics();
                    int nx = clamp(Math.round(startLpX + dx), 0, dm.widthPixels - sphereSizePx);
                    int ny = clamp(Math.round(startLpY + dy), 0, dm.heightPixels - sphereSizePx);
                    if (nx == lp.x && ny == lp.y) {
                        return true;
                    }
                    lp.x = nx;
                    lp.y = ny;
                    sphereX = nx;
                    sphereY = ny;
                    try {
                        windowManager.updateViewLayout(target, lp);
                    } catch (Exception ignored) {
                        // 球窗已随服务失效：位置改动无意义，静默
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragging && onSphereTap != null) {
                        onSphereTap.run();
                    }
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }

    /**
     * 弹出同心环工具菜单（§10 M0 + #68 径向树）：以悬浮球球心为原点向**最近边缘的对侧**展开半圆，
     * 页面栈由内向外画成同心环。窗从球心向开口侧铺（{@link FanMenuView#measureWidthPx}，外沿半径按当前页栈
     * 内容自适应），球心对齐窗**开口对侧**边缘（球在左半→窗左边、球在右半→窗右边）；球贴近屏幕上下沿时窗整体内收。
     * 必须在主线程调用（球点击回调 / 服务事件均在主线程）。push/pop 改变页栈后由服务重新调用本方法重建窗（尺寸随内容而变）。
     *
     * @param stack    页面栈（{@code stack.get(0)}=最内环=顶层工具；向外逐层子页）
     * @param listener 选择/枢纽/环外回调（服务据此 run 叶子 / push 容器 / pop / 关闭）
     */
    public void showToolMenu(List<FanMenuView.Page> stack, FanMenuView.Listener listener) {
        View old = views.remove(KEY_MENU);
        if (old != null) {
            try {
                windowManager.removeViewImmediate(old);
            } catch (Exception ignored) {
                // 已失效
            }
        }
        int mw = FanMenuView.measureWidthPx(overlayContext, stack);
        int mh = 2 * mw;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int ballCx = sphereX + sphereSizePx / 2;
        int ballCy = sphereY + sphereSizePx / 2;
        // 开口朝向最近边缘：球在屏幕右半边就向左展开（否则环带整条出屏）
        boolean facingLeft = ballCx > dm.widthPixels / 2;
        // 窗尽量把球心摆在自己竖直中线上；球贴近屏幕上下沿时窗整体内收，球心在窗内的 y 随之偏移
        int winY = clamp(ballCy - mh / 2, 0, Math.max(0, dm.heightPixels - mh));
        FanMenuView menu = new FanMenuView(overlayContext, stack, listener, facingLeft, ballCy - winY);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mw, mh,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 可触摸（捕获点/拖手势），但不抢 IME 焦点
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 球心 = 窗（开口对侧）边缘中点
        lp.x = facingLeft ? ballCx - mw : ballCx;
        lp.y = winY;
        addView(KEY_MENU, menu, lp);
    }

    /** 收起工具扇形菜单（不存在则忽略）。必须在主线程调用。 */
    public void hideToolMenu() {
        View v = views.remove(KEY_MENU);
        if (v != null) {
            try {
                windowManager.removeViewImmediate(v);
            } catch (Exception ignored) {
                // 已失效
            }
        }
    }

    public boolean isToolMenuVisible() {
        return views.containsKey(KEY_MENU);
    }

    /**
     * 弹出全屏框选层（§10 M1）：吃掉全部触摸的模态窗，拖出矩形后由 {@link RegionSelectView.Listener}
     * 交回屏幕像素坐标。登记进 {@code views}，故服务销毁/重建的 {@link #removeAllImmediate()} 一并兜底清除。
     * 必须在主线程调用。
     */
    public void showRegionSelect(String hintText, RegionSelectView.Listener listener) {
        hideRegionSelect();
        RegionSelectView view = new RegionSelectView(overlayContext, hintText, listener);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 可触摸（拖选区），但不抢 IME 焦点。
                // FLAG_LAYOUT_IN_SCREEN **不足以**让窗原点落到屏幕左上角：真机实测选层仍被状态栏
                // 下推 52px（拖到屏 y=712 记成视图 y=660），于是选区坐标与截屏坐标错位、按选区过滤
                // 全落空。再加 NO_LIMITS + 刘海 ALWAYS，视图 px 才真正＝截屏 px。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addView(KEY_SELECT, view, lp);
    }

    /** 收起全屏框选层（不存在则忽略）。必须在主线程调用。 */
    public void hideRegionSelect() {
        View v = views.remove(KEY_SELECT);
        if (v != null) {
            try {
                windowManager.removeViewImmediate(v);
            } catch (Exception ignored) {
                // 已失效
            }
        }
    }

    public boolean isRegionSelectVisible() {
        return views.containsKey(KEY_SELECT);
    }

    /**
     * 就地编辑面板（#68-T8b）：居中一扇**可获焦**的 {@code TYPE_ACCESSIBILITY_OVERLAY}，承载
     * {@link BlacklistPanelView}。与球/菜单/贴图的关键差别是**不带** {@code FLAG_NOT_FOCUSABLE}——
     * 面板内 {@code EditText} 必须拿到窗口焦点才拉得起软键盘（项目此前所有窗体都不带焦点，此为第一例）。
     * 代价：该窗是触摸模态的，面板开着时下层 App（含悬浮球）收不到触摸，只能由面板自己的「关闭」按钮或返回键退出。
     * 已存在则先替换。必须在主线程调用。
     */
    public void showPanel(View content) {
        hidePanel();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(PanelView.WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // FLAG_LAYOUT_NO_LIMITS 是「让窗出屏」的关键：只带 LAYOUT_IN_SCREEN 时 WMS 会把 frame
                // 钳回显示区域内，导致面板拖到边沿就停（跟手不回＝用户报的抽搐），且浅灰条永远越不出屏、
                // 也就永远触发不了「拖出即关闭」。加它之后屏内与否完全由 movePanel 自己判。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // 顶部锚定（不是居中）：居中时卡片下半张正好被软键盘盖住（T8b 真机复验的残留缺陷）。
        // 键盘高度约屏高 1/3，卡片贴顶后输入行始终在键盘之上；仍可被顶部手柄拖到任意位置。
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(PANEL_TOP_DP);
        // 键盘让位交给「贴顶 + 手柄拖曳」，窗自己绝不动：ADJUST_PAN 是 WMS 用动画平移 surface 实现的，
        // 它每帧改一次窗的可视偏移，和拖曳里逐帧写的 lp.x/lp.y 叠加＝抖动的第二个来源（抽搐的另一半）。
        // 卡片贴顶时输入行本就在键盘之上，不需要系统再顶一次。
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windowManager.addView(content, lp);
        views.put(KEY_PANEL, content);
        panelLp = lp;
        content.requestFocus(); // 焦点落进面板，返回键才会走它的 dispatchKeyEvent
    }

    /**
     * 按增量拖动编辑面板窗（顶部手柄拖曳用）。gravity 是 {@code TOP|CENTER_HORIZONTAL}，故
     * {@code x} 是相对屏幕中线的偏移（可负）、{@code y} 是距顶距离。
     *
     * <p>**不做屏内钳位**（用户裁定）：钳位会让窗停在边界而手指继续走，逐帧回弹＝拖曳抽搐；Java 层钳位已删，
     * WMS 那道钳位由本窗的 {@code FLAG_LAYOUT_NO_LIMITS} 放开（见 {@link #showPanel}）。改成
     * 「顶部浅灰条越出任一屏边即 {@link #hidePanel()}」——拖出屏幕就是丢弃面板。
     */
    public void movePanel(int dx, int dy) {
        View v = views.get(KEY_PANEL);
        if (v == null || panelLp == null) {
            return;
        }
        panelLp.x += dx;
        panelLp.y += dy;
        try {
            windowManager.updateViewLayout(v, panelLp);
        } catch (Exception ignored) {
            // 面板窗已失效：忽略
            return;
        }
        if (v instanceof PanelView panel && handleOffScreen(panel)) {
            hidePanel();
        }
    }

    /** 顶部浅灰条是否越出屏幕任一边缘（条在窗内位置 + 窗在屏内位置，都不依赖帧遍历）。 */
    private boolean handleOffScreen(PanelView panel) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        android.graphics.Rect r = new android.graphics.Rect();
        panel.handleRectInRoot(r);
        // 窗左缘 = (屏宽 − 窗宽)/2 + x（CENTER_HORIZONTAL），窗上缘 = y（TOP）
        r.offset((dm.widthPixels - dp(PanelView.WIDTH_DP)) / 2 + panelLp.x, panelLp.y);
        return r.left < 0 || r.top < 0 || r.right > dm.widthPixels || r.bottom > dm.heightPixels;
    }

    /** 收起就地编辑面板（不存在则忽略）。必须在主线程调用。 */
    public void hidePanel() {
        View v = views.remove(KEY_PANEL);
        panelLp = null;
        if (v != null) {
            try {
                windowManager.removeViewImmediate(v);
            } catch (Exception ignored) {
                // 已失效
            }
        }
    }

    public boolean isPanelVisible() {
        return views.containsKey(KEY_PANEL);
    }

    /**
     * 面板正文的**当前**文本（{@link TextPanelView} 的正文可编辑，用户可能已就地改过 OCR 结果）。
     * 面板未开、或开的是别种面板时返回 {@code fallback}——调用方传的通常是自己上次灌进去的那份。
     */
    public String panelBodyText(String fallback) {
        View v = views.get(KEY_PANEL);
        return v instanceof TextPanelView text ? text.bodyText() : fallback;
    }

    /** 展示型文本浮层：不拦截下层触摸/焦点。同一 key 重复调用即原位更新内容。 */
    public void showTextOverlay(String key, String text) {
        mainHandler.post(() -> applyTextOverlay(key, text));
    }

    private void applyTextOverlay(String key, String text) {
        View existing = views.get(key);
        if (existing instanceof TextView tv) {
            tv.setText(text);
            return;
        }
        TextView tv = new TextView(overlayContext);
        tv.setText(text);
        // 状态浮层用 M3 反色对（surfaceInverse/onSurfaceInverse）：深色模式下它是浅色字配深底，
        // 浅色模式下自动翻成深字配浅底，不再是我原先写死的一路白字。
        tv.setTextColor(M3.color(overlayContext, M3.ON_INVERSE_SURFACE));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int padH = dp(12), padV = dp(8);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(M3.alpha(M3.color(overlayContext, M3.INVERSE_SURFACE), 0.8f));
        bg.setCornerRadius(dp(8));
        tv.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 展示型：不触摸、不聚焦、不拦下层
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(72);
        addView(key, tv, lp);
    }

    public void removeOverlay(String key) {
        mainHandler.post(() -> {
            View v = views.remove(key);
            ungroup(key);
            if (v != null) {
                windowManager.removeViewImmediate(v);
            }
        });
    }

    public void hideSphere() {
        View v = views.remove(KEY_SPHERE);
        if (v != null) {
            windowManager.removeViewImmediate(v);
        }
    }

    /**
     * 截屏前临时摘除**本 app 拥有的全部悬浮窗**（悬浮球、扇形菜单及其子页、进度浮层、上一轮译文贴图），
     * 使它们不被 {@code takeScreenshot} 拍进源图——否则自家 UI 会被 OCR 读成文字、或遮住真正的原文。
     *
     * <p>用 {@code removeViewImmediate} 真正 detach（而非 {@code setVisibility(INVISIBLE)}）：INVISIBLE 只停
     * View 自绘，窗口 session/surface 仍在、SurfaceFlinger 继续合成那层，故实测截屏依旧拍进悬浮球（用户人工
     * 复验"修复无效"）。detach 才把窗从 WindowManager 摘掉、消解对应 layer；调用方须在 {@code capture()} 前
     * 留一段沉降延时，让拆除事务落到 SurfaceFlinger。与 {@link #showSelfAfterCapture()} 成对使用。
     * 可从工作线程安全调用：内部投递主线程执行并等待落地。
     */
    public void hideSelfForCapture() {
        setSelfDetached(true);
    }

    /** 恢复 {@link #hideSelfForCapture()} 摘除的悬浮窗（截屏拿到帧后立即调用），按原 LayoutParams 重新挂回。 */
    public void showSelfAfterCapture() {
        setSelfDetached(false);
    }

    private void setSelfDetached(boolean detach) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyDetach(detach);
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            applyDetach(detach);
            latch.countDown();
        });
        try {
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 主线程执行：detach=true 把每个已挂窗 {@code removeViewImmediate} 摘掉、连同其 {@link WindowManager.LayoutParams}
     * 记入 {@link #detachedForCapture}（并从 {@code views} 移除，避免恢复前的窗口二次重排错乱）；
     * detach=false 按记录的 key/params 原样 {@code addView} 挂回并重登记进 {@code views}。
     */
    private void applyDetach(boolean detach) {
        if (detach) {
            detachedForCapture.clear();
            for (Map.Entry<String, View> e : new ArrayList<>(views.entrySet())) {
                // 就地编辑面板**不参与截屏摘窗**：它是全 app 唯一带焦点的窗，摘掉再挂回会丢软键盘与光标；
                // 而采集只在跑翻译时发生，那时编辑面板本就关着，没有拍进源图的现实路径。
                if (KEY_PANEL.equals(e.getKey())) {
                    continue;
                }
                View v = e.getValue();
                if (!(v.getLayoutParams() instanceof WindowManager.LayoutParams lp)) {
                    continue;
                }
                try {
                    windowManager.removeViewImmediate(v);
                    detachedForCapture.add(new Detached(e.getKey(), v, lp));
                } catch (Exception ignored) {
                    // 已失效：不记入，恢复时自然跳过
                }
            }
            for (Detached d : detachedForCapture) {
                views.remove(d.key);
            }
        } else {
            for (Detached d : detachedForCapture) {
                try {
                    windowManager.addView(d.view, d.lp);
                    views.put(d.key, d.view);
                } catch (Exception ignored) {
                    // 挂回失败（窗已随进程失效）：忽略
                }
            }
            detachedForCapture.clear();
        }
    }

    /** 截屏期间临时摘除的单个窗：记住 key、View 与其原始参数，供恢复原样重挂。 */
    private static final class Detached {
        final String key;
        final View view;
        final WindowManager.LayoutParams lp;

        Detached(String key, View view, WindowManager.LayoutParams lp) {
            this.key = key;
            this.view = view;
            this.lp = lp;
        }
    }

    /** 服务 onDestroy / 重建时调用：立即移除全部已挂载窗口。 */
    public void removeAllImmediate() {
        for (View v : views.values()) {
            try {
                windowManager.removeViewImmediate(v);
            } catch (Exception ignored) {
                // 已随进程/窗体失效
            }
        }
        views.clear();
        groups.clear();
        for (Runnable r : expiry.values()) {
            mainHandler.removeCallbacks(r);
        }
        expiry.clear();
    }

    private void addView(String key, View view, WindowManager.LayoutParams lp) {
        addView(null, key, view, lp);
    }

    /** group 非空则把 key 登记进该组（供 removeGroup 批量移除）。 */
    private void addView(String group, String key, View view, WindowManager.LayoutParams lp) {
        View old = views.remove(key);
        if (old != null) {
            windowManager.removeViewImmediate(old);
            ungroup(key);
        }
        windowManager.addView(view, lp);
        views.put(key, view);
        if (group != null) {
            groups.computeIfAbsent(group, g -> new HashSet<>()).add(key);
        }
    }

    private void ungroup(String key) {
        for (Set<String> ks : groups.values()) {
            ks.remove(key);
        }
    }

    /**
     * 原位贴图（§5.2-1）：在屏幕像素矩形 (x,y,w,h) 放一段文本，归入 group。
     * 展示型穿透（不触摸、不聚焦），字号随框自适应缩放到填满。
     * <p>底色用**从原文矩形采样**的 bgColor（全不透明，遮住原文），字色用与之对比的 textColor，
     * 使译文像就地覆写在页面自己的底色上（而非浮一块深色板）。直角无圆角，四角不漏原文。
     * 同一 key 重复调用即原位更新（含底色/字色）。坐标须为屏幕物理像素（截屏 720×1600 与 overlay 同系）。
     */
    public void showBoxedText(String group, String key, int x, int y, int w, int h, String text,
                              int bgColor, int textColor) {
        mainHandler.post(() -> applyBoxedText(group, key, x, y, w, h, text, bgColor, textColor));
    }

    private void applyBoxedText(String group, String key, int x, int y, int w, int h, String text,
                                int bgColor, int textColor) {
        View existing = views.get(key);
        if (existing instanceof TextView tv) {
            tv.setText(text);
            tv.setTextColor(textColor);
            tv.setBackground(boxBackground(bgColor));
            return;
        }
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(3), 0, dp(3), 0);
        // 字号在 [6,18]sp 间二分自适应到填满该框（minSdk30 支持 auto-size）
        tv.setAutoSizeTextTypeUniformWithConfiguration(
                6, 18, 1, TypedValue.COMPLEX_UNIT_SP);
        tv.setBackground(boxBackground(bgColor));
        int cw = Math.max(1, w);
        int ch = Math.max(1, h);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                cw, ch,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START; // 绝对定位
        lp.x = Math.max(0, x);
        lp.y = Math.max(0, y);
        addView(group, key, tv, lp);
    }

    /** 批量移除一组贴图（§5.2-6），一次性 removeViewImmediate，避免逐个抖动。 */
    public void removeGroup(String group) {
        mainHandler.post(() -> doRemoveGroup(group));
    }

    private void doRemoveGroup(String group) {
        cancelExpiry(group);
        Set<String> keys = groups.remove(group);
        if (keys == null) {
            return;
        }
        for (String k : keys) {
            View v = views.remove(k);
            if (v != null) {
                try {
                    windowManager.removeViewImmediate(v);
                } catch (Exception ignored) {
                    // 已失效
                }
            }
        }
    }

    /**
     * 无事件软过期兜底（§5.2-5）：group 在 delayMs 后自动整体移除。每次调用重排期（撤旧计时）。
     * 用于游戏/视频等不派发无障碍事件的页面，防译文长期错位。delayMs&lt;=0 视为常驻（取消计时）。
     */
    public void scheduleGroupExpiry(String group, long delayMs) {
        mainHandler.post(() -> {
            cancelExpiry(group);
            if (delayMs <= 0) {
                return;
            }
            Runnable r = () -> doRemoveGroup(group);
            expiry.put(group, r);
            mainHandler.postDelayed(r, delayMs);
        });
    }

    private void cancelExpiry(String group) {
        Runnable r = expiry.remove(group);
        if (r != null) {
            mainHandler.removeCallbacks(r);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    /** 原位贴图的底：全不透明实心直角块，铺满并遮住原文矩形（不留圆角缝隙漏原文）。 */
    private GradientDrawable boxBackground(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color | 0xFF000000); // 强制全不透明
        bg.setCornerRadius(0);
        return bg;
    }
}
