package com.flexiatom.floatingassistant.mt;

/**
 * 端侧 MT 的两个模型槽。
 *
 * <ul>
 *   <li>{@link #LIGHT} — STQ 1.25-bit，日用默认：体积小、冷加载快，短自然文本足够。</li>
 *   <li>{@link #HEAVY} — Q4_K_M，密集/技术页升级用：能力更强，代价是体积与冷加载。</li>
 * </ul>
 *
 * <p>{@code fileName} 是 {@code getExternalFilesDir(null)} 下的实际文件名，须与设备上落盘的一致。
 */
public enum MtModelKind {

    LIGHT("Hy-MT2-1.8B-1.25Bit.gguf", "轻量 1.25-bit"),
    HEAVY("Hy-MT2-1.8B-Q4_K_M.gguf", "Q4_K_M");

    public final String fileName;
    public final String displayName;

    MtModelKind(String fileName, String displayName) {
        this.fileName = fileName;
        this.displayName = displayName;
    }
}
