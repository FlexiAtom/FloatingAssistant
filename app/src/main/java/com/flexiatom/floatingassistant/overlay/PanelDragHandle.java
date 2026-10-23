/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of FloatingAssistant.
 *
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.flexiatom.floatingassistant.R;

/**
 * 面板顶部手柄：居中一条浅灰短条，**双击=关闭面板**、**按住拖曳=移动面板窗**。
 * 触摸目标是整条 {@code FrameLayout}（高 20dp，远大于 4dp 的可见条），免得细条点不中。
 *
 * <p>从 {@code BlacklistPanelView} 里抽出来共用（黑名单面板与文本面板同一手势）：这套「逐帧 raw 增量 +
 * 亚像素 carry」的写法是从用户报告的**拖曳抽搐**里定下来的——用 {@code GestureDetector.onScroll} 会把整段
 * 手势累计量当逐帧增量写进 {@code lp.x}，窗跑得比手指快、撞屏边回弹＝抖动。逻辑只留一份，别在两个面板里各自漂移。
 */
final class PanelDragHandle extends FrameLayout {

    /** 拖曳起始阈值（px²，TouchTool 同款 9px 的平方）：位移不足它不算拖，留给双击判定。 */
    private static final float DRAG_SLOP_SQ_PX = 81f;

    /** 手柄两次轻点间隔不超过它算「双击关闭」。 */
    private static final long DOUBLE_TAP_TIMEOUT_MS = 300L;

    /** 关闭/移动请求（由所在面板转发给 {@link OverlayManager}）。 */
    interface Callback {
        void onClose();

        void onMove(int dx, int dy);
    }

    private final View bar;

    PanelDragHandle(Context context, int barWidthPx, Callback callback) {
        super(context);
        setContentDescription(context.getString(R.string.panel_cd_handle));
        GradientDrawable barBg = new GradientDrawable();
        // 半透明 onSurfaceVariant：叠在容器面上是中性灰条
        barBg.setColor(M3.alpha(M3.color(context, M3.ON_SURFACE_VARIANT), 0.5f));
        barBg.setCornerRadius(px(2));
        bar = new View(context);
        bar.setBackground(barBg);
        LayoutParams barLp = new LayoutParams(barWidthPx, px(4));
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(bar, barLp);

        installTouchCallback(callback);
    }

    /** 可见条（不是整条触摸区）在**本视图坐标**内的矩形；调用方再把它偏移到面板根坐标。 */
    void barRectInRoot(Rect out) {
        bar.getDrawingRect(out);
        offsetDescendantRectToMyCoords(bar, out);
    }

    private void installTouchCallback(Callback callback) {
        setOnTouchListener(new View.OnTouchListener() {
            private float lastRawX;
            private float lastRawY;
            private float carryX;
            private float carryY;
            private boolean dragging;
            private long lastTapUpAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastRawX = e.getRawX();
                        lastRawY = e.getRawY();
                        carryX = 0f;
                        carryY = 0f;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - lastRawX;
                        float dy = e.getRawY() - lastRawY;
                        if (!dragging && dx * dx + dy * dy < DRAG_SLOP_SQ_PX) {
                            return true;
                        }
                        dragging = true;
                        // 亚像素余量进 carry 累加，免得连续小步位移被 round 吃成 0（拖不动）
                        float fx = dx + carryX;
                        float fy = dy + carryY;
                        int ix = Math.round(fx);
                        int iy = Math.round(fy);
                        carryX = fx - ix;
                        carryY = fy - iy;
                        lastRawX = e.getRawX();
                        lastRawY = e.getRawY();
                        if (ix != 0 || iy != 0) {
                            callback.onMove(ix, iy);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            dragging = false;
                            return true;
                        }
                        long now = android.os.SystemClock.uptimeMillis();
                        if (lastTapUpAt > 0 && now - lastTapUpAt <= DOUBLE_TAP_TIMEOUT_MS) {
                            lastTapUpAt = 0;
                            callback.onClose();
                        } else {
                            lastTapUpAt = now;
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
