package com.flexiatom.floatingassistant.mt;

import com.flexiatom.floatingassistant.core.LangCatalog;

/**
 * Hy-MT2 的提示词组装（官方卡「Translation Task Instruction Examples」里的 **Default Translation** 一行）。
 *
 * <p>句式逐字照官方英文列，只把 {@code {target_lang}} 换成 {@link LangCatalog#enName}：
 * 官方注释要求 target_lang 用**全名**，且英文提示词里用英文名。
 *
 * <p>两处口径说明（都是可核对的判断，不是随手定的）：
 * <ul>
 *   <li>官方表里那句 "only output the translated result…" 外面裹着 markdown 的 {@code **}，
 *       那是表格里的**加粗标记**，不是提示词正文，故不带进字符串。</li>
 *   <li>官方所有句式都**只有目标语言、没有源语言槽**——源语言由模型自行判定。
 *       所以面板上的「原语言」这一列不进提示词（见 m2-translation-settings 的未决项），
 *       本类不接受 srcCode，免得日后有人以为它生效了。</li>
 * </ul>
 *
 * <p>放在 Java 侧而不是 {@code mt-jni.cpp}：改句式不再需要过 NDK。
 */
public final class MtPrompt {

    private MtPrompt() {
    }

    /** 组装喂给模型的完整 user 消息（指令 + 空行 + 原文）。{@code tgtCode} 不在表内时按默认目标语兜。 */
    public static String userMessage(String tgtCode, String text) {
        LangCatalog.Lang lang = LangCatalog.find(tgtCode);
        if (lang == null) {
            lang = LangCatalog.find(LangCatalog.DEFAULT_TGT);
        }
        return "Translate the following text into " + lang.enName
                + ". Note that you should only output the translated result without any additional explanation:\n\n"
                + text;
    }
}
