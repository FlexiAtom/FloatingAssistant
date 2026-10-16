package com.flexiatom.floatingassistant.mt;

import android.content.Context;
import android.util.Log;

import com.flexiatom.floatingassistant.core.BlacklistMatcher;
import com.flexiatom.floatingassistant.core.MtEngine;
import com.flexiatom.floatingassistant.core.SettingsStore;

/**
 * 分场景模型中枢：整页级路由 + <b>同一时刻只驻留一个引擎</b>。
 *
 * <p>路由优先级：人工模式（{@code FORCE_LIGHT}/{@code FORCE_HEAVY}）&gt; 自动
 * （命中黑名单 → {@link MtModelKind#HEAVY}，否则 {@link MtModelKind#LIGHT}）。
 *
 * <p>目标模型与当前驻留不同 → 先 {@code close()} 旧引擎再懒建新引擎。刻意不双驻：
 * Q4 与 1.25-bit 同时在内存会把前台应用挤掉（6GB 设备实测约束），换面重载（1.6s/6.5s）可接受。
 *
 * <p>仅在 {@code CaptureStateRouter} 的单工作线程上调用，无并发，故不加锁。
 */
public final class MtModelHub {

    private static final String TAG = "MtHub";

    private final Context ctx;
    private final SettingsStore settings;
    private final BlacklistMatcher matcher;

    private MtEngine engine;
    private MtModelKind loadedKind;

    public MtModelHub(Context ctx, SettingsStore settings, BlacklistMatcher matcher) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.matcher = matcher;
    }

    /** 按整页文本解析目标模型，必要时换面，返回可用引擎（最坏为 passthrough）。 */
    public MtEngine engineFor(String pageText) {
        boolean matched = matcher.matches(pageText);
        MtModelKind want = resolve(matched);
        if (engine == null || loadedKind != want) {
            swap(want);
        }
        Log.i(TAG, "route=" + loadedKind + " matched=" + matched
                + " mode=" + settings.mode() + " pageChars="
                + (pageText == null ? 0 : pageText.length()));
        return engine;
    }

    private MtModelKind resolve(boolean matched) {
        switch (settings.mode()) {
            case SettingsStore.MODE_FORCE_LIGHT:
                return MtModelKind.LIGHT;
            case SettingsStore.MODE_FORCE_HEAVY:
                return MtModelKind.HEAVY;
            default:
                return matched ? MtModelKind.HEAVY : MtModelKind.LIGHT;
        }
    }

    private void swap(MtModelKind want) {
        if (engine != null) {
            engine.close();
        }
        MtEngine created = MtEngineFactory.create(ctx, want);
        // 目标不可用（缺文件/加载失败）→ 试另一槽，仍不行才保留 passthrough
        if (!created.isReady()) {
            MtModelKind alt = want == MtModelKind.HEAVY ? MtModelKind.LIGHT : MtModelKind.HEAVY;
            MtEngine fallback = MtEngineFactory.create(ctx, alt);
            if (fallback.isReady()) {
                created.close();
                created = fallback;
                want = alt;
                Log.i(TAG, "降级到另一槽: " + alt);
            } else {
                fallback.close();
            }
        }
        engine = created;
        loadedKind = want;
    }

    public void close() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        loadedKind = null;
    }
}
