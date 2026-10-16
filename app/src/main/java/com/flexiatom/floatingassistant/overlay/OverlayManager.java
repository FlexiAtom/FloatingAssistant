package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import android.view.WindowManager;

import java.util.HashMap;
import java.util.HashSet;
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

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, View> views = new HashMap<>();
    /** 分组注册表：group → 该组所有 key，供 removeGroup 一次性批量移除（§5.2-6）。 */
    private final Map<String, Set<String>> groups = new HashMap<>();
    /** 每组待触发的软过期移除任务，重排期时先撤旧的（§5.2-5）。 */
    private final Map<String, Runnable> expiry = new HashMap<>();

    private Runnable onSphereTap;

    public OverlayManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setOnSphereTap(Runnable onSphereTap) {
        this.onSphereTap = onSphereTap;
    }

    public void showSphere() {
        if (views.containsKey(KEY_SPHERE)) {
            return;
        }
        FloatingBallView sphere = new FloatingBallView(context);
        sphere.setClickable(true);
        sphere.setOnClickListener(v -> {
            if (onSphereTap != null) {
                onSphereTap.run();
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 交互型：可触摸，但不抢焦点（点击由无障碍窗接收，下层仍可操作）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 200;
        addView(KEY_SPHERE, sphere, lp);
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
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int padH = dp(12), padV = dp(8);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC1C1B1F")); // MD3 反色表面 ~80% 不透明
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
