package com.flexiatom.floatingassistant.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用设置的持久层（SharedPreferences，项目此前无任何 prefs）。
 *
 * <p>存两样：{@link #mode() 模型选择模式} 与 {@link #blacklist() 黑名单}。每次写自增
 * {@link #version()}，供 {@link BlacklistMatcher} 判断已编译 Pattern 缓存是否失效。
 *
 * <p>黑名单序列化：prefs 存单 String，每条一行，前缀 {@code kw:}=字面关键词、{@code re:}=正则；
 * pattern 内不得含换行（含则该行按字面截断处理，避免破坏格式）。
 */
public final class SettingsStore {

    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE_LIGHT = 1;
    public static final int MODE_FORCE_HEAVY = 2;

    private static final String PREFS = "mt_settings";
    private static final String KEY_MODE = "model_mode";
    private static final String KEY_BLACKLIST = "blacklist";
    private static final String KEY_VERSION = "version";

    /** 一条黑名单规则：{@code regex=false} 按字面关键词匹配，{@code true} 按正则匹配。 */
    public static final class Entry {
        public final boolean regex;
        public final String pattern;

        public Entry(boolean regex, String pattern) {
            this.regex = regex;
            this.pattern = pattern;
        }
    }

    private final SharedPreferences prefs;

    public SettingsStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int mode() {
        return prefs.getInt(KEY_MODE, MODE_AUTO);
    }

    public void setMode(int mode) {
        int v = mode == MODE_FORCE_LIGHT || mode == MODE_FORCE_HEAVY ? mode : MODE_AUTO;
        prefs.edit().putInt(KEY_MODE, v).putInt(KEY_VERSION, version() + 1).apply();
    }

    public int version() {
        return prefs.getInt(KEY_VERSION, 0);
    }

    public List<Entry> blacklist() {
        String raw = prefs.getString(KEY_BLACKLIST, "");
        List<Entry> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("re:")) {
                out.add(new Entry(true, line.substring(3)));
            } else if (line.startsWith("kw:")) {
                out.add(new Entry(false, line.substring(3)));
            }
            // 无合法前缀的行按字面关键词兜底，别静默丢用户输入
            else {
                out.add(new Entry(false, line));
            }
        }
        return out;
    }

    public void setBlacklist(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            String p = e.pattern == null ? "" : e.pattern.replace('\n', ' ');
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.regex ? "re:" : "kw:").append(p);
        }
        prefs.edit().putString(KEY_BLACKLIST, sb.toString())
                .putInt(KEY_VERSION, version() + 1).apply();
    }
}
