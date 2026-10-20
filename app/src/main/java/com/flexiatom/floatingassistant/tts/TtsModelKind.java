package com.flexiatom.floatingassistant.tts;

import java.io.File;

/**
 * 端侧 TTS 的两个候选模型档（{@code tts-engine-replacement} 读法①的取证对象）。
 *
 * <ul>
 *   <li>{@link #AISHELL3} — vits-icefall-zh-aishell3：纯中文、多音色，不含 {@code rule.far} 时整目录 33 MB。</li>
 *   <li>{@link #MELO} — vits-melo-tts-zh_en：中英混读、单音色，sherpa 官方 48 kHz。</li>
 * </ul>
 *
 * <p>{@code dirName} 就是 {@code getExternalFilesDir(null)} 下的**目录名**，沿用上游 tar.bz2 解包后的原名，
 * 不再造一层本地别名（与 {@code mt.MtModelKind#fileName} 同一约定：名字必须与设备上落盘的完全一致）。
 */
public enum TtsModelKind {

    AISHELL3("vits-icefall-zh-aishell3", "aishell3", "中文多音色"),
    MELO("vits-melo-tts-zh_en", "melo", "中英混读");

    public final String dirName;
    /** 命令行短名（{@code am start --es tts_variant aishell3}），免得探针那侧要写全上游目录名。 */
    public final String alias;
    public final String displayName;

    TtsModelKind(String dirName, String alias, String displayName) {
        this.dirName = dirName;
        this.alias = alias;
        this.displayName = displayName;
    }

    /** 模型目录（tar 解包后的那一层，不是数据根）。 */
    public File dirOf(File base) {
        return new File(base, dirName);
    }

    /**
     * 按名取档；{@code null}/空/不认识一律返回 {@code null}，由调用方显式报错——
     * 兜成默认档会让"另一档根本没法加载"这一格永远测不到。
     */
    public static TtsModelKind fromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (TtsModelKind k : values()) {
            if (k.alias.equalsIgnoreCase(name) || k.dirName.equals(name)) {
                return k;
            }
        }
        return null;
    }
}
