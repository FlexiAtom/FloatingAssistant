package com.flexiatom.floatingassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.capture.ScreenCapturer;
import com.flexiatom.floatingassistant.core.BlacklistMatcher;
import com.flexiatom.floatingassistant.core.CaptureStateRouter;
import com.flexiatom.floatingassistant.core.RuntimeStats;
import com.flexiatom.floatingassistant.core.SettingsStore;
import com.flexiatom.floatingassistant.ocr.PpOcrEngineFactory;
import com.flexiatom.floatingassistant.mt.MtModelHub;
import com.flexiatom.floatingassistant.overlay.BlacklistPanelView;
import com.flexiatom.floatingassistant.overlay.FanMenuView;
import com.flexiatom.floatingassistant.overlay.OverlayManager;
import com.flexiatom.floatingassistant.overlay.RegionSelectView;
import com.flexiatom.floatingassistant.overlay.TextPanelView;
import com.flexiatom.floatingassistant.overlay.TranslatePanelView;
import com.flexiatom.floatingassistant.tool.ContainerTool;
import com.flexiatom.floatingassistant.tool.SimpleTool;
import com.flexiatom.floatingassistant.tool.Tool;
import com.flexiatom.floatingassistant.tool.ToolRegistry;
import com.flexiatom.floatingassistant.tts.TtsEngine;

import java.util.ArrayList;
import java.util.List;

public class SphereAccessibilityService extends AccessibilityService {

    private static volatile boolean connected = false;
    private static volatile SphereAccessibilityService instance;
    /** 反馈浮层的兜底时长：这类窗没有别的关闭通路，不自动收就永久挡在屏幕顶部（用户 2026-10-18 裁决 2s）。 */
    private static final long FEEDBACK_MS = 2000L;

    private OverlayManager overlayManager;
    private ScreenCapturer screenCapturer;
    private CaptureStateRouter router;
    /**
     * 模型中枢（档位路由 + 单引擎驻留）。从前是 {@code onCreate} 里的局部变量、只有 router 拿得到，
     * 于是"不截屏直接喂一句文本给模型"这类量测没有入口——提到字段，与 router 同一处建、同一生命周期。
     */
    private MtModelHub mtHub;
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

    /** 心跳与文本面板都在主线程 Handler 上跑（服务本身就在主线程 Looper，不另起线程）。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 每 {@link RuntimeStats#HEARTBEAT_MS} 打一条自回报日志并推一次心跳。
     * 被杀时 {@code onDestroy} 不保证执行，所以「服务活着」这件事得**主动留痕**，否则 24 h 验收无据可查。
     */
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            RuntimeStats.of(SphereAccessibilityService.this).beat();
            mainHandler.postDelayed(this, RuntimeStats.HEARTBEAT_MS);
        }
    };

    /** 文本面板当前正文（复制动作取它）：面板一屏只放一份文本，故单字段足够，不开列表管理。 */
    private String panelText = "";

    /** 生产朗读通道（tts-engine-replacement 裁二）：懒建；空闲 60 s / 服务解绑即卸载（峰值 401 MB，不常驻）。 */
    private TtsEngine ttsEngine;
    /** 当前面板的「朗读」动作：合成/播放期间把它原地改成「取消」，结束后恢复。 */
    private TextPanelView.Action speakAction;

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

    /**
     * 以程序方式触发一次**框选识别**（M1 演示/量测入口，与点「框选」后松手同一条路径）。
     * 回调给的是面板将显示的文本；量测方（adb intent）直接打日志即可。
     */
    public void runOnceNowRegion(int[] rect, java.util.function.Consumer<String> onText) {
        CaptureStateRouter r = router;
        if (r != null) {
            r.runOnceRegion(rect, onText);
        }
    }

    /** 部分截图量测入口（同 {@link #runOnceNowRegion}，走相册落盘那条）。 */
    public void runOnceNowShot(int[] rect, java.util.function.Consumer<String> onDone) {
        CaptureStateRouter r = router;
        if (r != null) {
            r.runOnceRegionShot(rect, onDone);
        }
    }

    /**
     * 扇形几何量测入口（{@code fan-menu-into-ball}）：把球摆到 {@code ballTopLeft}，弹出根菜单并按
     * {@code indexPath} 逐层压入子页，然后回调球与扇形的几何。**与点球的通路不同处仅在没经过手势**——
     * 合窗后的不变量（扇形圆心＝球心、贴右沿不被 WMS 钳、拖球扇形同行）必须能在不注入触摸的前提下量出来，
     * 手点仍归人。量测完由调用方自己 {@link #hideMenuForTest()} 收掉，免得留一扇模态窗挡住下层。
     */
    public void probeMenuGeometryAt(int[] ballTopLeft, int[] indexPath,
                                    java.util.function.Consumer<int[]> onProbe) {
        mainHandler.post(() -> {
            overlayManager.moveSphereTo(ballTopLeft[0], ballTopLeft[1]);
            menuStack.clear();
            menuStack.add(tools.tools());
            for (int index : indexPath) {
                List<Tool> page = menuStack.get(menuStack.size() - 1);
                if (index < 0 || index >= page.size() || !page.get(index).isContainer()) {
                    break;
                }
                menuStack.add(page.get(index).children());
            }
            overlayManager.showToolMenu(buildPages(), menuListener);
            if (onProbe != null) {
                // 探针要读的是**布局之后**的落位（getLocationOnScreen 在 addView 当刻还是旧值），
                // 故排到下一帧之后；400 ms 是「布局肯定走完」的保守值，不是产品逻辑。
                mainHandler.postDelayed(() -> onProbe.accept(overlayManager.sphereProbe()), 400);
            }
        });
    }

    /** 与 {@link #probeMenuGeometryAt} 配套：收扇形并把球窗缩回。 */
    public void hideMenuForTest() {
        mainHandler.post(overlayManager::hideToolMenu);
    }

    /**
     * 量测用：直接开/关「翻译设置」面板（m2-translation-settings T1/T4 要判"面板能不能开"与
     * "开着的模态后果"，而这条不该依赖人手点）。临时诊断入口，结案后可撤——与 {@code P5} 族同一处置。
     */
    public void openTranslatePanelForTest() {
        mainHandler.post(this::openTranslateSettings);
    }

    /** 量测用：关掉当前面板（{@link #openTranslatePanelForTest} 的另一半）。 */
    public void closePanelForTest() {
        mainHandler.post(overlayManager::hidePanel);
    }

    /**
     * 量测用（runtime-self-report 行 70「心跳不扰主线程」）：在主线程上以 200 ms 固定节拍自采样，
     * 跑满 {@code seconds} 后打一条漂移分布。
     *
     * <p>原方案是"心跳触发时连续框选 3 次，比对 {@code run_ms} 与无心跳基线"——那要手指，且"无心跳基线"
     * 根本没有（关掉心跳得改生产代码）。本采样直接量同一件事：心跳与采样跑在**同一条主线程队列**上，
     * 心跳若堵住队列，漂移就会在 60 s 周期处出尖峰。报出的 {@code maxWall} 是墙钟毫秒，与
     * {@code logcat -s RuntimeReport} 里 beat 行的时间戳对时即可判定尖峰是不是心跳造成的。
     */
    public void mainThreadJitterForTest(int seconds) {
        final int intervalMs = 200;
        mainHandler.post(new Runnable() {
            private final long startUptime = SystemClock.uptimeMillis();
            private final long startWall = System.currentTimeMillis();
            private long scheduled = startUptime;
            private int n;
            private long sum;
            private long maxDrift;
            private long maxWall;

            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                long drift = now - scheduled;
                if (n++ > 0) { // 第一拍 scheduled==startUptime，drift 恒 0，不计
                    sum += drift;
                    if (drift > maxDrift) {
                        maxDrift = drift;
                        maxWall = startWall + (now - startUptime);
                    }
                }
                if (now - startUptime >= seconds * 1000L) {
                    android.util.Log.i("P5JITTER", "n=" + (n - 1) + " spanS=" + seconds
                            + " mean=" + (sum / (n - 1)) + "ms max=" + maxDrift + "ms"
                            + " maxAt=" + maxWall + " beatEveryMs=" + RuntimeStats.HEARTBEAT_MS);
                    return;
                }
                scheduled = now + intervalMs;
                mainHandler.postDelayed(this, intervalMs);
            }
        });
    }

    /**
     * 量测用：在「翻译设置」面板里点一行，{@code spec} 就是语言缩写（如 {@code "ja"}）。
     * 走的是行自己的 {@code OnClickListener}（见 {@link TranslatePanelView#clickRowForTest}），
     * 所以这条只证「点了→落盘→读得回」，触摸命中测试仍归人。
     * 日志同时读回 prefs，把写路径和读路径一起量掉（T2 的存储半段）。
     */
    public void pickTranslateRowForTest(String code) {
        mainHandler.post(() -> {
            View panel = overlayManager.currentPanel();
            boolean found = panel instanceof TranslatePanelView tp && tp.clickRowForTest(code);
            android.util.Log.i("P5PANEL", "pick tgt:" + code
                    + " panel=" + (panel == null ? "none" : panel.getClass().getSimpleName())
                    + " rowFound=" + found
                    + " prefs=" + settings.tgtLang()
                    + " version=" + settings.version());
        });
    }

    /**
     * 当前**目标语言**缩写（{@code LangCatalog} 的 code），供翻译路径每次取用。
     *
     * <p>源语言不在这条链上：Hy-MT2 官方所有句式都只有 {@code {target_lang}}、没有源语言槽，
     * 源语言由模型自行判定，所以面板与 prefs 都只剩目标语言一列/一键。
     * 工作线程直接读它——SharedPreferences 是内存缓存，且 {@code settings} 在服务生命周期内不换实例。
     */
    public String targetLang() {
        return settings.tgtLang();
    }

    /**
     * 量测用（m2 第二步）：**不经截屏**把一句文本按指定语向喂给模型，结果打日志。
     *
     * <p>为什么不走 runOnce 那条：整页路径要先 MediaProjection 授权（那一按归用户），而本步要证的只是
     * 「语向真的进了提示词、模型出的是那种语言」。绕开采集，证据链更短，也不动已验稳的截图链路。
     * 模型加载在秒级，故另起线程，不落主线程。
     *
     * @param tgtCode 目标语言缩写；空串＝用当前 prefs 里存的那一个
     */
    public void translateTextForTest(String text, String tgtCode) {
        new Thread(() -> {
            String tgt = (tgtCode == null || tgtCode.isEmpty()) ? settings.tgtLang() : tgtCode;
            long t0 = System.nanoTime();
            try {
                com.flexiatom.floatingassistant.core.MtEngine m = mtHub.engineFor(text);
                boolean ready = m != null && m.isReady();
                String out = m == null ? null : m.translate(text, tgt);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                android.util.Log.i("P6MT", "tgt=" + tgt + " ready=" + ready
                        + " ms=" + ms + " in=[" + text + "] out=[" + out + "]");
            } catch (Throwable e) {
                android.util.Log.e("P6MT", "tgt=" + tgt + " failed after "
                        + ((System.nanoTime() - t0) / 1_000_000L) + "ms: " + e, e);
            }
        }, "p6-mt").start();
    }

    /**
     * 量测用：走生产 {@link TtsEngine#speak} 全链路（懒加载 → 分句 → 合成 → AudioTrack）。
     *
     * <p>存在的理由与 {@code p6_mt} 同：听感归人耳（长期约定），但"管线通不通"不该混在触摸测试里一起判。
     * 这条只证明加载/合成/播放状态机跑通；按钮命中与声音本身仍由人测。
     */
    public void speakTextForTest(String text) {
        mainHandler.post(() -> {
            android.util.Log.i("TTS", "speakForTest chars=" + text.length()
                    + " engine=" + (ttsEngine == null ? "cold" : "warm"));
            ttsEngine().speak(text);
        });
    }

    /** 球视图状态量测入口（用户报「收起扇形后球消失」）：回调 {@link OverlayManager#sphereProbe()}。 */
    public void probeSphereState(java.util.function.Consumer<int[]> onProbe) {
        mainHandler.post(() -> {
            if (onProbe != null) {
                onProbe.accept(overlayManager.sphereProbe());
            }
        });
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
            mtHub = new MtModelHub(this, settings, new BlacklistMatcher(settings));
            router = new CaptureStateRouter(this, overlayManager,
                    () -> PpOcrEngineFactory.create(this), mtHub);
        }
        buildTools();
        // 点球 = 展开/收起同心环工具菜单（§10 M0 + #68），不再直接触发采集；采集改由菜单里选具体工具。
        overlayManager.setOnSphereTap(this::toggleToolMenu);
        overlayManager.showSphere();
        // 自回报：起会话记账与心跳（runtime-self-report）。心跳先撤后布，防重连叠成两条节奏。
        RuntimeStats.of(this).sessionStarted();
        mainHandler.removeCallbacks(heartbeat);
        mainHandler.postDelayed(heartbeat, RuntimeStats.HEARTBEAT_MS);
    }

    /**
     * 构建工具树（§10 M0 + #68 径向树）：注册顺序 = 最内环自上而下。
     * 第1页 = [全屏翻译(叶子), 框选(容器), 翻译设置(叶子→悬浮窗), 开发者选项(容器)]；
     * 开发者选项 → [整页OCR(叶子), 模型档位(容器), 黑名单(叶子→悬浮窗), 自检(叶子→悬浮窗), 关闭切换动画(叶子→悬浮窗反馈)]；
     * 模型档位 → [自动, 轻量, 重量](叶子→即时设 model_mode)。
     */
    private void buildTools() {
        tools = new ToolRegistry();
        Tool fullscreen = new SimpleTool("fullscreen_translate",
                getString(R.string.tool_fullscreen_translate), svc -> runInPlaceTracked());
        Tool region = new ContainerTool("region_select",
                getString(R.string.tool_region_select), List.of(
                        new SimpleTool("partial_screenshot", getString(R.string.tool_partial_screenshot),
                                svc -> startRegionSelect(true)),
                        new SimpleTool("ocr_copy", getString(R.string.tool_ocr_copy),
                                svc -> startRegionSelect(false))));
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
        Tool translateSettings = new SimpleTool("translate_settings",
                getString(R.string.tool_translate_settings), svc -> openTranslateSettings());
        Tool report = new SimpleTool("runtime_report",
                getString(R.string.tool_runtime_report), svc -> openReport());
        Tool closeAnim = new SimpleTool("close_anim",
                getString(R.string.tool_close_anim), svc -> toggleAnimScale());
        Tool devOptions = new ContainerTool("developer_options",
                getString(R.string.tool_developer_options),
                List.of(pageOcr, modelTier, blacklist, report, closeAnim));
        tools.register(fullscreen);
        tools.register(region);
        tools.register(translateSettings);
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
                getString(R.string.tool_model_tier) + " = " + name, FEEDBACK_MS);
    }

    /** 系统「动画时长缩放」三键：关则三者同时为 0，恢复则同时回 1.0。 */
    private static final String[] ANIM_SCALE_KEYS = {
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale"};

    /**
     * 切换系统转场动画开关（在途/收起都归这三键管），双向：三项全 0 就恢复成 1.0，否则压成 0。
     * 结果一律走浮层反馈——写 {@code Settings.Global} 需要 WRITE_SECURE_SETTINGS，
     * 声明不等于获得（无障碍服务身份也不附带它），没授权时只可能 adb 授一次，故把命令原样打进反馈里。
     */
    public void toggleAnimScale() {
        boolean allOff = true;
        for (String key : ANIM_SCALE_KEYS) {
            if (Settings.Global.getFloat(getContentResolver(), key, 1f) != 0f) {
                allOff = false;
                break;
            }
        }
        float value = allOff ? 1f : 0f;
        boolean ok = true;
        try {
            for (String key : ANIM_SCALE_KEYS) {
                ok &= Settings.Global.putFloat(getContentResolver(), key, value);
            }
        } catch (SecurityException e) {
            ok = false;
        }
        String head = getString(R.string.tool_close_anim) + "：";
        overlayManager.showTextOverlay("anim_scale_feedback", ok
                ? head + (value == 0f ? "已关闭（三键缩放 = 0）" : "已恢复（三键缩放 = 1.0）")
                : head + "未生效——缺 WRITE_SECURE_SETTINGS，授一次：adb shell pm grant "
                        + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS", FEEDBACK_MS);
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
     * 黑名单就地编辑面板（#68-T8b）：不再另起 {@code SettingsActivity}——那会让「子页」与悬浮层平级、
     * 且把用户从正在读的页面上带走（反馈#2）。改为在**当前页之上**弹一扇可获焦的居中面板；
     * 面板已在显示时再选本项=收起（当开关用）。
     *
     * <p>增删即时经 {@link SettingsStore#setBlacklist} 落盘；下一次采集时 {@code BlacklistMatcher}
     * 按 {@code version()} 现读重编译，**无需重启服务**。
     */
    private void openBlacklist() {
        if (overlayManager.isPanelVisible()) {
            overlayManager.hidePanel();
            return;
        }
        // 内容视图用 OverlayManager 的 M3 上下文构造（不是服务本身）：服务上下文没有 app 主题，
        // 面板里读 colorSurfaceContainerHigh 这类角色属性会读不到值（md3-dynamic-color）。
        overlayManager.showPanel(new BlacklistPanelView(overlayManager.themedContext(), settings.blacklist(),
                new BlacklistPanelView.Listener() {
                    @Override
                    public void onSave(List<SettingsStore.Entry> entries) {
                        settings.setBlacklist(entries);
                    }

                    @Override
                    public void onClose() {
                        overlayManager.hidePanel();
                    }

                    @Override
                    public void onMove(int dx, int dy) {
                        overlayManager.movePanel(dx, dy);
                    }
                }));
    }

    /**
     * 框选（§10 M1 闭环第一步）：弹全屏选层，拖出矩形即「截图 → 裁剪 → OCR → 文本面板」。
     *
     * <p>选区窗必须在派发本次运行**之前**收掉：它带半透明遮罩，被拍进源图就等于给 OCR 掺了一层灰。
     * {@code captureExcludingSelf} 摘的是当时仍挂在 {@code views} 里的窗，收掉即自然不含它。
     */
    private void startRegionSelect(final boolean shot) {
        overlayManager.showRegionSelect(getString(R.string.region_hint),
                new RegionSelectView.Listener() {
                    @Override
                    public void onSelected(int x0, int y0, int x1, int y1) {
                        overlayManager.hideRegionSelect();
                        CaptureStateRouter r = router;
                        if (r == null) {
                            return;
                        }
                        translatedPkg = lastTopPkg;
                        int[] rect = new int[]{x0, y0, x1, y1};
                        if (shot) {
                            r.runOnceRegionShot(rect,
                                    text -> overlayManager.showTextOverlay("shot_feedback", text, FEEDBACK_MS));
                        } else {
                            r.runOnceRegion(rect,
                                    text -> showTextPanel(getString(R.string.region_result_title),
                                            text, true));
                        }
                    }

                    @Override
                    public void onCancelled() {
                        overlayManager.hideRegionSelect();
                    }
                });
    }

    /** 自检报告（runtime-self-report 的人读出口）：同一份 {@code report()} 既喂面板也喂 logcat，两处真值同源。 */
    /**
     * 语向面板（m2-translation-settings 第一步）：形状照 {@link #openBlacklist()}——同一张模态面板窗，
     * 内容视图用 {@code themedContext()} 构造（服务上下文读不到 M3 角色属性）。
     * 点一行即刻落盘，不设「保存」按钮（与黑名单面板同一取向：改了就存）。
     */
    private void openTranslateSettings() {
        if (overlayManager.isPanelVisible()) {
            overlayManager.hidePanel();
            return;
        }
        overlayManager.showPanel(new TranslatePanelView(overlayManager.themedContext(),
                settings.tgtLang(),
                new TranslatePanelView.Listener() {
                    @Override
                    public void onPick(String code) {
                        settings.setTgtLang(code);
                    }

                    @Override
                    public void onClose() {
                        overlayManager.hidePanel();
                    }

                    @Override
                    public void onMove(int dx, int dy) {
                        overlayManager.movePanel(dx, dy);
                    }
                }));
    }

    /** 量测用：开「自检」面板（runtime-self-report 行 71 要判"面板正文与 logcat 同一出口"）。临时诊断入口，与 {@code P5} 族同批撤。 */
    public void openReportPanelForTest() {
        mainHandler.post(this::openReport);
        // 正文区可滚动，截图只看得到首屏；把整串另打一行，比对就不依赖手指滚动
        mainHandler.postDelayed(() -> {
            View panel = overlayManager.currentPanel();
            android.util.Log.i("P5PANEL", "report body=["
                    + (panel instanceof TextPanelView tv ? tv.bodyText() : "n/a") + "]");
        }, 400);
    }

    /**
     * 量测用：走「点正文框拉键盘」这条真实路径（面板没开就先开），把 {@link TextPanelView} 返回的
     * IME 状态串打进日志——配合外部 {@code dumpsys input_method} 判「无障碍覆盖层窗能否收 IME」
     * （overlay-ime-input-failure 的地基格）。临时诊断入口，与 {@code P5} 族同批撤。
     */
    public void imeProbeForTest() {
        boolean wasVisible = overlayManager.isPanelVisible();
        if (!wasVisible) {
            mainHandler.post(this::openReport);
        }
        mainHandler.postDelayed(() -> {
            View panel = overlayManager.currentPanel();
            if (!(panel instanceof TextPanelView tv)) {
                android.util.Log.e("P5IME", "panel not a TextPanelView (visible=" + wasVisible + ")");
                return;
            }
            android.util.Log.i("P5IME", "tap body → " + tv.imeTapForTest());
        }, wasVisible ? 60 : 500);
    }

    /**
     * 量测用：证「复制取到的是**当前**正文」这条行为变更点。开面板 → 把正文换成带标记的串（模拟就地
     * 改过字）→ 点正文框（真实 showSoftInput 路径）→ 走复制动作 → 回读剪贴板。IME 上屏那一格仍归人，
     * 这里只证取值口与剪贴板写入。临时诊断入口，与 {@code P5} 族同批撤。
     */
    public void editableCopyProbeForTest(String marker) {
        boolean wasVisible = overlayManager.isPanelVisible();
        if (!wasVisible) {
            mainHandler.post(this::openReport);
        }
        mainHandler.postDelayed(() -> {
            View panel = overlayManager.currentPanel();
            if (!(panel instanceof TextPanelView tv)) {
                android.util.Log.e("P5EDIT", "panel not a TextPanelView (visible=" + wasVisible + ")");
                return;
            }
            String initial = tv.bodyText();
            tv.setBodyForTest(marker);
            tv.imeTapForTest();
            copyPanelText();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            String got = cd == null || cd.getItemCount() == 0
                    ? "<null>" : cd.getItemAt(0).coerceToText(this).toString();
            android.util.Log.i("P5EDIT", "initialLen=" + initial.length() + " marker=" + marker
                    + " clip=" + got + " clipIsMarker=" + marker.equals(got)
                    + " clipIsInitial=" + initial.equals(got));
        }, wasVisible ? 60 : 500);
    }

    private void openReport() {
        if (overlayManager.isPanelVisible()) {
            overlayManager.hidePanel();
            return;
        }
        String report = RuntimeStats.of(this).report();
        android.util.Log.i("RuntimeReport", "\n" + report);
        showTextPanel(getString(R.string.report_title), report);
    }

    /** 文本面板（框选结果 / 自检报告共用一扇）：正文留一份初值，供面板已收起时「复制」兜底取用。 */
    private void showTextPanel(String title, String body) {
        showTextPanel(title, body, false);
    }

    /**
     * @param speakable 是否带「朗读」（框选结果带；自检报告不带——报告不是朗读对象，且 melo RTF&gt;1，
     *                  把 40 行报告交给它是分钟级等待，不在裁二划定的"朗读这一框"里）。按钮是**开关**：
     *                  正在读时再按＝停；朗读取面板**当前**正文，用户就地改过的字也会读到。
     */
    private void showTextPanel(String title, String body, boolean speakable) {
        panelText = body;
        List<TextPanelView.Action> actions = new ArrayList<>();
        if (speakable && settings.ttsEnabled()) {
            boolean speakingNow = ttsEngine != null && ttsEngine.isSpeaking();
            TextPanelView.Action speak = new TextPanelView.Action(
                    getString(speakingNow ? R.string.action_cancel : R.string.action_speak), () -> {
                TtsEngine te = ttsEngine();
                if (te.isSpeaking()) {
                    te.stop();
                    updateSpeakActionLabel(false);
                    overlayManager.showTextOverlay("tts_feedback",
                            getString(R.string.tts_stopped_feedback), FEEDBACK_MS);
                } else {
                    updateSpeakActionLabel(true);
                    te.speak(overlayManager.panelBodyText(panelText));
                    overlayManager.showTextOverlay("tts_feedback",
                            getString(R.string.tts_synth_feedback), FEEDBACK_MS);
                }
            });
            speakAction = speak;
            actions.add(speak);
        } else {
            speakAction = null;
        }
        actions.add(new TextPanelView.Action(getString(R.string.action_copy), this::copyPanelText));
        actions.add(new TextPanelView.Action(getString(R.string.action_close), () -> {
            stopSpeaking();
            speakAction = null;
            overlayManager.hidePanel();
        }));
        overlayManager.showPanel(new TextPanelView(overlayManager.themedContext(), title, body,
                actions,
                new TextPanelView.Listener() {
                    @Override
                    public void onClose() {
                        stopSpeaking();
                        speakAction = null;
                        overlayManager.hidePanel();
                    }

                    @Override
                    public void onMove(int dx, int dy) {
                        overlayManager.movePanel(dx, dy);
                    }
                }));
    }

    /** 面板朗读开关的引擎入口：`tts_feedback` 用带超时的反馈浮层，否则 2 s 后自收（项目惯例）。 */
    private TtsEngine ttsEngine() {
        if (ttsEngine == null) {
            ttsEngine = new TtsEngine(this, settings, new TtsEngine.Listener() {
                @Override
                public void onNotice(String text) {
                    if (overlayManager != null) {
                        overlayManager.showTextOverlay("tts_feedback", text, FEEDBACK_MS);
                    }
                }

                @Override
                public void onSpeakingChanged(boolean speaking) {
                    updateSpeakActionLabel(speaking);
                }
            });
        }
        return ttsEngine;
    }

    /** 按钮是状态开关：合成/播放全程「取消」，结束、停止或失败后恢复「朗读」。 */
    private void updateSpeakActionLabel(boolean speaking) {
        TextPanelView.Action action = speakAction;
        if (action != null) {
            action.setLabel(getString(speaking ? R.string.action_cancel : R.string.action_speak));
        }
    }

    private void stopSpeaking() {
        if (ttsEngine != null) {
            ttsEngine.stop();
        }
    }

    /**
     * 复制面板正文到系统剪贴板——M1 框选的主输出。取**当前**正文而非灌入时的快照：面板可编辑，
     * 用户改过的字必须能复制走，否则「就地改错字」这条流程是断的。
     * 无障碍服务不是前台 App，而 Android 10+ 对剪贴板有前台限制；**写**已真机验证可跨 App 粘贴
     * （2026-10-21 人测，见 working/m1-region-select.md 反证表第 3 行）。
     */
    private void copyPanelText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("floating-assistant",
                overlayManager.panelBodyText(panelText)));
        overlayManager.showTextOverlay("clipboard_feedback",
                getString(R.string.copied_to_clipboard), FEEDBACK_MS);
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
        String prevPkg = lastTopPkg;
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
        // 朗读同一条内存纪律：真的换了前台 App 才卸（同包内的窗口变化不算），朗读中不打断。
        if (prevPkg != null && !prevPkg.equals(pkg) && ttsEngine != null) {
            ttsEngine.unloadIfIdle();
        }
    }

    @Override
    public void onInterrupt() {
    }

    /** 撤心跳并结算会话（{@code onUnbind} 与 {@code onDestroy} 常连着来，结算本身幂等）。 */
    private void stopReporting() {
        mainHandler.removeCallbacks(heartbeat);
        RuntimeStats.of(this).sessionEnded();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        instance = null;
        stopReporting();
        if (ttsEngine != null) {
            ttsEngine.unload();
        }
        if (overlayManager != null) {
            overlayManager.removeAllImmediate();
        }
        return super.onUnbind(intent);
    }

    /**
     * 方向变化（系统自动旋转开着时，人把手机侧过来就会触发）：球窗与扇形的位置都是按**旧方向**算出来的，
     * 当场交给 {@link OverlayManager#onScreenRotation()} 纠正，否则球会停在屏外＝「球消失」。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlayManager != null) {
            mainHandler.post(overlayManager::onScreenRotation);
        }
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        stopReporting();
        if (router != null) {
            router.shutdown();
            router = null;
        }
        if (screenCapturer != null) {
            screenCapturer.shutdown();
            screenCapturer = null;
        }
        if (ttsEngine != null) {
            ttsEngine.shutdown();
            ttsEngine = null;
        }
        if (overlayManager != null) {
            overlayManager.removeAllImmediate();
            overlayManager = null;
        }
        super.onDestroy();
    }
}
