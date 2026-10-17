package com.flexiatom.floatingassistant.overlay;

import android.graphics.Rect;

/**
 * 就地面板的共同约定：黑名单编辑面板与文本面板共用 {@link OverlayManager#showPanel} 那一扇**带焦点**的窗，
 * 全 app 同时只有一张，故两者的宽度与「拖出屏边即关闭」的判定必须走同一套口径。
 */
interface PanelView {

    /** 面板固定宽度（dp），屏宽 360dp 量级下留边成卡片状。 */
    int WIDTH_DP = 300;

    /**
     * 顶部手柄条在**本视图（= 窗内容）坐标**内的矩形，供 {@link OverlayManager#movePanel} 结合窗位置判
     * 「浅灰条拖出屏幕即关闭」。用布局坐标而非 {@code getLocationOnScreen}：后者要等下一帧遍历才反映
     * {@code updateViewLayout} 的新位置，逐帧拖曳会读到一个事件滞后的旧值。
     */
    void handleRectInRoot(Rect out);
}
