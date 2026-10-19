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

    /** 一个语向：官方缩写 + 面板显示的中文名。 */
    public static final class Lang {
        public final String code;
        public final String name;

        Lang(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    public static final Lang[] ALL = {
            new Lang("zh", "中文"),
            new Lang("en", "英语"),
            new Lang("fr", "法语"),
            new Lang("pt", "葡萄牙语"),
            new Lang("es", "西班牙语"),
            new Lang("ja", "日语"),
            new Lang("tr", "土耳其语"),
            new Lang("ru", "俄语"),
            new Lang("ar", "阿拉伯语"),
            new Lang("ko", "韩语"),
            new Lang("th", "泰语"),
            new Lang("it", "意大利语"),
            new Lang("de", "德语"),
            new Lang("vi", "越南语"),
            new Lang("ms", "马来语"),
            new Lang("id", "印尼语"),
            new Lang("tl", "菲律宾语"),
            new Lang("hi", "印地语"),
            new Lang("zh-Hant", "繁体中文"),
            new Lang("pl", "波兰语"),
            new Lang("cs", "捷克语"),
            new Lang("nl", "荷兰语"),
            new Lang("km", "高棉语"),
            new Lang("my", "缅甸语"),
            new Lang("fa", "波斯语"),
            new Lang("gu", "古吉拉特语"),
            new Lang("ur", "乌尔都语"),
            new Lang("te", "泰卢固语"),
            new Lang("mr", "马拉地语"),
            new Lang("he", "希伯来语"),
            new Lang("bn", "孟加拉语"),
            new Lang("ta", "泰米尔语"),
            new Lang("uk", "乌克兰语"),
            new Lang("bo", "藏语"),
            new Lang("kk", "哈萨克语"),
            new Lang("mn", "蒙古语"),
            new Lang("ug", "维吾尔语"),
            new Lang("yue", "粤语"),
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
