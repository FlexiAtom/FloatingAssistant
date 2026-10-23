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

package com.flexiatom.floatingassistant.tts;

import android.content.Context;
import android.os.Debug;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * {@code p5_tts} 机读探针：把「端侧自建合成在这台机器上到底能不能跑、跑多快、占多少」量成日志。
 *
 * <p>存在的理由是人机分工——合成不需要手指，但**听感**需要耳朵。这一趟只交付机器能读的东西
 * （加载耗时、逐句 RTF、采样率、音色数、模型目录体积、进程 PSS 增量）并把 wav 落盘，
 * 剩下的「像不像人、数字读对没有」由人听（见 voice.md 长期约定）。
 *
 * <p>失败一律显式：装配失败打 {@code FAIL} 且带原因，没有"跳过就算通过"这条路。
 */
public final class TtsProbe {

    private static final String TAG = "P5TTS";

    private static final String[] ZH_TEXTS = {
            "本软件按现状提供，不附加任何担保。",
            "点击悬浮球可以即时翻译当前屏幕。",
            "端侧模型在这台机器上运行，不会上传任何图像内容。",
            "会议定在2026年10月20日14时30分，预算3.5万元。",
            "内存占用受档位限制，低端机建议关闭后台常驻。",
    };

    private static final String[] EN_TEXTS = {
            "The translation runs entirely on this device.",
            "Please hold the floating ball near the edge of the screen.",
    };

    private TtsProbe() {
    }

    /**
     * @param variant {@link TtsModelKind#alias}，或 {@code both}；不认识的串直接 {@code FAIL}（不兜默认档）
     * @param useFar  是否挂 {@code rule.far}——footprint 断崖就是要量的那一格，必须由命令行给
     */
    public static void run(Context ctx, String variant, boolean useFar, int threads, int sid, float speed) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.e(TAG, "FAIL no external files dir");
            return;
        }
        File outDir = new File(base, "tts_out");
        List<TtsModelKind> kinds;
        if (variant == null || variant.isEmpty() || "both".equalsIgnoreCase(variant)) {
            kinds = List.of(TtsModelKind.values());
        } else {
            TtsModelKind k = TtsModelKind.fromName(variant);
            if (k == null) {
                Log.e(TAG, "FAIL unknown variant=" + variant
                        + " 可选=aishell3|melo|both");
                return;
            }
            kinds = List.of(k);
        }
        Log.i(TAG, "probe start variants=" + variant + " far=" + useFar + " threads=" + threads
                + " sid=" + sid + " speed=" + speed + " outDir=" + outDir.getAbsolutePath());

        for (TtsModelKind kind : kinds) {
            File dir = kind.dirOf(base);
            Log.i(TAG, "dir kind=" + kind.alias + " path=" + dir.getAbsolutePath()
                    + " exists=" + dir.isDirectory() + " files=" + countFiles(dir)
                    + " bytes=" + dirBytes(dir));
            if (!dir.isDirectory()) {
                Log.e(TAG, "FAIL kind=" + kind.alias + " reason=模型目录不在（先 adb push 到 "
                        + base.getAbsolutePath() + "）");
                continue;
            }
            String memBefore = mem();
            SherpaTtsEngine engine = SherpaTtsEngine.create(ctx, kind, useFar, threads);
            if (engine == null) {
                Log.e(TAG, "FAIL kind=" + kind.alias + " reason=装配/加载失败（原因见上一条 P5TTS 日志）");
                continue;
            }
            String memAfterLoad = mem();
            Log.i(TAG, "mem kind=" + kind.alias + " before[" + memBefore + "] afterLoad["
                    + memAfterLoad + "]");

            String[] texts = kind == TtsModelKind.MELO ? concat(ZH_TEXTS, EN_TEXTS) : ZH_TEXTS;
            int maxSid = Math.max(0, engine.numSpeakers - 1);
            if (sid > maxSid) {
                Log.e(TAG, "kind=" + kind.alias + " sid=" + sid + " 超出音色数，改用 0");
                sid = 0;
            }
            int ok = 0;
            long sumGenMs = 0;
            float sumAudioSec = 0;
            float worstRtf = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < texts.length; i++) {
                File wav = new File(outDir, kind.alias + (useFar ? "-far" : "-nf") + "_" + (i + 1) + ".wav");
                SherpaTtsEngine.Synthesis s = engine.synthesize(texts[i], sid, speed, wav);
                if (s == null) {
                    Log.e(TAG, "synth FAIL kind=" + kind.alias + " i=" + (i + 1) + "/" + texts.length
                            + " text=[" + texts[i] + "]");
                    continue;
                }
                ok++;
                sumGenMs += s.genMs;
                sumAudioSec += s.audioSec;
                worstRtf = Math.max(worstRtf, s.rtf);
                Log.i(TAG, "synth kind=" + kind.alias + " i=" + (i + 1) + "/" + texts.length
                        + " sid=" + sid + " chars=" + texts[i].length()
                        + " genMs=" + s.genMs + " audioSec=" + fmt(s.audioSec) + " rtf=" + fmt(s.rtf)
                        + " wav=" + (s.wav == null ? "-" : s.wav.getAbsolutePath()));
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000L;
            Log.i(TAG, "SUMMARY kind=" + kind.alias + " far=" + useFar
                    + " ok=" + ok + "/" + texts.length
                    + " loadMs=" + engine.loadMs
                    + " sampleRate=" + engine.sampleRate
                    + " numSpeakers=" + engine.numSpeakers
                    + " rules=" + (engine.ruleFsts.isEmpty() ? "-" : engine.ruleFsts.split(",").length)
                    + " avgRtf=" + (sumAudioSec > 0 ? fmt(sumGenMs / 1000f / sumAudioSec) : "-")
                    + " worstRtf=" + fmt(worstRtf)
                    + " wallMs=" + wallMs
                    + " afterSynth[" + mem() + "]"
                    + " dirBytes=" + dirBytes(dir));
            engine.release();
            Log.i(TAG, "mem kind=" + kind.alias + " afterRelease[" + mem() + "]");
        }
        Log.i(TAG, "probe done");
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long dirBytes(File f) {
        if (f == null) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        long sum = 0;
        for (File k : kids) {
            sum += dirBytes(k);
        }
        return sum;
    }

    private static int countFiles(File f) {
        if (f == null || !f.isDirectory()) {
            return 0;
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (File k : kids) {
            n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    /**
     * 本进程内存读数。**PSS 那条在这台机器上量不出加载增量**（2026-10-20 实测：加载 30 MB 模型后
     * {@code getProcessMemoryInfo} 报 62.21 MB → 62.21 MB，deltaMb=0.00——HyperOS 上这条对刚 dlopen
     * 的原生映射不计入，或取数时机在页表落定之前）。所以改报两路能动的量：{@code nativeHeapAllocated}
     * （原生 malloc arena，正是 ONNX 权重走的那条）与 {@code /proc/self/statm} 的常驻页。取不到返回 -1。
     */
    private static String mem() {
        StringBuilder sb = new StringBuilder();
        try {
            long nativeBytes = Debug.getNativeHeapAllocatedSize();
            sb.append("nativeHeapMb=").append(fmt(nativeBytes / 1048576f));
        } catch (Throwable err) {
            sb.append("nativeHeapMb=-(").append(err.getClass().getSimpleName()).append(')');
        }
        sb.append(" rssMb=").append(fmt(rssKb() / 1024f));
        return sb.toString();
    }

    /** statm 第 2 列＝常驻页数（页大小按 4 KB 记）；读不到返回 -1。 */
    private static long rssKb() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/statm"))) {
            String line = br.readLine();
            if (line == null) {
                return -1;
            }
            String[] p = line.trim().split("\\s+");
            if (p.length < 2) {
                return -1;
            }
            return Long.parseLong(p[1]) * 4L;
        } catch (Throwable err) {
            Log.e(TAG, "rss read failed: " + err.getClass().getName() + ": " + err.getMessage());
            return -4096;
        }
    }


    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
