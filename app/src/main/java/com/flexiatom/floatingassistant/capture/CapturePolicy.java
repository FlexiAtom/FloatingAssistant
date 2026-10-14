package com.flexiatom.floatingassistant.capture;

/** 无障碍截图的时序与限流参数（提案 §4.3）。 */
public final class CapturePolicy {

    /** takeScreenshot 回调超时：个别 ROM 不回调时避免永久挂起（区别于 TouchTool 的裸 join）。 */
    public static final long TIMEOUT_MS = 800L;

    /** 超时/失败后的重试次数。 */
    public static final int RETRY = 1;

    /** 连续截图的最小间隔节流：Android 12+ 约 3 次/秒，取 ~350ms 留余量。 */
    public static final long MIN_INTERVAL_MS = 350L;

    /** 判定"纯黑/空图"时的采样网格边长（每边 N 个点）。 */
    public static final int BLACK_SAMPLE_GRID = 8;

    /** 单通道视为"亮"的最小阈值；全部采样点低于此值判为黑屏。 */
    public static final int BLACK_LUMA_THRESHOLD = 8;

    private CapturePolicy() {
    }
}
