package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.Button;

/**
 * 就地面板共用的 M3 形状与按钮样式（{@link BlacklistPanelView} 与 {@link TextPanelView} 两套面板一处取色）。
 * 颜色一律走 {@link M3}：面板贴在壁纸调色板上，硬编码色会立刻和 App 其余部分脱色。
 */
final class PanelStyles {

    private PanelStyles() {
    }

    /** 卡片底：容器面保留 95% 不透明——悬浮窗贴在任何 App 上，留一点透明度表层级、留一点实心保可读。 */
    static GradientDrawable card(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(M3.alpha(M3.color(context, M3.SURFACE_CONTAINER_HIGH), 0.95f));
        bg.setCornerRadius(dp(context, 12));
        return bg;
    }

    /** 面板内的紧凑按钮：自带圆角底（不带框架按钮的 4/6dp 内建 inset）+ 零最小宽高，尺寸只由文字与内边距决定。 */
    static Button tonalButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(M3.color(context, M3.ON_SECONDARY_CONTAINER));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        // M3 tonal 按钮底（配 onSecondaryContainer 字）
        bg.setColor(M3.color(context, M3.SECONDARY_CONTAINER));
        bg.setCornerRadius(dp(context, 6));
        b.setBackground(bg);
        b.setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4));
        return b;
    }

    static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
