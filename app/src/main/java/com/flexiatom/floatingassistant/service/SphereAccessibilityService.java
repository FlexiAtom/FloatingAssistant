package com.flexiatom.floatingassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import com.flexiatom.floatingassistant.capture.ScreenCapturer;
import com.flexiatom.floatingassistant.core.CaptureStateRouter;
import com.flexiatom.floatingassistant.ocr.PpOcrEngineFactory;
import com.flexiatom.floatingassistant.mt.MtEngineFactory;
import com.flexiatom.floatingassistant.overlay.OverlayManager;

public class SphereAccessibilityService extends AccessibilityService {

    private static volatile boolean connected = false;
    private static volatile SphereAccessibilityService instance;

    private OverlayManager overlayManager;
    private ScreenCapturer screenCapturer;
    private CaptureStateRouter router;

    public static boolean isConnected() {
        return connected;
    }

    /** 已连接的实例；未连接返回 null。 */
    public static SphereAccessibilityService getInstance() {
        return connected ? instance : null;
    }

    /** 供 app:capture 使用的截图器（懒建）。 */
    public synchronized ScreenCapturer capturer() {
        if (screenCapturer == null) {
            screenCapturer = new ScreenCapturer(this);
        }
        return screenCapturer;
    }

    /** 以程序方式触发一次采集-OCR-覆盖层（等同点悬浮球）；服务未就绪则忽略。 */
    public void runOnceNow() {
        CaptureStateRouter r = router;
        if (r != null) {
            r.runOnce();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        if (overlayManager == null) {
            overlayManager = new OverlayManager(this);
        } else {
            // 重建时先清残留，避免双份悬浮球/幽灵窗（§4.1 约定 4）
            overlayManager.removeAllImmediate();
        }
        if (router == null) {
            router = new CaptureStateRouter(this, overlayManager,
                    () -> PpOcrEngineFactory.create(this),
                    () -> MtEngineFactory.create(this));
        }
        overlayManager.setOnSphereTap(() -> router.runOnce());
        overlayManager.showSphere();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 事件分发在后续里程碑接入 ToolRegistry
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        instance = null;
        if (overlayManager != null) {
            overlayManager.removeAllImmediate();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        if (router != null) {
            router.shutdown();
            router = null;
        }
        if (screenCapturer != null) {
            screenCapturer.shutdown();
            screenCapturer = null;
        }
        if (overlayManager != null) {
            overlayManager.removeAllImmediate();
            overlayManager = null;
        }
        super.onDestroy();
    }
}
