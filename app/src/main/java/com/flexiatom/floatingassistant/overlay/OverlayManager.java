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
import java.util.Map;

/**
 * 悬浮窗唯一出口（提案 §4.1 约定）。内部维护 Map&lt;String,View&gt;，
 * 服务销毁/重建时通过 {@link #removeAllImmediate()} 清理，避免 WindowLeaked/幽灵窗。
 *
 * <p>窗口分两类：悬浮球为交互型（可触摸、非聚焦）；文本提示为展示型
 * （FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE，不拦截下层操作）。
 */
public class OverlayManager {

    private static final String KEY_SPHERE = "sphere";

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, View> views = new HashMap<>();

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
    }

    private void addView(String key, View view, WindowManager.LayoutParams lp) {
        View old = views.remove(key);
        if (old != null) {
            windowManager.removeViewImmediate(old);
        }
        windowManager.addView(view, lp);
        views.put(key, view);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
