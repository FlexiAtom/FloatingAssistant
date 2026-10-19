package com.flexiatom.floatingassistant.core;

/**
 * Hy-MT2 官方语向表（38 项：33 种互译 + 繁体中文 + 藏/哈萨克/蒙古/维吾尔/粤）。
 *
 * <p>抄自官方模型卡 {@code README_CN.md} 的「Supported Languages」表——缩写取官方列
 * （含非标准的 {@code zh-Hant}、{@code tl}=菲律宾语、{@code yue}=粤语），中文名逐字照卡。
 * **不放原生**：语向增减只改这一处，不必重编 {@code libmtjni.so}。
 *
 * <p>注意：本表是**上游外部事实**，不是本 app 的不变量；官方卡若改版，此处要跟着核。
 */
public final class LangCatalog {

    /**
     * 一个语向：官方缩写 + 面板显示的中文名 + 提示词用的官方英文名。
     *
     * <p>{@code enName} 不是装饰：官方句式示例明确要求「target_lang 用**全名**，英文提示词里用英文名」。
     * 面板给人看中文，喂模型的那一句用 {@code enName}，两件事各用各的字段，不做互译查找。
     */
    public static final class Lang {
        public final String code;
        public final String name;
        public final String enName;

        Lang(String code, String name, String enName) {
            this.code = code;
            this.name = name;
            this.enName = enName;
        }
    }

    public static final Lang[] ALL = {
            new Lang("zh", "中文", "Chinese"),
            new Lang("en", "英语", "English"),
            new Lang("fr", "法语", "French"),
            new Lang("pt", "葡萄牙语", "Portuguese"),
            new Lang("es", "西班牙语", "Spanish"),
            new Lang("ja", "日语", "Japanese"),
            new Lang("tr", "土耳其语", "Turkish"),
            new Lang("ru", "俄语", "Russian"),
            new Lang("ar", "阿拉伯语", "Arabic"),
            new Lang("ko", "韩语", "Korean"),
            new Lang("th", "泰语", "Thai"),
            new Lang("it", "意大利语", "Italian"),
            new Lang("de", "德语", "German"),
            new Lang("vi", "越南语", "Vietnamese"),
            new Lang("ms", "马来语", "Malay"),
            new Lang("id", "印尼语", "Indonesian"),
            new Lang("tl", "菲律宾语", "Filipino"),
            new Lang("hi", "印地语", "Hindi"),
            new Lang("zh-Hant", "繁体中文", "Traditional Chinese"),
            new Lang("pl", "波兰语", "Polish"),
            new Lang("cs", "捷克语", "Czech"),
            new Lang("nl", "荷兰语", "Dutch"),
            new Lang("km", "高棉语", "Khmer"),
            new Lang("my", "缅甸语", "Burmese"),
            new Lang("fa", "波斯语", "Persian"),
            new Lang("gu", "古吉拉特语", "Gujarati"),
            new Lang("ur", "乌尔都语", "Urdu"),
            new Lang("te", "泰卢固语", "Telugu"),
            new Lang("mr", "马拉地语", "Marathi"),
            new Lang("he", "希伯来语", "Hebrew"),
            new Lang("bn", "孟加拉语", "Bengali"),
            new Lang("ta", "泰米尔语", "Tamil"),
            new Lang("uk", "乌克兰语", "Ukrainian"),
            new Lang("bo", "藏语", "Tibetan"),
            new Lang("kk", "哈萨克语", "Kazakh"),
            new Lang("mn", "蒙古语", "Mongolian"),
            new Lang("ug", "维吾尔语", "Uyghur"),
            new Lang("yue", "粤语", "Cantonese"),
    };

    public static final String DEFAULT_SRC = "zh";
    public static final String DEFAULT_TGT = "en";

    private LangCatalog() {
    }

    /** 按缩写取语向；不在表内（旧版本 prefs 残留、手改）返回 {@code null}，由调用方兜默认。 */
    public static Lang find(String code) {
        if (code == null) {
            return null;
        }
        for (Lang l : ALL) {
            if (l.code.equals(code)) {
                return l;
            }
        }
        return null;
    }

    /** 取显示名，未知缩写回显原码（不让面板出现空白行）。 */
    public static String nameOf(String code) {
        Lang l = find(code);
        return l != null ? l.name : code;
    }
}
