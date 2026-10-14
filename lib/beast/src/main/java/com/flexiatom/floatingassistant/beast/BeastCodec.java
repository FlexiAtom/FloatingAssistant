package com.flexiatom.floatingassistant.beast;

/**
 * 兽音译者编解码（净室 Java 实现，见提案 §3.1d / R29）。
 *
 * <p>按公开的兽音译者「主流」格式规范独立编写：不复制任何外部 SDK 源码，
 * 与主流工具逐字符兼容。默认字典 {@code 嗷呜啊~}。主流串结构为
 * 头部缀(3) + 正文 + 尾巴缀(1)；头尾是字典的 1 基序号 {@code 4,2,1 / 3}，
 * 四个位置恰好覆盖整套字典一次，故解码时可从串本身提取字典。
 */
public final class BeastCodec {

    /** 主流默认字典：1 基序号 嗷=1 呜=2 啊=3 ~=4。 */
    private static final char[] DEFAULT_DICT = {'嗷', '呜', '啊', '~'};

    private static final int RADIX = 16;   // 单个 UTF-16 码元用 4 位十六进制表示
    private static final int DICT_SIZE = 4;

    private BeastCodec() {
    }

    /** 明文 → 主流兽文（默认字典，含头尾缀）。 */
    public static String encode(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw must not be null");
        }
        return encodeMainstream(raw, DEFAULT_DICT);
    }

    /**
     * 主流兽文 → 明文。字典从串自身的头尾缀提取，因此无需外部提供字典。
     *
     * @throws IllegalArgumentException 串长度不足、含字典外字符，或无法从头尾还原字典
     */
    public static String decode(String beast) {
        if (beast == null) {
            throw new IllegalArgumentException("beast must not be null");
        }
        if (beast.length() < 4) {
            throw new IllegalArgumentException("beast string too short for mainstream form");
        }
        char[] dict = extractDict(beast);
        String body = beast.substring(3, beast.length() - 1);
        return decodeBody(body, dict);
    }

    // ---- 主流封装（头缀 = 序号 4,2,1；尾缀 = 序号 3）----

    static String encodeMainstream(String raw, char[] dict) {
        requireDict(dict);
        StringBuilder out = new StringBuilder();
        out.append(dict[3]).append(dict[1]).append(dict[0]); // 头缀：1 基序号 4,2,1
        out.append(encodeBody(raw, dict));
        out.append(dict[2]);                                  // 尾缀：1 基序号 3
        return out.toString();
    }

    static char[] extractDict(String beast) {
        // 头缀三位与尾缀一位分别对应字典槽位 3,1,0,2
        char[] dict = new char[DICT_SIZE];
        dict[3] = beast.charAt(0);
        dict[1] = beast.charAt(1);
        dict[0] = beast.charAt(2);
        dict[2] = beast.charAt(beast.length() - 1);
        requireDict(dict);
        return dict;
    }

    // ---- 正文（每个 UTF-16 码元 → 4 位十六进制 → 每个 nibble 展开成 2 个字典字符）----

    static String encodeBody(String raw, char[] dict) {
        StringBuilder out = new StringBuilder();
        for (int c = 0; c < raw.length(); c++) {
            char codeUnit = raw.charAt(c);
            for (int shift = 12; shift >= 0; shift -= 4) {
                int nibble = (codeUnit >> shift) & 0xF;
                int k = nibble + (out.length() / 2 % RADIX);
                if (k >= RADIX) {
                    k -= RADIX;
                }
                out.append(dict[k / DICT_SIZE]).append(dict[k % DICT_SIZE]);
            }
        }
        return out.toString();
    }

    static String decodeBody(String beast, char[] dict) {
        StringBuilder hex = new StringBuilder();
        int nibbleIndex = 0;
        for (int i = 0; i + 1 < beast.length(); i += 2) {
            int pos1 = indexOf(beast.charAt(i), dict);
            int pos2 = indexOf(beast.charAt(i + 1), dict);
            int k = pos1 * DICT_SIZE + pos2;
            int nibble = k - (nibbleIndex % RADIX);
            if (nibble < 0) {
                nibble += RADIX;
            }
            hex.append(Integer.toHexString(nibble));
            nibbleIndex++;
        }
        StringBuilder raw = new StringBuilder();
        for (int j = 0; j + RADIX / DICT_SIZE <= hex.length(); j += DICT_SIZE) {
            int value = Integer.parseInt(hex.substring(j, j + DICT_SIZE), RADIX);
            raw.append((char) value);
        }
        return raw.toString();
    }

    private static int indexOf(char ch, char[] dict) {
        for (int i = 0; i < dict.length; i++) {
            if (dict[i] == ch) {
                return i;
            }
        }
        throw new IllegalArgumentException("character not in dictionary: " + ch);
    }

    private static void requireDict(char[] dict) {
        if (dict == null || dict.length != DICT_SIZE) {
            throw new IllegalArgumentException("dictionary must contain exactly 4 characters");
        }
        for (int i = 0; i < DICT_SIZE; i++) {
            for (int j = i + 1; j < DICT_SIZE; j++) {
                if (dict[i] == dict[j]) {
                    throw new IllegalArgumentException("dictionary characters must be distinct");
                }
            }
        }
    }
}
