package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.View;

public class FloatingBallView extends View {

    private final Paint fillPaint;
    private final int sizePx;

    public FloatingBallView(Context context) {
        super(context);
        sizePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(M3.color(context, M3.PRIMARY));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(sizePx, sizePx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = sizePx / 2f;
        float cy = sizePx / 2f;
        canvas.drawCircle(cx, cy, sizePx / 2f, fillPaint);
    }
}
