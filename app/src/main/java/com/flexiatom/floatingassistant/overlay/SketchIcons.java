package com.flexiatom.floatingassistant.overlay;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 手绘系工具栏图标（m5-editor-asr-notes E1b）：运行时按给定尺寸现绘，零资源零解码
 * （人裁 2026-10-21「图标应使用代码在应用启动时绘制需要的大小」）。
 * 人供 SVG 的几何件不在这里——软盘见 {@code ic_floppy.xml}、新建（纸笔）见 {@code ic_paper_pen.xml}：
 * 那两件的**比例由人钉死**，只许改色，手绘系才有自由按格子里的尺寸重排。
 */
final class SketchIcons {

    static final int HAMBURGER = 0;
    static final int UNDO = 1;
    static final int REDO = 2;
    static final int SLASH = 3;
    static final int DOTS = 4;

    private SketchIcons() {
    }

    static Drawable of(int kind, int color, float density) {
        return new SketchIcon(kind, color, density);
    }

    private static final class SketchIcon extends Drawable {
        private final int kind;
        private final float d;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        SketchIcon(int kind, int color, float density) {
            this.kind = kind;
            this.d = density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(color);
            stroke.setStrokeWidth(2f * density);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) (24 * d);
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) (24 * d);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float cx = b.exactCenterX();
            switch (kind) {
                case HAMBURGER -> {
                    float x0 = b.left + w * 0.12f;
                    float x1 = b.right - w * 0.12f;
                    canvas.drawLine(x0, b.top + h * 0.28f, x1, b.top + h * 0.28f, stroke);
                    canvas.drawLine(x0, b.top + h * 0.50f, x1, b.top + h * 0.50f, stroke);
                    canvas.drawLine(x0, b.top + h * 0.72f, x1, b.top + h * 0.72f, stroke);
                }
                case UNDO -> drawHump(canvas, b, w, h, true);
                case REDO -> drawHump(canvas, b, w, h, false);
                case SLASH -> canvas.drawLine(b.left + w * 0.34f, b.bottom - h * 0.22f,
                        b.left + w * 0.66f, b.top + h * 0.22f, stroke);
                case DOTS -> {
                    float r = 1.9f * d;
                    canvas.drawCircle(cx, b.top + h * 0.25f, r, fill);
                    canvas.drawCircle(cx, b.top + h * 0.50f, r, fill);
                    canvas.drawCircle(cx, b.top + h * 0.75f, r, fill);
                }
                default -> throw new IllegalStateException("unknown icon kind " + kind);
            }
        }

        /** 草图的「两条弧」＝∩ 驼峰 + 端点小箭头：左端朝下是撤销，镜像是重做。 */
        private void drawHump(Canvas canvas, Rect b, float w, float h, boolean undo) {
            RectF oval = new RectF(b.left + w * 0.18f, b.top + h * 0.20f,
                    b.right - w * 0.18f, b.bottom - h * 0.22f);
            canvas.drawArc(oval, 180, 180, false, stroke);
            float ex = undo ? oval.left : oval.right;
            float ey = oval.centerY();
            canvas.drawLine(ex, ey, ex, ey + 5f * d, stroke);
            canvas.drawLine(ex - 3f * d, ey + 2f * d, ex, ey + 5.5f * d, stroke);
            canvas.drawLine(ex + 3f * d, ey + 2f * d, ex, ey + 5.5f * d, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        /** 抽象方法必须覆盖；颜色在构造时就烘进画笔，外部滤镜对本系图标无意义（`Paint.setColorFilter` 也已废弃）。 */
        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
