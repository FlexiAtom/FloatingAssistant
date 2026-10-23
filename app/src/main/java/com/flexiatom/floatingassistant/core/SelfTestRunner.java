/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of FloatingAssistant.
 *
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.flexiatom.floatingassistant.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.flexiatom.floatingassistant.capture.ScreenCapturer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * 截图组一键自检（提案 §7.5 的最小片）：连续调用**生产** {@link ScreenCapturer} 采样，
 * 汇总成功率/延迟/重试/失败类别，再按规则给出参数建议。
 *
 * <p><b>为什么复用生产截图器</b>：另写一条「测试专用截图」会把参数、节流、黑屏判定都复制一遍，
 * 自检通过而生产失败时无从归因。这里只多一个 {@link ScreenCapturer.AttemptListener}，不改截图语义。
 *
 * <p><b>为什么不写 {@link RuntimeStats}</b>：那是生产成功率/黑屏率的口径，自检的连续采样混进去会冲淡真值。
 * 自检只出报告与 {@code TuningSelfTest} 日志，供人对照调参。
 *
 * <p>线程：{@link #run} 是同步阻塞的，调用方负责放到后台线程；本类不新建线程池。
 */
public final class SelfTestRunner {

    private static final String TAG = "TuningSelfTest";
    public static final int DEFAULT_ITERATIONS = 8;

    private final ScreenCapturer capturer;
    private final TuningStore tuning;

    public SelfTestRunner(ScreenCapturer capturer, TuningStore tuning) {
        this.capturer = capturer;
        this.tuning = tuning;
    }

    /** 自检结果：{@code text} 进 UI，{@code summary} 进 logcat（一行，字段名稳定）。 */
    public static final class Result {
        public final String text;
        public final String summary;

        public Result(String text, String summary) {
            this.text = text;
            this.summary = summary;
        }
    }

    public Result run(int requestedIterations) {
        final int n = Math.max(1, Math.min(20, requestedIterations));
        final String params = tuning.summary();
        final long timeoutMs = tuning.timeoutMs();
        final boolean detectBlank = tuning.detectBlank();
        final boolean warmup = tuning.warmup();

        int ok = 0;
        int attempts = 0;
        int retries = 0;
        long sumMs = 0L;
        long maxMs = 0L;
        long firstMs = -1L;
        final EnumMap<ScreenCapturer.Outcome, Integer> attemptOutcomes =
                new EnumMap<>(ScreenCapturer.Outcome.class);
        final EnumMap<ScreenCapturer.Outcome, Integer> finalOutcomes =
                new EnumMap<>(ScreenCapturer.Outcome.class);
        int executed = 0;

        for (int i = 0; i < n; i++) {
            final EnumMap<ScreenCapturer.Outcome, Integer> runOutcomes =
                    new EnumMap<>(ScreenCapturer.Outcome.class);
            final int[] runAttempts = {0};
            final long[] runOkMs = {-1L};
            ScreenCapturer.AttemptListener listener = (attempt, outcome, ms) -> {
                runAttempts[0]++;
                runOutcomes.merge(outcome, 1, Integer::sum);
                if (outcome == ScreenCapturer.Outcome.OK) {
                    runOkMs[0] = ms;
                }
            };

            long t0 = SystemClock.elapsedRealtime();
            long wallMs;
            long ms;
            try {
                Bitmap bmp = capturer.capture(listener);
                wallMs = SystemClock.elapsedRealtime() - t0;
                // 只计**成功那一趟截图**的耗时：capture() 里的节流等待不是截图成本，
                // 把它算进均值会让 350ms 间隔把报告灌成「截图 350ms」，调参就失去依据。
                ms = runOkMs[0] >= 0L ? runOkMs[0] : wallMs;
                bmp.recycle();
                ok++;
                sumMs += ms;
                maxMs = Math.max(maxMs, ms);
                if (firstMs < 0L) {
                    firstMs = ms;
                }
            } catch (ScreenCapturer.CaptureException e) {
                wallMs = SystemClock.elapsedRealtime() - t0;
                ms = wallMs;
                finalOutcomes.merge(e.outcome, 1, Integer::sum);
                if (e.outcome == ScreenCapturer.Outcome.SECURED) {
                    // 受保护页面重试无意义；保留已采到的样本并提前收口，建议里会指路。
                    executed = i + 1;
                    attempts += runAttempts[0];
                    retries += Math.max(0, runAttempts[0] - 1);
                    merge(attemptOutcomes, runOutcomes);
                    break;
                }
            } catch (RuntimeException e) {
                wallMs = SystemClock.elapsedRealtime() - t0;
                ms = wallMs;
                finalOutcomes.merge(ScreenCapturer.Outcome.ERROR, 1, Integer::sum);
                Log.i(TAG, "iteration#" + (i + 1) + " threw " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
            executed = i + 1;
            attempts += runAttempts[0];
            retries += Math.max(0, runAttempts[0] - 1);
            merge(attemptOutcomes, runOutcomes);
            Log.i(TAG, "iteration#" + (i + 1) + " captureMs=" + ms + " wallMs=" + wallMs
                    + " attempts=" + runAttempts[0] + " outcomes=" + runOutcomes);
        }

        long meanMs = ok > 0 ? sumMs / ok : 0L;
        List<String> advice = buildAdvice(n, executed, ok, meanMs, maxMs, firstMs, timeoutMs,
                retries, attemptOutcomes, finalOutcomes, detectBlank, warmup);

        String text = buildText(n, executed, ok, meanMs, maxMs, firstMs, attempts, retries,
                attemptOutcomes, finalOutcomes, params, advice);
        String summary = String.format(Locale.US,
                "selftest n=%d executed=%d ok=%d mean=%d max=%d first=%d attempts=%d retries=%d"
                        + " timeout=%d black=%d secured=%d error=%d"
                        + " params {%s} advice=%s",
                n, executed, ok, meanMs, maxMs, firstMs, attempts, retries,
                count(attemptOutcomes, ScreenCapturer.Outcome.TIMEOUT),
                count(attemptOutcomes, ScreenCapturer.Outcome.BLACK),
                count(attemptOutcomes, ScreenCapturer.Outcome.SECURED),
                count(attemptOutcomes, ScreenCapturer.Outcome.ERROR),
                params, String.join(" | ", advice));
        Log.i(TAG, summary);
        Log.i(TAG, "report\n" + text);
        return new Result(text, summary);
    }

    private static List<String> buildAdvice(int n, int executed, int ok, long meanMs, long maxMs,
                                            long firstMs, long timeoutMs, int retries,
                                            EnumMap<ScreenCapturer.Outcome, Integer> attempts,
                                            EnumMap<ScreenCapturer.Outcome, Integer> finals,
                                            boolean detectBlank, boolean warmup) {
        List<String> out = new ArrayList<>();
        int timeout = count(attempts, ScreenCapturer.Outcome.TIMEOUT);
        int black = count(attempts, ScreenCapturer.Outcome.BLACK);
        int secured = count(attempts, ScreenCapturer.Outcome.SECURED);
        int error = Math.max(count(attempts, ScreenCapturer.Outcome.ERROR),
                count(finals, ScreenCapturer.Outcome.ERROR));

        if (secured > 0) {
            out.add("当前页面受保护（SECURED " + secured + " 次），换到普通页面再自检；这不是截图参数问题。");
        }
        if (timeout > 0) {
            out.add("超时 " + timeout + " 次 → 建议把单次超时从 " + timeoutMs + "ms 提到 "
                    + nextTimeout(timeoutMs) + "ms；若仍超时，再检查最小间隔。");
        }
        if (error > 0) {
            // 只在建议里给方向，不替用户写 prefs；数值由页面上的滑块决定。
            out.add("系统错误 " + error + " 次 → 先把最小间隔提到 500–1000ms 再测；"
                    + "仍报错再看系统限流/无障碍权限。");
        }
        if (black > 0) {
            if (detectBlank) {
                out.add("黑屏判定 " + black + " 次 → 页面若有内容，降低黑屏阈值（当前页可先关黑屏检测复测），"
                        + "排除深色页面被误杀。");
            } else {
                out.add("黑屏检测已关闭：纯黑/空图会按成功计，成功率会偏高。");
            }
        }
        if (timeout == 0 && error == 0 && black == 0 && ok > 0 && meanMs > timeoutMs * 7 / 10) {
            out.add("均值 " + meanMs + "ms 已接近超时 " + timeoutMs + "ms 的 70% → 建议把超时提到 "
                    + nextTimeout(timeoutMs) + "ms，给系统抖动留余量。");
        }
        if (retries > 0 && timeout == 0 && error == 0) {
            out.add("发生 " + retries + " 次重试但最终成功 → 当前超时/间隔处于边缘，建议适度上调。");
        }
        if (warmup && firstMs > 0 && ok >= 2 && firstMs > meanMs + meanMs / 2) {
            out.add("预热已开，但首轮 " + firstMs + "ms 明显高于均值 " + meanMs
                    + "ms → 预热可能未吃掉首帧慢，可对比关闭预热后的首轮。");
        }
        if (ok == 0) {
            out.add("本轮没有成功样本 → 先换普通页面；仍失败就把超时提到 1500ms、间隔 1000ms 后再测。");
        } else if (ok == executed && timeout == 0 && error == 0 && black == 0 && retries == 0
                && meanMs <= timeoutMs * 7 / 10) {
            out.add("当前参数匹配本机：样本全部成功，均值 " + meanMs + "ms，无重试、无黑屏/超时。");
        }
        if (out.isEmpty()) {
            out.add("样本不足以给出稳定结论（成功 " + ok + "/" + n + "），建议在普通页面上再跑一轮。");
        }
        return out;
    }

    private static String buildText(int n, int executed, int ok, long meanMs, long maxMs,
                                    long firstMs, int attempts, int retries,
                                    EnumMap<ScreenCapturer.Outcome, Integer> attemptOutcomes,
                                    EnumMap<ScreenCapturer.Outcome, Integer> finalOutcomes,
                                    String params, List<String> advice) {
        StringBuilder sb = new StringBuilder();
        sb.append("截图自检 ").append(executed).append('/').append(n).append(" 次\n");
        sb.append("当前参数\n  ").append(params).append('\n');
        sb.append("结果\n");
        sb.append("  成功 ").append(ok).append('/').append(executed)
                .append(" · 截图耗时 均 ").append(meanMs).append("ms 最大 ").append(maxMs).append("ms");
        if (firstMs >= 0) {
            sb.append(" 首轮 ").append(firstMs).append("ms");
        }
        sb.append("（不含节流等待）\n");
        sb.append("  截图趟 ").append(attempts).append("（重试 ").append(retries).append("）");
        for (ScreenCapturer.Outcome o : ScreenCapturer.Outcome.values()) {
            int c = count(attemptOutcomes, o);
            if (c > 0) {
                sb.append(" · ").append(o).append(' ').append(c);
            }
        }
        sb.append('\n');
        if (!finalOutcomes.isEmpty()) {
            sb.append("  最终失败  ");
            for (ScreenCapturer.Outcome o : ScreenCapturer.Outcome.values()) {
                int c = count(finalOutcomes, o);
                if (c > 0) {
                    sb.append(o).append(' ').append(c).append("  ");
                }
            }
            sb.append('\n');
        }
        sb.append("建议\n");
        for (String a : advice) {
            sb.append("  · ").append(a).append('\n');
        }
        return sb.toString().trim();
    }

    private static long nextTimeout(long current) {
        long[] steps = {300L, 500L, 800L, 1500L, 3000L, 5000L};
        for (long s : steps) {
            if (s > current) {
                return s;
            }
        }
        return 5000L;
    }

    private static int count(EnumMap<ScreenCapturer.Outcome, Integer> map,
                             ScreenCapturer.Outcome key) {
        Integer v = map.get(key);
        return v == null ? 0 : v;
    }

    private static void merge(EnumMap<ScreenCapturer.Outcome, Integer> into,
                              EnumMap<ScreenCapturer.Outcome, Integer> from) {
        for (ScreenCapturer.Outcome o : ScreenCapturer.Outcome.values()) {
            Integer v = from.get(o);
            if (v != null) {
                into.merge(o, v, Integer::sum);
            }
        }
    }
}
