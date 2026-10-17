package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.view.ContextThemeWrapper;

import androidx.core.graphics.ColorUtils;

import com.flexiatom.floatingassistant.R;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

/**
 * M3 角色取色入口（md3-dynamic-color）。
 *
 * <p>悬浮窗由无障碍服务持有，而服务上下文本身不带 app 主题，直接读 {@code colorPrimary} 这类属性读不到值；
 * 所以先给上下文套上 {@code Theme.FloatingAssistant}，再让 {@link DynamicColors} 在其上挂**壁纸调色板叠加**。
 * 叠加不支持时（Android 11、或 ROM 关掉个性化）{@code wrapContextIfAvailable} 原样返回上下文，
 * 于是自动回落到父主题自带的 Material 3 基线色板——**这就是 minSdk 30 的兜底，不需要写版本分支**。
 */
final class M3 {

    // 下面全是 Material 库的 R（不是 app 的 R）：本 app 不再自存色值，角色只由主题给出。
    static final int PRIMARY = com.google.android.material.R.attr.colorPrimary;
    static final int ON_PRIMARY = com.google.android.material.R.attr.colorOnPrimary;
    static final int PRIMARY_CONTAINER = com.google.android.material.R.attr.colorPrimaryContainer;
    static final int ON_PRIMARY_CONTAINER = com.google.android.material.R.attr.colorOnPrimaryContainer;
    static final int SECONDARY_CONTAINER = com.google.android.material.R.attr.colorSecondaryContainer;
    static final int ON_SECONDARY_CONTAINER = com.google.android.material.R.attr.colorOnSecondaryContainer;
    static final int SURFACE_CONTAINER_HIGH = com.google.android.material.R.attr.colorSurfaceContainerHigh;
    static final int ON_SURFACE = com.google.android.material.R.attr.colorOnSurface;
    static final int ON_SURFACE_VARIANT = com.google.android.material.R.attr.colorOnSurfaceVariant;
    static final int OUTLINE = com.google.android.material.R.attr.colorOutline;
    static final int OUTLINE_VARIANT = com.google.android.material.R.attr.colorOutlineVariant;
    static final int INVERSE_SURFACE = com.google.android.material.R.attr.colorSurfaceInverse;
    static final int ON_INVERSE_SURFACE = com.google.android.material.R.attr.colorOnSurfaceInverse;

    private M3() {
    }

    /** 带 M3 角色、并尽可能跟随壁纸调色板的上下文；所有 overlay 视图都用它构造。 */
    static Context themed(Context base) {
        return DynamicColors.wrapContextIfAvailable(
                new ContextThemeWrapper(base, R.style.Theme_FloatingAssistant));
    }

    /**
     * 解析一个 M3 角色属性。属性由 {@code Theme.Material3.*} 父主题保证存在，
     * 回落值 0 仅在主题被换坏时命中（那时界面变透明，比静默用旧硬编码色更容易被发现）。
     */
    static int color(Context themed, int attr) {
        return MaterialColors.getColor(themed, attr, 0);
    }

    /** 角色色加透明度：悬浮窗贴在任何 App 上，分层与可读性都要靠它。 */
    static int alpha(int color, float fraction) {
        return ColorUtils.setAlphaComponent(color, Math.round(255 * fraction));
    }
}
