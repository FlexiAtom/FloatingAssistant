/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of FloatingAssistant.
 *
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.flexiatom.floatingassistant.core;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 黑名单匹配：整页 OCR 文本命中任一规则即判「需升级到重模型」。
 *
 * <p>按 {@link SettingsStore#version()} 缓存已编译 {@link Pattern}，设置没变不重复编译。
 * 字面关键词走 {@link Pattern#quote}，正则原样编译；两者都 {@code CASE_INSENSITIVE}。
 * 坏正则（{@link java.util.regex.PatternSyntaxException}）跳过该条并记日志，绝不抛穿。
 */
public final class BlacklistMatcher {

    private static final String TAG = "Blacklist";

    private final SettingsStore settings;
    private int cachedVersion = -1;
    private List<Pattern> cached = new ArrayList<>();

    public BlacklistMatcher(SettingsStore settings) {
        this.settings = settings;
    }

    /** 任一规则在 {@code text} 中找到子串即 true；空黑名单或 null 文本 → false。 */
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        List<Pattern> patterns = patterns();
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private List<Pattern> patterns() {
        int v = settings.version();
        if (v != cachedVersion) {
            cached = compile(settings.blacklist());
            cachedVersion = v;
        }
        return cached;
    }

    private static List<Pattern> compile(List<SettingsStore.Entry> entries) {
        List<Pattern> out = new ArrayList<>(entries.size());
        for (SettingsStore.Entry e : entries) {
            try {
                String body = e.regex ? e.pattern : Pattern.quote(e.pattern);
                out.add(Pattern.compile(body, Pattern.CASE_INSENSITIVE));
            } catch (RuntimeException err) {
                Log.w(TAG, "跳过坏规则(" + (e.regex ? "re:" : "kw:") + e.pattern + "): "
                        + err.getClass().getSimpleName() + ": " + err.getMessage());
            }
        }
        return out;
    }
}
