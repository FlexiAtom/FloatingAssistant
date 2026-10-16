package com.flexiatom.floatingassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import com.flexiatom.floatingassistant.capture.ScreenCapturer;
import com.flexiatom.floatingassistant.core.BlacklistMatcher;
import com.flexiatom.floatingassistant.core.CaptureStateRouter;
import com.flexiatom.floatingassistant.core.SettingsStore;
import com.flexiatom.floatingassistant.ocr.PpOcrEngineFactory;
import com.flexiatom.floatingassistant.mt.MtModelHub;
import com.flexiatom.floatingassistant.overlay.OverlayManager;

public class SphereAccessibilityService extends AccessibilityService {

    private static volatile boolean connected = false;
    private static volatile SphereAccessibilityService instance;

    private OverlayManager overlayManager;
    private ScreenCapturer screenCapturer;
    private CaptureStateRouter router;

    /** 最近一次窗口状态变化看到的顶层外部 App 包名（忽略自身覆盖层）。用于"切走即卸载"判定。 */
    private volatile String lastTopPkg;
    /** 本次采集/翻译针对的 App 包名；顶层变为别的 App 且空闲时 → 释放常驻模型。 */
    private volatile String translatedPkg;

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
        triggerRun();
    }

    /** 触发一次**原位贴图**采集（M3 演示入口，独立于默认整页路径）。 */
    public void runOnceNowInPlace() {
        CaptureStateRouter r = router;
        if (r != null) {
            r.runOnceInPlace();
        }
    }

    /** 采集触发统一入口：先记住"这次翻的是哪个 App"，供切走即卸载判定。 */
    private void triggerRun() {
        CaptureStateRouter r = router;
        if (r != null) {
            translatedPkg = lastTopPkg;
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
            SettingsStore settings = new SettingsStore(this);
            MtModelHub mtHub = new MtModelHub(this, settings, new BlacklistMatcher(settings));
            router = new CaptureStateRouter(this, overlayManager,
                    () -> PpOcrEngineFactory.create(this), mtHub);
        }
        overlayManager.setOnSphereTap(this::triggerRun);
        overlayManager.showSphere();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        String pkg = (p == null) ? null : p.toString();
        // 忽略自身（悬浮球/覆盖层/Toast 也报本包名，否则每次弹层都会误卸载）与空包名
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) {
            return;
        }
        lastTopPkg = pkg;
        // 前台切走即卸载：翻译过的 App 不再是顶层 → 空闲时释放常驻模型内存（§5.5 修订）。
        // 翻译进行中不打断（返回 false，保留 translatedPkg，下次顶层变化再试；长翻译可被悬浮球取消）。
        String t = translatedPkg;
        if (t != null && !t.equals(pkg)) {
            CaptureStateRouter r = router;
            if (r != null && r.unloadIfIdle()) {
                translatedPkg = null;
            }
        }
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
