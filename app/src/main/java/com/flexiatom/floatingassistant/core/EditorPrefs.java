package com.flexiatom.floatingassistant.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 编辑器显示档（E6 ⋮ 的开关类）。
 *
 * <p><b>为什么另开 prefs 而不并进 {@link SettingsStore}</b>：后者的 {@code version()} 是
 * {@code BlacklistMatcher} 判 Pattern 缓存失效的信号，每翻一次显示开关就自增会让黑名单每轮重编译
 * （{@link TuningStore} 当年为同一理由分家，这里沿用同一口径）。
 *
 * <p>这里存的是**全局档**（跨文档一致）；「只读模式」按篇生效，落 E5 索引的行尾列，不在这里。
 * 读侧现读不设监听：编辑器视图一次只有一个，进面板时读一次就够。
 */
public final class EditorPrefs {

    private static final String PREFS = "app_editor";
    private static final String K_WRAP = "wrap";
    private static final String K_SMOOTH = "smooth";

    private final SharedPreferences prefs;

    public EditorPrefs(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 自动换行：默认开（＝当下的既有行为，翻成关才有变化）。 */
    public boolean wrap() {
        return prefs.getBoolean(K_WRAP, true);
    }

    public void setWrap(boolean on) {
        prefs.edit().putBoolean(K_WRAP, on).apply();
    }

    /** 流畅模式：默认关。开着＝屏蔽输入法联想与提取窗、并不绘选区气泡与手柄（换性能）。 */
    public boolean smooth() {
        return prefs.getBoolean(K_SMOOTH, false);
    }

    public void setSmooth(boolean on) {
        prefs.edit().putBoolean(K_SMOOTH, on).apply();
    }
}
