package com.flexiatom.floatingassistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 记事本索引的**纯编解码**（不碰 Android，可在 JVM 里直接跑用例——{@code NotesCodecTest} 编译的就是这份文件本体）。
 *
 * <p>索引一条一行，字段以 {@code |} 分隔：{@code id|pin|updatedMs|title}。正文不进索引，
 * 每篇是一个独立文件（见 {@link NotesStore}），所以这里只处理"看得见的那一列名字"。
 *
 * <p>读侧一律**逐行兜底**：坏行跳过、缺列按默认补齐，绝不因为一行脏了就丢掉整份索引
 * （同 {@link SettingsStore} 黑名单"无合法前缀按字面兜底"的取向——用户数据不可静默丢）。
 */
public final class NotesCodec {

    /** 分隔符；标题与合集名里的它会被 {@link #sanitizeTitle(String)} 换成空格。 */
    public static final String SEP = "|";
    /** 标题显示上限（码点）：抽屉只有 100dp 宽，长正文首行必须截。 */
    public static final int TITLE_MAX_CODE_POINTS = 40;

    private NotesCodec() {
    }

    /** 索引里的一条文档元数据（正文不在这里，按 id 去文件取）。 */
    public static final class Record {
        public final String id;
        public final boolean pinned;
        public final long updatedMs;
        public final String title;

        public Record(String id, boolean pinned, long updatedMs, String title) {
            this.id = id;
            this.pinned = pinned;
            this.updatedMs = updatedMs;
            this.title = title;
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
                    .append(sanitizeTitle(r.title));
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
            out.add(new Record(parts[0], "1".equals(parts[1]), updated, title));
        }
        return out;
    }

    /**
     * 标题清洗：换掉分隔符与一切空白串（{@code |} 会破坏行格式，{@code \n} 会破坏记录，
     * 连续空白在 100dp 的栏里只是噪音），压首尾空白；空标题回空串，
     * 由调用方决定显示什么——存储层不替用户编一个名字。
     */
    public static String sanitizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = title.replace('|', ' ').replaceAll("\\s+", " ").trim();
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
