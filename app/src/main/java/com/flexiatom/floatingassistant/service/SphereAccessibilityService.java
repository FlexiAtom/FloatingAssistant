package com.flexiatom.floatingassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.capture.ScreenCapturer;
import com.flexiatom.floatingassistant.core.BlacklistMatcher;
import com.flexiatom.floatingassistant.core.CaptureStateRouter;
import com.flexiatom.floatingassistant.core.SettingsStore;
import com.flexiatom.floatingassistant.ocr.PpOcrEngineFactory;
import com.flexiatom.floatingassistant.mt.MtModelHub;
import com.flexiatom.floatingassistant.overlay.FanMenuView;
import com.flexiatom.floatingassistant.overlay.OverlayManager;
import com.flexiatom.floatingassistant.settings.SettingsActivity;
import com.flexiatom.floatingassistant.tool.ContainerTool;
import com.flexiatom.floatingassistant.tool.SimpleTool;
import com.flexiatom.floatingassistant.tool.Tool;
import com.flexiatom.floatingassistant.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

public class SphereAccessibilityService extends AccessibilityService {

    private static volatile boolean connected = false;
    private static volatile SphereAccessibilityService instance;

    private OverlayManager overlayManager;
    private ScreenCapturer screenCapturer;
    private CaptureStateRouter router;
    /** 悬浮球同心环菜单的工具表（§10 M0 + #68）：注册顺序 = 环自上而下。 */
    private ToolRegistry tools;
    /** 模型档位/黑名单等设置的持久层（菜单叶子即时写 model_mode 用）。 */
    private SettingsStore settings;
    /** 当前展开的同心环页面栈：{@code menuStack.get(0)}=最内环=顶层工具；向外逐层为子页。主线程访问。 */
    private final List<List<Tool>> menuStack = new ArrayList<>();
    /** 同心环菜单的选择回调（叶子 run / 容器 push / 枢纽 pop / 环外关闭）。 */
    private final FanMenuView.Listener menuListener = new FanMenuView.Listener() {
        @Override
        public void onEntry(int depth, int index) {
            chooseTool(depth, index);
        }

        @Override
        public void onHub() {
            popOrCloseMenu();
        }

        @Override
        public void onOutside() {
            overlayManager.hideToolMenu();
        }
    };

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
        if (settings == null) {
            settings = new SettingsStore(this);
        }
        if (router == null) {
            MtModelHub mtHub = new MtModelHub(this, settings, new BlacklistMatcher(settings));
            router = new CaptureStateRouter(this, overlayManager,
                    () -> PpOcrEngineFactory.create(this), mtHub);
        }
        buildTools();
        // 点球 = 展开/收起同心环工具菜单（§10 M0 + #68），不再直接触发采集；采集改由菜单里选具体工具。
        overlayManager.setOnSphereTap(this::toggleToolMenu);
        overlayManager.showSphere();
    }

    /**
     * 构建工具树（§10 M0 + #68 径向树）：注册顺序 = 最内环自上而下。
     * 第1页 = [全屏翻译(叶子), 开发者选项(容器)]；
     * 开发者选项 → [整页OCR(叶子), 模型档位(容器), 黑名单(叶子→悬浮窗)]；
     * 模型档位 → [自动, 轻量, 重量](叶子→即时设 model_mode)。
     */
    private void buildTools() {
        tools = new ToolRegistry();
        Tool fullscreen = new SimpleTool("fullscreen_translate",
                getString(R.string.tool_fullscreen_translate), svc -> runInPlaceTracked());
        Tool pageOcr = new SimpleTool("page_ocr",
                getString(R.string.tool_page_ocr), svc -> triggerRun());
        Tool modelTier = new ContainerTool("model_tier",
                getString(R.string.tool_model_tier), List.of(
                        new SimpleTool("mode_auto", getString(R.string.tool_mode_auto),
                                svc -> setModelMode(SettingsStore.MODE_AUTO)),
                        new SimpleTool("mode_light", getString(R.string.tool_mode_light),
                                svc -> setModelMode(SettingsStore.MODE_FORCE_LIGHT)),
                        new SimpleTool("mode_heavy", getString(R.string.tool_mode_heavy),
                                svc -> setModelMode(SettingsStore.MODE_FORCE_HEAVY))));
        Tool blacklist = new SimpleTool("blacklist",
                getString(R.string.tool_blacklist), svc -> openBlacklist());
        Tool devOptions = new ContainerTool("developer_options",
                getString(R.string.tool_developer_options), List.of(pageOcr, modelTier, blacklist));
        tools.register(fullscreen);
        tools.register(devOptions);
    }

    /** 悬浮球点击：菜单已开则收，未开则以顶层工具为最内环弹出同心环菜单。 */
    private void toggleToolMenu() {
        if (overlayManager.isToolMenuVisible()) {
            overlayManager.hideToolMenu();
            return;
        }
        menuStack.clear();
        menuStack.add(tools.tools());
        overlayManager.showToolMenu(buildPages(), menuListener);
    }

    /**
     * 选中第 {@code depth} 环第 {@code index} 格：叶子→收菜单并 run；容器→截断到该层再压入其子项、原地刷新。
     * 回调在主线程（触摸事件）触发，menuStack 仅主线程访问，无并发。
     */
    private void chooseTool(int depth, int index) {
        if (depth < 0 || depth >= menuStack.size()) {
            return;
        }
        List<Tool> page = menuStack.get(depth);
        if (index < 0 || index >= page.size()) {
            return;
        }
        Tool t = page.get(index);
        if (t.isContainer()) {
            while (menuStack.size() > depth + 1) {
                menuStack.remove(menuStack.size() - 1);
            }
            menuStack.add(t.children());
            // 重建窗：页栈变深/变挤时外沿半径按内容自适应，窗尺寸须随之重算（原地刷新改不了窗大小）
            overlayManager.showToolMenu(buildPages(), menuListener);
        } else {
            overlayManager.hideToolMenu();
            t.run(this);
        }
    }

    /** 枢纽（球心）点击：非根层→pop 一层并刷新；根层→关闭整个菜单。 */
    private void popOrCloseMenu() {
        if (menuStack.size() > 1) {
            menuStack.remove(menuStack.size() - 1);
            overlayManager.showToolMenu(buildPages(), menuListener);
        } else {
            overlayManager.hideToolMenu();
        }
    }

    /** 把当前 menuStack（List&lt;List&lt;Tool&gt;&gt;）映射成视图层页面栈（解耦，视图不认识 Tool）。 */
    private List<FanMenuView.Page> buildPages() {
        List<FanMenuView.Page> pages = new ArrayList<>(menuStack.size());
        for (List<Tool> page : menuStack) {
            List<FanMenuView.Entry> entries = new ArrayList<>(page.size());
            for (Tool t : page) {
                entries.add(new FanMenuView.Entry(t.label(), t.isContainer()));
            }
            pages.add(new FanMenuView.Page(entries));
        }
        return pages;
    }

    /** 即时设模型档位并给浮层反馈；下一次采集 {@code MtModelHub.engineFor} 现读 mode 即生效。 */
    private void setModelMode(int mode) {
        settings.setMode(mode);
        String name;
        switch (mode) {
            case SettingsStore.MODE_FORCE_LIGHT:
                name = getString(R.string.tool_mode_light);
                break;
            case SettingsStore.MODE_FORCE_HEAVY:
                name = getString(R.string.tool_mode_heavy);
                break;
            default:
                name = getString(R.string.tool_mode_auto);
        }
        overlayManager.showTextOverlay("model_mode_feedback",
                getString(R.string.tool_model_tier) + " = " + name);
    }

    /** 原位全屏翻译（带切走即卸载记账）：记住本次目标 App，再走 M3 原位路径。 */
    private void runInPlaceTracked() {
        CaptureStateRouter r = router;
        if (r != null) {
            translatedPkg = lastTopPkg;
            r.runOnceInPlace();
        }
    }

    /**
     * 黑名单入口。#68-T8a 占位：暂跳 {@link SettingsActivity}（下一步 T8b 改为就地弹可获焦悬浮窗，
     * 消除反馈#2「子页与顶层页平级」的独立 Activity）。
     */
    private void openBlacklist() {
        Intent i = new Intent(this, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
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
        // 页面变化自动清除（§5.2-4）：外部 App 窗口状态变化（导航/换页）→ 上一轮原位贴图已错位，整组移除。
        // 只认 WINDOW_STATE_CHANGED（内容级 CONTENT_CHANGED 太噪：光标闪/列表动画会误清，留给软过期兜底）。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && overlayManager != null) {
            overlayManager.removeGroup(OverlayManager.GROUP_MT);
            overlayManager.hideToolMenu(); // 导航/换页时扇形菜单不该悬着（§10 M0）
        }
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
