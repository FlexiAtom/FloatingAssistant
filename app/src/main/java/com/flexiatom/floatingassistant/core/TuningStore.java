package com.flexiatom.floatingassistant.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.flexiatom.floatingassistant.capture.CapturePolicy;

import java.util.Locale;

/**
 * 设备自调参数（提案 §7.2 L2）——当前只放截图组，其余组按 {@code device-tuning-page} 的切片台账后补。
 *
 * <p><b>为什么另开 prefs 而不并进 {@link SettingsStore}</b>：后者的 {@code version()} 是
 * {@code BlacklistMatcher} 判 Pattern 缓存失效的信号，每写一次参数就自增会让黑名单每轮重编译。
 *
 * <p><b>为什么没有版本号/监听器</b>：{@link com.flexiatom.floatingassistant.capture.ScreenCapturer}
 * 每次截图都现读本类，SharedPreferences 的进程内缓存让这次读取只是一次 map 查找——改完下一次截图即生效，
 * 不需要重启服务，也不需要额外线程或 DataStore。
 *
 * <p>默认值**就是当前生产常量**。提案 §7.3 的默认值列要等 P0″ 实测回填；本轮不借「调参页」之名
 * 顺手改行为。{@code warmup} 是新增能力，默认关（此前无预热），由自检与用户决定是否开。
 */
public final class TuningStore {

    private static final String PREFS = "tuning";

    private static final String K_TIMEOUT_MS = "capture_timeout_ms";
    private static final String K_RETRY = "capture_retry";
    private static final String K_MIN_INTERVAL_MS = "capture_min_interval_ms";
    private static final String K_DETECT_BLANK = "capture_detect_blank";
    private static final String K_WARMUP = "capture_warmup";
    private static final String K_BLACK_THRESHOLD = "capture_black_threshold";
    private static final String K_FALLBACK_STRICT = "capture_fallback_strict";

    public static final long TIMEOUT_MIN_MS = 300L;
    public static final long TIMEOUT_MAX_MS = 5000L;
    public static final long TIMEOUT_DEFAULT_MS = CapturePolicy.TIMEOUT_MS;

    public static final int RETRY_MIN = 0;
    public static final int RETRY_MAX = 3;
    public static final int RETRY_DEFAULT = CapturePolicy.RETRY;

    public static final long INTERVAL_MIN_MS = 0L;
    public static final long INTERVAL_MAX_MS = 3000L;
    public static final long INTERVAL_DEFAULT_MS = CapturePolicy.MIN_INTERVAL_MS;

    public static final int BLACK_MIN = 1;
    public static final int BLACK_MAX = 64;
    public static final int BLACK_DEFAULT = CapturePolicy.BLACK_LUMA_THRESHOLD;

    public static final boolean DETECT_BLANK_DEFAULT = true;
    public static final boolean WARMUP_DEFAULT = false;
    /** 默认宽松：截图失败仍读无障碍树兜底，与调参页出现前的生产行为一致。 */
    public static final boolean FALLBACK_STRICT_DEFAULT = false;

    private final SharedPreferences prefs;

    public TuningStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 截图组

    public long timeoutMs() {
        return snap(clamp(safeLong(K_TIMEOUT_MS, TIMEOUT_DEFAULT_MS),
                TIMEOUT_MIN_MS, TIMEOUT_MAX_MS), 100L);
    }

    public void setTimeoutMs(long ms) {
        prefs.edit().putLong(K_TIMEOUT_MS,
                snap(clamp(ms, TIMEOUT_MIN_MS, TIMEOUT_MAX_MS), 100L)).apply();
    }

    public int retryCount() {
        return (int) clamp(safeLong(K_RETRY, RETRY_DEFAULT), RETRY_MIN, RETRY_MAX);
    }

    public void setRetryCount(int count) {
        prefs.edit().putLong(K_RETRY, clamp(count, RETRY_MIN, RETRY_MAX)).apply();
    }

    public long minIntervalMs() {
        return snap(clamp(safeLong(K_MIN_INTERVAL_MS, INTERVAL_DEFAULT_MS),
                INTERVAL_MIN_MS, INTERVAL_MAX_MS), 50L);
    }

    public void setMinIntervalMs(long ms) {
        prefs.edit().putLong(K_MIN_INTERVAL_MS,
                snap(clamp(ms, INTERVAL_MIN_MS, INTERVAL_MAX_MS), 50L)).apply();
    }

    public boolean detectBlank() {
        return safeBoolean(K_DETECT_BLANK, DETECT_BLANK_DEFAULT);
    }

    public void setDetectBlank(boolean enabled) {
        prefs.edit().putBoolean(K_DETECT_BLANK, enabled).apply();
    }

    public boolean warmup() {
        return safeBoolean(K_WARMUP, WARMUP_DEFAULT);
    }

    public void setWarmup(boolean enabled) {
        prefs.edit().putBoolean(K_WARMUP, enabled).apply();
    }

    public int blackThreshold() {
        return (int) clamp(safeLong(K_BLACK_THRESHOLD, BLACK_DEFAULT), BLACK_MIN, BLACK_MAX);
    }

    public void setBlackThreshold(int threshold) {
        prefs.edit().putLong(K_BLACK_THRESHOLD, clamp(threshold, BLACK_MIN, BLACK_MAX)).apply();
    }

    /** 严格模式：截图失败直接给「不支持」，不读无障碍树兜底。默认 false＝保持现状。 */
    public boolean fallbackStrict() {
        return safeBoolean(K_FALLBACK_STRICT, FALLBACK_STRICT_DEFAULT);
    }

    public void setFallbackStrict(boolean strict) {
        prefs.edit().putBoolean(K_FALLBACK_STRICT, strict).apply();
    }

    /** 恢复本切片全部默认值；其余组的键若将来加入，也应在这里一并清掉。 */
    public void resetDefaults() {
        prefs.edit().clear().apply();
    }

    /** 自检/日志用的一行参数快照；字段名与 {@code TuningSelfTest} 摘要一致，便于逐项对照。 */
    public String summary() {
        return String.format(Locale.US,
                "timeout=%d retry=%d interval=%d blank=%s warmup=%s black=%d strict=%s",
                timeoutMs(), retryCount(), minIntervalMs(), detectBlank(), warmup(),
                blackThreshold(), fallbackStrict());
    }

    // ---------------------------------------------------------------- 内部

    /** prefs 被外部改坏类型时按默认值读，不让一次坏写入把截图链路打崩。 */
    private long safeLong(String key, long def) {
        try {
            return prefs.getLong(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    private boolean safeBoolean(String key, boolean def) {
        try {
            return prefs.getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 对齐到滑动条步长：Slider.setValue 只接受 valueFrom + k·step 的点。 */
    private static long snap(long v, long step) {
        return Math.round((double) v / step) * step;
    }
}
