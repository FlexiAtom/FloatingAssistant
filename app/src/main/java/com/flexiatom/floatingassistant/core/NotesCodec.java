package com.flexiatom.floatingassistant.core;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 记事本索引的**纯编解码**（不碰 Android，所以用例可以直接用 {@code javac}＋{@code java} 单文件跑这份本体，
 * 仓里目前没有它的测试源集——E6b 起这条口径改成了「临时跑一遍并把输出贴进反证表」，别当成已有用例）。
 *
 * <p>索引一条一行，字段以 {@code |} 分隔：{@code id|pin|updatedMs|title|charset|newline}。正文不进索引，
 * 每篇是一个独立文件（见 {@link NotesStore}），所以这里只处理"看得见的那一列名字"。
 *
 * <p>后两列（E6b）＝**保存编码**与**换行符档位**，都是字符串标签（{@code UTF-8}／{@code GBK}；
 * {@code LF}／{@code CRLF}／{@code CR}），不是枚举序号——索引是人类可用文件管理器直读的，
 * 序号会把它变成只有本 app 认得的暗码。读侧缺列按默认补（{@link #DEFAULT_CHARSET}／
 * {@link #DEFAULT_NEWLINE}），所以旧的 4 列索引原样能用，不必迁移。
 *
 * <p>读侧一律**逐行兜底**：坏行跳过、缺列按默认补齐，绝不因为一行脏了就丢掉整份索引
 * （同 {@link SettingsStore} 黑名单"无合法前缀按字面兜底"的取向——用户数据不可静默丢）。
 */
public final class NotesCodec {

    /** 分隔符；标题与合集名里的它会被 {@link #sanitizeTitle(String)} 换成空格。 */
    public static final String SEP = "|";
    /** 标题显示上限（码点）：抽屉只有 100dp 宽，长正文首行必须截。 */
    public static final int TITLE_MAX_CODE_POINTS = 40;
    public static final String DEFAULT_CHARSET = "UTF-8";
    public static final String DEFAULT_NEWLINE = "LF";

    /**
     * {@code UTF-8 BOM} 不是 JDK 的 charset 名，但它是**Windows 记事本那一路的兼容档**——没有它，
     * CRLF＋中文存出去在记事本里仍是乱码。所以标签自己留着，真正解码时经
     * {@link #jdkCharset(String)} 换回 {@code UTF-8}，区别只在盘上那三个字节（{@link #UTF8_BOM}）。
     */
    public static final String BOM_LABEL = "UTF-8 BOM";
    static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private NotesCodec() {
    }

    /** 索引里的一条文档元数据（正文不在这里，按 id 去文件取）。 */
    public static final class Record {
        public final String id;
        public final boolean pinned;
        public final long updatedMs;
        public final String title;
        public final String charset;
        public final String newline;

        public Record(String id, boolean pinned, long updatedMs, String title,
                      String charset, String newline) {
            this.id = id;
            this.pinned = pinned;
            this.updatedMs = updatedMs;
            this.title = title;
            this.charset = sanitizeLabel(charset, DEFAULT_CHARSET);
            this.newline = sanitizeLabel(newline, DEFAULT_NEWLINE);
        }
    }

    public static String encode(List<Record> records) {
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r.id).append(SEP)
                    .append(r.pinned ? '1' : '0').append(SEP)
                    .append(r.updatedMs).append(SEP)
                    .append(sanitizeTitle(r.title)).append(SEP)
                    .append(r.charset).append(SEP)
                    .append(r.newline);
        }
        return sb.toString();
    }

    /** 解析整份索引：空行与列数不足的行跳过，{@code updatedMs} 脏值按 0 处理。 */
    public static List<Record> decode(String raw) {
        List<Record> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\" + SEP, -1);
            if (parts.length < 3 || parts[0].isEmpty()) {
                continue;
            }
            long updated;
            try {
                updated = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                updated = 0L;
            }
            String title = parts.length >= 4 ? parts[3] : "";
            String charset = parts.length >= 6 ? parts[4] : DEFAULT_CHARSET;
            String newline = parts.length >= 6 ? parts[5] : DEFAULT_NEWLINE;
            out.add(new Record(parts[0], "1".equals(parts[1]), updated, title, charset, newline));
        }
        return out;
    }

    /** 档位 → 盘上那个换行序列；认不得的标签回 LF，别让一个脏字段把整篇变成单行。 */
    public static String newlineSequence(String tag) {
        if ("CRLF".equals(tag)) {
            return "\r\n";
        }
        if ("CR".equals(tag)) {
            return "\r";
        }
        return "\n";
    }

    /** 标签 → JDK charset 名；存储层拿它去 {@code Charset.forName}，其余标签原样透传。 */
    public static String jdkCharset(String label) {
        return BOM_LABEL.equals(label) ? DEFAULT_CHARSET : label;
    }

    /** 这两列与标题都直接进索引行，所以 {@code |} 和换行必须洗掉；洗空了回默认档，不留一个能打断格式的标签。 */
    private static String sanitizeLabel(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        String cleaned = value.replace('|', ' ').replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /**
     * 盘上文本 → 编辑缓冲：一律归一成 {@code \n}。顺序不能反——先 {@code \r}→{@code \n} 会把
     * {@code \r\n} 拆成两个换行。缓冲里恒为 {@code \n} 是硬约定：{@code EditText} 认不了 CRLF，
     * 而行号/光标行列/撤销快照那一整套也都按 {@code \n} 算。
     */
    public static String toBuffer(String fromFile) {
        if (fromFile == null || fromFile.isEmpty()) {
            return "";
        }
        return fromFile.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 编辑缓冲 → 盘上文本：把 {@code \n} 换成该篇的档位（无损往返全靠这两条对称）。 */
    public static String toFile(String buffer, String newlineTag) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        return buffer.replace("\n", newlineSequence(newlineTag));
    }

    /** 标签 → JDK {@code Charset}；认不得回 {@code null}，由调用方决定怎么兜底。 */
    public static Charset charsetFor(String label) {
        try {
            return Charset.forName(jdkCharset(label));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 缓冲 → 盘上字节：先按档位展开换行，再按档位编码，{@link #BOM_LABEL} 最后前置三个字节。
     * 标签认不得（或该档装不下这些字）时**按 UTF-8 出字节**——纯编解码层不写日志，才能在 JVM 里
     * 单文件跑；调用方要提醒人就自己看 {@link #charsetFor(String)} 再调这里。
     */
    public static byte[] encodeBytes(String buffer, String charset, String newline) {
        Charset cs = charsetFor(charset);
        ByteBuffer encoded = (cs == null ? StandardCharsets.UTF_8 : cs).encode(toFile(buffer, newline));
        byte[] text = new byte[encoded.remaining()]; // 不能直接 .array()：那是底层大数组，尾部多出来的字节不是内容
        encoded.get(text);
        if (!BOM_LABEL.equals(charset) || text.length == 0) {
            return text;
        }
        byte[] out = new byte[text.length + UTF8_BOM.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(text, 0, out, UTF8_BOM.length, text.length);
        return out;
    }

    /**
     * 盘上字节 → 缓冲。BOM **无论档位都剥**：带着它解码只会让首行开头多一个不可见字符，
     * 而标题＝首行，那一个字符会一路污染索引里的标题。
     */
    public static String decodeBytes(byte[] raw, String charset) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int off = startsWithBom(raw) ? UTF8_BOM.length : 0;
        Charset cs = charsetFor(charset);
        if (cs == null) {
            cs = StandardCharsets.UTF_8;
        }
        return toBuffer(new String(raw, off, raw.length - off, cs));
    }

    /** 盘上字节开头是不是 UTF-8 BOM（{@code 字节数}那一格也拿它判，免得多出三格）。 */
    public static boolean startsWithBom(byte[] raw) {
        if (raw == null || raw.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (raw[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 标题清洗：换掉分隔符与一切空白串（{@code |} 会破坏行格式，{@code \n} 会破坏记录，
     * 连续空白在 100dp 的栏里只是噪音），压首尾空白；空标题回空串，
     * 由调用方决定显示什么——存储层不替用户编一个名字。
     */
    public static String sanitizeTitle(String title) {
        String cleaned = sanitizeLabel(title, "");
        return cleaned.isEmpty() ? "" : truncate(cleaned, TITLE_MAX_CODE_POINTS);
    }

    /** 由正文生成标题＝第一条非空行；整篇空白则回空串（新建时不显示成一行空白）。 */
    public static String deriveTitle(String body) {
        if (body == null) {
            return "";
        }
        int start = 0;
        while (start < body.length()) {
            int nl = body.indexOf('\n', start);
            String line = nl < 0 ? body.substring(start) : body.substring(start, nl);
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return sanitizeTitle(trimmed);
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return "";
    }

    /**
     * 正则优先（人裁：搜索框默认正则）；语法不成立时**退回字面包含**而不是返回空结果
     * ——半截正则正在输入是常态，那格应当显示它匹配得到的东西。
     */
    public static Pattern tryCompile(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(query);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** 命中判据＝标题或正文；{@code pattern} 为 null（非法正则）时按字面 {@code literal} 包含。 */
    public static boolean matches(Pattern pattern, String literal, String title, String body) {
        if (pattern != null) {
            return (title != null && pattern.matcher(title).find())
                    || (body != null && pattern.matcher(body).find());
        }
        if (literal == null || literal.isEmpty()) {
            return true;
        }
        return (title != null && title.contains(literal)) || (body != null && body.contains(literal));
    }

    /** 置顶组在前、组内按更新时间倒序（列表与搜索结果同一口径）。 */
    public static List<Record> sorted(List<Record> records) {
        List<Record> out = new ArrayList<>(records);
        out.sort((a, b) -> {
            if (a.pinned != b.pinned) {
                return a.pinned ? -1 : 1;
            }
            return Long.compare(b.updatedMs, a.updatedMs);
        });
        return out;
    }

    /** 按码点截断，避免在代理对中间切出乱字符。 */
    private static String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }
}
