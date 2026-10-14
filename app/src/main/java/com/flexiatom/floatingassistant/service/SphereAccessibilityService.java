package com.flexiatom.floatingassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import com.flexiatom.floatingassistant.overlay.OverlayManager;

public class SphereAccessibilityService extends AccessibilityService {

    private static volatile boolean connected = false;

    private OverlayManager overlayManager;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        if (overlayManager == null) {
            overlayManager = new OverlayManager(this);
        }
        overlayManager.showSphere();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // M0 脚手架：事件分发在后续里程碑接入 ToolRegistry
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        if (overlayManager != null) {
            overlayManager.hideSphere();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (overlayManager != null) {
            overlayManager.hideSphere();
            overlayManager = null;
        }
        super.onDestroy();
    }
}
