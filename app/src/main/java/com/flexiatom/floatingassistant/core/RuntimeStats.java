package com.flexiatom.floatingassistant.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.flexiatom.floatingassistant.capture.ScreenCapturer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 应用自回报（runtime-self-report）：把「服务活了多久、被重连过几次、截图/识别的成功率与延迟」记进
 * 独立 prefs {@code runtime_stats}，供 M0/M1 收口举证。
 *
 * <p><b>为什么单开一份 prefs、不并进 {@link SettingsStore}</b>：后者的 {@code version()} 是给
 * {@code BlacklistMatcher} 判 Pattern 缓存失效用的——每写一次就自增。若遥测共用它，一次心跳就把黑名单
 * 缓存判过期，白重编译正则。两份文件的写入语义不同，必须分开。
 *
 * <p><b>为什么要心跳</b>：系统杀掉无障碍服务进程时<b>不保证回调</b> {@code onDestroy}。只靠 onDestroy
 * 结算，「跑了 20 h 被回收」与「跑了 20 h 还在跑」在记录上完全同形（都没有结束记录）。心跳把
 * {@code last_beat_wall} 往前推，下次连接即可区分「干净退出」与「被回收」，并算出失联时长。
 *
 * <p><b>时长口径</b>：用 {@link SystemClock#elapsedRealtime()} 差值——它含深睡，正是「服务存活多久」
 * 要的口径（{@code uptimeMillis} 会把待机扣掉，读数偏小）。跨重启后该钟归零，故累计值取持久化的
 * {@code total_ms}，会话内只用差值。
 *
 * <p>进程内单例（{@link #of}）：遥测是横切关注点，不该沿构造器到处传参；底层存储是 prefs，本就跨进程调用
 * 单调，单例不引入额外状态。
 */
public final class RuntimeStats {

    private static final String TAG = "RuntimeReport";

    /** 心跳周期：60 s 足够把「被回收」的失联误差压到 1 分钟内，且每分钟一次 apply() 的写盘量可忽略。 */
    public static final long HEARTBEAT_MS = 60_000L;

    private static final String PREFS = "runtime_stats";

    private static final String K_CONNECTS = "connect_count";
    private static final String K_CLEAN_ENDS = "clean_end_count";
    private static final String K_TOTAL_MS = "total_ms";
    private static final String K_SESS_START_WALL = "sess_start_wall";
    private static final String K_LAST_BEAT_WALL = "last_beat_wall";
    private static final String K_LAST_END_WALL = "last_end_wall";
    private static final String K_LAST_SESSION_MS = "last_session_ms";
    private static final String K_GAP_MS = "last_gap_ms";
    private static final String K_CAPS = "caps";
    private static final String K_CAP_TOTAL = "cap_total";
    private static final String K_RUNS = "runs";

    /** 一次「运行」的类别，{@code runs} 表的第一列。新增工具就加一个常量，不必加 prefs 键。 */
    public static final String RUN_PAGE = "page";
    public static final String RUN_INPLACE = "inplace";
    public static final String RUN_REGION = "region";

    private static volatile RuntimeStats instance;

    private final SharedPreferences prefs;

    /** 会话起点（elapsedRealtime）；0=当前无在途会话，{@link #sessionEnded()} 据此幂等。 */
    private long sessionStartElapsed;

    private RuntimeStats(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static RuntimeStats of(Context ctx) {
        RuntimeStats local = instance;
        if (local == null) {
            synchronized (RuntimeStats.class) {
                local = instance;
                if (local == null) {
                    local = new RuntimeStats(ctx);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ---------------------------------------------------------------- 会话生命周期

    /**
     * 服务连上时调用：连接数 +1，记会话起点；若上一次会话没留下干净结束记录 → 记为异常退出，
     * 并把「上次心跳 → 本次连接」的墙钟差写进 {@code last_gap_ms}（＝失联时长，回收证据）。
     */
    public synchronized void sessionStarted() {
        long wall = System.currentTimeMillis();
        long beat = prefs.getLong(K_LAST_BEAT_WALL, 0L);
        long startWall = prefs.getLong(K_SESS_START_WALL, 0L);
        long endWall = prefs.getLong(K_LAST_END_WALL, 0L);
        long gap = 0L;
        boolean abnormal = startWall > 0L && endWall < startWall; // 有起点、无该起点之后的结束记录
        if (abnormal && beat > 0L) {
            gap = Math.max(0L, wall - beat);
        }
        sessionStartElapsed = SystemClock.elapsedRealtime();
        prefs.edit()
                .putInt(K_CONNECTS, prefs.getInt(K_CONNECTS, 0) + 1)
                .putLong(K_SESS_START_WALL, wall)
                .putLong(K_GAP_MS, gap)
                .remove(K_LAST_BEAT_WALL)
                .apply();
        Log.i(TAG, "session#" + prefs.getInt(K_CONNECTS, 1) + " start"
                + (abnormal ? " prevAbnormal=true gapMs=" + gap : ""));
    }

    /** 服务解构/解绑时调用：按 elapsedRealtime 差结算本会话并清零起点。幂等（无在途会话则 no-op）。 */
    public synchronized void sessionEnded() {
        long start = sessionStartElapsed;
        if (start == 0L) {
            return;
        }
        long ms = Math.max(0L, SystemClock.elapsedRealtime() - start);
        sessionStartElapsed = 0L;
        prefs.edit()
                .putLong(K_TOTAL_MS, prefs.getLong(K_TOTAL_MS, 0L) + ms)
                .putLong(K_LAST_SESSION_MS, ms)
                .putLong(K_LAST_END_WALL, System.currentTimeMillis())
                .putInt(K_CLEAN_ENDS, prefs.getInt(K_CLEAN_ENDS, 0) + 1)
                .putLong(K_SESS_START_WALL, 0L)
                .apply();
        Log.i(TAG, "session ended cleanly ms=" + ms + " totalMs=" + prefs.getLong(K_TOTAL_MS, 0L));
    }

    /** 心跳：只推 {@code last_beat_wall} 并打一行日志。由服务侧每 {@link #HEARTBEAT_MS} 触发一次。 */
    public synchronized void beat() {
        long now = System.currentTimeMillis();
        prefs.edit().putLong(K_LAST_BEAT_WALL, now).apply();
        Log.i(TAG, "beat uptime=" + format(uptimeMillis())
                + " connects=" + prefs.getInt(K_CONNECTS, 0)
                + " cleanEnds=" + prefs.getInt(K_CLEAN_ENDS, 0)
                + " caps=" + prefs.getLong(K_CAP_TOTAL, 0L));
    }

    /** 当前会话已存活毫秒（无在途会话返回 0）。 */
    public synchronized long currentSessionMillis() {
        long start = sessionStartElapsed;
        return start == 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - start);
    }

    /** 累计运行毫秒 = 历史干净会话之和 + 当前在途会话。 */
    public synchronized long uptimeMillis() {
        return prefs.getLong(K_TOTAL_MS, 0L) + currentSessionMillis();
    }

    // ---------------------------------------------------------------- 结果计数

    /** 记一次截图归类（含失败）。失败路径也要记——黑屏率/超时率正是设备自调页的输入。 */
    public synchronized void capture(ScreenCapturer.Outcome outcome) {
        prefs.edit()
                .putString(K_CAPS, bumpLabel(readRows(K_CAPS), outcome.name()))
                .putLong(K_CAP_TOTAL, prefs.getLong(K_CAP_TOTAL, 0L) + 1L)
                .apply();
    }

    /**
     * 记一次端到端「运行」（采集→出文本）。{@code ms} 为工作线程内的整段耗时。
     * 聚合按 kind 分行存于单个 {@code runs} 字符串（每 kind 一行 {@code kind|count|fail|sum|max|last}），
     * 与 {@link SettingsStore} 黑名单「一行一条」的序列化取向一致，新增工具不用加键。
     */
    public synchronized void run(String kind, long ms, boolean ok) {
        List<String> rows = readRows(K_RUNS);
        String updated = null;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).startsWith(kind + "|")) {
                updated = mergeRun(rows.get(i), ms, ok);
                rows.set(i, updated);
                break;
            }
        }
        if (updated == null) {
            updated = mergeRun(kind + "|0|0|0|0|0", ms, ok);
            rows.add(updated);
        }
        prefs.edit().putString(K_RUNS, joinRows(rows)).apply();
        Log.i(TAG, "run " + kind + " ms=" + ms + " ok=" + ok);
    }

    // ---------------------------------------------------------------- 人读报告

    /** 面板与 logcat 共用的唯一出口：两处真值必须同源，否则 UI 与日志分叉就无从判信。 */
    public synchronized String report() {
        long uptime = uptimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("连续运行 ").append(format(uptime)).append('\n');
        sb.append("  当前会话 ").append(format(currentSessionMillis()))
                .append(" · 累计(已结束) ").append(format(prefs.getLong(K_TOTAL_MS, 0L))).append('\n');
        int connects = prefs.getInt(K_CONNECTS, 0);
        int clean = prefs.getInt(K_CLEAN_ENDS, 0);
        sb.append("服务连接 ").append(connects).append(" 次")
                .append(" · 干净退出 ").append(clean).append(" 次")
                .append(" · 异常退出 ").append(Math.max(0, connects - clean)).append(" 次").append('\n');
        long gap = prefs.getLong(K_GAP_MS, 0L);
        if (gap > 0L) {
            sb.append("上次失联 ").append(format(gap)).append("（末次心跳→本次连接）").append('\n');
        }
        long capTotal = prefs.getLong(K_CAP_TOTAL, 0L);
        sb.append("截图 ").append(capTotal).append(" 次");
        if (capTotal > 0) {
            sb.append(" → 成功率 ").append(percent(count(K_CAPS, "OK"), capTotal))
                    .append(" 黑屏率 ").append(percent(count(K_CAPS, "BLACK"), capTotal));
            for (String row : readRows(K_CAPS)) {
                String[] f = row.split("\\|");
                if (f.length >= 2) {
                    sb.append(" · ").append(f[0]).append(' ').append(parseLong(f[1]));
                }
            }
        }
        sb.append('\n');
        List<String> runs = readRows(K_RUNS);
        if (runs.isEmpty()) {
            sb.append("识别运行 0 次");
        }
        for (String row : runs) {
            String[] f = row.split("\\|");
            if (f.length < 6) {
                continue;
            }
            long count = parseLong(f[1]);
            long fail = parseLong(f[2]);
            long sum = parseLong(f[3]);
            sb.append("  ").append(f[0]).append(' ').append(count).append(" 次")
                    .append(" 失败 ").append(fail)
                    .append(" 均 ").append(count > 0 ? sum / count : 0).append("ms")
                    .append(" 最大 ").append(parseLong(f[4])).append("ms")
                    .append(" 最近 ").append(parseLong(f[5])).append("ms")
                    .append('\n');
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 内部

    /** 已有「标签|计数」表上把 {@code label} 计数加一，返回新表文本。调用方须持本类锁。 */
    private static String bumpLabel(List<String> rows, String label) {
        for (int i = 0; i < rows.size(); i++) {
            String[] f = rows.get(i).split("\\|");
            if (f.length >= 2 && f[0].equals(label)) {
                rows.set(i, label + "|" + (parseLong(f[1]) + 1));
                return joinRows(rows);
            }
        }
        rows.add(label + "|1");
        return joinRows(rows);
    }

    private long count(String key, String label) {
        for (String row : readRows(key)) {
            String[] f = row.split("\\|");
            if (f.length >= 2 && f[0].equals(label)) {
                return parseLong(f[1]);
            }
        }
        return 0L;
    }

    /** 已有行 {@code kind|count|fail|sum|max|last} 上并入一次结果。 */
    private static String mergeRun(String row, long ms, boolean ok) {
        String[] f = row.split("\\|");
        long count = 0;
        long fail = 0;
        long sum = 0;
        long max = 0;
        if (f.length >= 5) {
            count = parseLong(f[1]);
            fail = parseLong(f[2]);
            sum = parseLong(f[3]);
            max = parseLong(f[4]);
        }
        count++;
        if (!ok) {
            fail++;
        }
        sum += Math.max(0L, ms);
        max = Math.max(max, ms);
        return f[0] + "|" + count + "|" + fail + "|" + sum + "|" + max + "|" + ms;
    }

    private List<String> readRows(String key) {
        String raw = prefs.getString(key, "");
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static String joinRows(List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (String r : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r);
        }
        return sb.toString();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String percent(long part, long total) {
        if (total <= 0) {
            return "—";
        }
        return String.format(Locale.US, "%.1f%%", 100.0 * part / total);
    }

    /** 毫秒转「1天2小时3分4秒」，不足一天只显示到秒，读报告的人不必自己换算。 */
    private static String format(long ms) {
        long s = ms / 1000L;
        long days = s / 86400L;
        long hours = (s % 86400L) / 3600L;
        long minutes = (s % 3600L) / 60L;
        long seconds = s % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("小时");
        }
        if (days > 0 || hours > 0 || minutes > 0) {
            sb.append(minutes).append("分");
        }
        sb.append(seconds).append("秒");
        return sb.toString();
    }
}
