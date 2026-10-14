package com.flexiatom.floatingassistant.beast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 兼容性基线：期望串取自兽音译者主流格式参考实现（默认字典 {@code 嗷呜啊~}
 * 主流封装 头缀 ~呜嗷 + 正文 + 尾缀 啊），逐字符比对，证明净室 Java 实现与主流工具互通。
 */
public class BeastCodecTest {

    private static final String[][] GOLDEN = {
        {"", "~呜嗷啊"},
        {"A", "~呜嗷嗷嗷嗷呜呜啊呜嗷啊"},
        {"hi", "~呜嗷嗷嗷嗷呜啊嗷啊~呜嗷呜呜~嗷嗷嗷啊"},
        {"中文", "~呜嗷呜嗷~~呜嗷嗷嗷啊啊啊啊~啊~啊啊"},
        {"你好，世界！", "~呜嗷呜嗷嗷嗷啊嗷嗷~啊呜~啊~呜呜嗷呜~啊嗷啊啊呜~嗷嗷啊~~~呜呜呜~呜啊呜啊~~嗷~呜嗷呜啊啊嗷啊"},
        {"混合ab1中文😀", "~呜嗷呜啊~啊嗷呜啊啊啊呜啊呜呜啊~~啊嗷啊呜嗷嗷~嗷~嗷~呜呜嗷嗷呜嗷嗷嗷呜呜呜呜嗷啊嗷嗷~啊嗷呜嗷~啊~啊嗷啊嗷啊啊呜呜呜嗷呜~嗷~呜~~嗷啊嗷~啊"},
    };

    @Test
    public void encodeMatchesMainstreamReference() {
        for (String[] g : GOLDEN) {
            assertEquals("encode(" + g[0] + ")", g[1], BeastCodec.encode(g[0]));
        }
    }

    @Test
    public void decodeMatchesMainstreamReference() {
        for (String[] g : GOLDEN) {
            assertEquals("decode of golden for " + g[0], g[0], BeastCodec.decode(g[1]));
        }
    }

    @Test
    public void roundTripIsLossless() {
        String[] samples = {
            "", "hello world", "悬浮助手", "混合English和中文",
            "emoji 😀🎉🚀", "符号 !@#$%^&*()_+-=", "换行\n制表\t回退\r",
            "全角：，。！？", "数字 0123456789",
        };
        for (String s : samples) {
            assertEquals("round-trip " + s, s, BeastCodec.decode(BeastCodec.encode(s)));
        }
    }

    @Test
    public void longTextRoundTrip() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("第").append(i).append("行 mixed 文本 😀\n");
        }
        String text = sb.toString();
        assertEquals(text, BeastCodec.decode(BeastCodec.encode(text)));
    }

    @Test
    public void crossDictRecoversOwnDictionary() {
        // 自定义字典：配方复活（README 记录的真人实测字典）
        char[] dict = {'配', '方', '复', '活'};
        String raw = "跨字典测试 cross";
        String encoded = BeastCodec.encodeMainstream(raw, dict);
        // 解码时字典从串本身头尾提取，无需外部传入
        assertEquals(raw, BeastCodec.decode(encoded));
        // 头缀 = dict[3]dict[1]dict[0] = 活方配，尾缀 = dict[2] = 复
        assertTrue(encoded.startsWith("活方配"));
        assertTrue(encoded.endsWith("复"));
    }

    @Test
    public void decodeRejectsTooShort() {
        assertThrows(IllegalArgumentException.class, () -> BeastCodec.decode("呜嗷啊"));
    }

    @Test
    public void decodeRejectsNonDictChar() {
        // 头缀 ~呜嗷 + 正文(呜X，X 不在字典内) + 尾缀 啊
        assertThrows(IllegalArgumentException.class, () -> BeastCodec.decode("~呜嗷呜X啊"));
    }

    @Test
    public void nullInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> BeastCodec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> BeastCodec.decode(null));
    }
}
