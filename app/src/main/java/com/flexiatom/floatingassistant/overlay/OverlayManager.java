package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;

public class OverlayManager {

    private final Context context;
    private final WindowManager windowManager;
    private FloatingBallView sphere;

    public OverlayManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void showSphere() {
        if (sphere != null) {
            return;
        }
        sphere = new FloatingBallView(context);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 200;
        windowManager.addView(sphere, lp);
    }

    public void hideSphere() {
        if (sphere != null) {
            windowManager.removeView(sphere);
            sphere = null;
        }
    }
}
