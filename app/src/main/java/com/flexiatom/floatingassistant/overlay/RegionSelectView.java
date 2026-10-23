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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 全屏框选层（§10 M1）：按住拖出一个矩形，松手把**屏幕物理像素**坐标交回服务去截图裁剪。
 *
 * <p>坐标同系的依据：本窗与原位贴图用的是同一套 {@code FLAG_LAYOUT_IN_SCREEN} + 绝对像素定位，而 M3
 * 已实测译文块正好压在原文上——即「窗内 1px = 截屏 1px」。故这里不做任何密度/状态栏换算，直接交回视图坐标。
 *
 * <p>挖洞用 {@link Path.FillType#INVERSE_EVEN_ODD}（整屏矩形减去选区）而不是 {@code PorterDuff.CLEAR}：
 * 后者在同一次绘制里擦背景要求视图自带离屏 layer，硬件加速下容易把洞又填上；奇偶反填不依赖 layer，画出来就是
 * 「四周暗、中间亮」。
 *
 * <p>这层是**模态**的：它吃掉全部触摸，期间下层 App 不可滚动/点击——所以「先把页面停在你想要的画面，再框选」。
 * 也因为它盖在悬浮球之上（后加的窗 z 序更高），取消不能靠点球，改为**原地轻点**（位移不足 touchSlop）即收。
 */
public final class RegionSelectView extends View {

    /** 提示气泡距屏幕顶部的距离（dp）：贴顶但留出状态栏文字可读。 */
    private static final int HINT_TOP_DP = 24;

    public interface Listener {
        /** 选区确定，坐标为屏幕物理像素且已保证 {@code x0<=x1, y0<=y1}。 */
        void onSelected(int x0, int y0, int x1, int y1);

        /** 位移不足 touchSlop（当作误触或主动取消）或手势被打断：收起选层，不做识别。 */
        void onCancelled();
    }

    private final Listener listener;
    private final int touchSlop;
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scrimPath = new Path();
    private final Rect selection = new Rect();
    private final float density;

    private int anchorX;
    private int anchorY;
    private boolean dragging;

    private String hintText;

    public RegionSelectView(Context context, String hintText, Listener listener) {
        super(context);
        this.listener = listener;
        this.hintText = hintText;
        this.density = context.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        // 遮罩取 onSurface 半透明：压在浅色页面上变灰、压在深色页面上仍是暗化，两种底色都不得刺眼
        scrimPaint.setStyle(Paint.Style.FILL);
        scrimPaint.setColor(M3.alpha(M3.color(context, M3.ON_SURFACE), 0.45f));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2));
        borderPaint.setColor(M3.color(context, M3.PRIMARY));
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(M3.alpha(M3.color(context, M3.INVERSE_SURFACE), 0.8f));
        bubbleTextPaint.setColor(M3.color(context, M3.ON_INVERSE_SURFACE));
        bubbleTextPaint.setTextSize(sp(13));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (dragging && !selection.isEmpty()) {
            scrimPath.reset();
            scrimPath.addRect(0, 0, w, h, Path.Direction.CW);
            scrimPath.addRect(selection.left, selection.top, selection.right, selection.bottom,
                    Path.Direction.CW);
            scrimPath.setFillType(Path.FillType.INVERSE_EVEN_ODD);
            canvas.drawPath(scrimPath, scrimPaint);
            canvas.drawRect(selection, borderPaint);
        }
        drawHint(canvas, w);
    }

    /** 顶部提示气泡：告诉用户「拖=选区、松手=识别、轻点=取消」，否则这层黑屏看着像卡住。 */
    private void drawHint(Canvas canvas, int width) {
        float textW = bubbleTextPaint.measureText(hintText);
        int padH = dp(12);
        int padV = dp(8);
        float bubbleW = textW + 2 * padH;
        float left = (width - bubbleW) / 2f;
        float top = dp(HINT_TOP_DP);
        float radius = dp(8);
        canvas.drawRoundRect(left, top, left + bubbleW,
                top + bubbleTextPaint.getTextSize() + 2 * padV, radius, radius, bubblePaint);
        canvas.drawText(hintText, left + padH, top + padV - bubbleTextPaint.ascent(), bubbleTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                anchorX = (int) event.getX();
                anchorY = (int) event.getY();
                selection.setEmpty();
                dragging = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                if (!dragging && Math.abs(x - anchorX) < touchSlop
                        && Math.abs(y - anchorY) < touchSlop) {
                    return true;
                }
                dragging = true;
                setRect(anchorX, anchorY, x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    listener.onCancelled(); // 原地轻点＝取消（本层盖在悬浮球之上，取消不能靠点球）
                    return true;
                }
                setRect(anchorX, anchorY, (int) event.getX(), (int) event.getY());
                listener.onSelected(selection.left, selection.top, selection.right, selection.bottom);
                return true;
            case MotionEvent.ACTION_CANCEL:
                listener.onCancelled();
                return true;
            default:
                return false;
        }
    }

    private void setRect(int x0, int y0, int x1, int y1) {
        selection.set(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
    }

    private int dp(int value) {
        return (int) (value * density);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
