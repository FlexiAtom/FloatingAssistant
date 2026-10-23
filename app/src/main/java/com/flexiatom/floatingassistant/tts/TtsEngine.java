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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.flexiatom.floatingassistant.core.RuntimeStats;
import com.flexiatom.floatingassistant.core.SettingsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产朗读通道：默认档 {@link TtsModelKind#MELO}（裁二，依据见 {@code finished/tts-engine-replacement.md}）。
 *
 * <p>三条实测读数把设计钉死：
 * <ul>
 *   <li>冷加载 7.2 s、峰值 401 MB ⇒ 引擎按需加载、空闲 {@value #IDLE_UNLOAD_MS} ms 后卸载、前台切走立即卸，
 *       不常驻、不与 MT HEAVY 同驻。</li>
 *   <li>RTF 1.36（4 线程最优）> 1 ⇒ <b>合成慢于播放</b>，流式朗读必然中途断流。这里分句合成、逐句写
 *       {@link AudioTrack}（{@code write} 的背压就是播放节奏），首句一出就开口，后面的句子在缓冲里续。</li>
 *   <li>失败不静默（TouchTool {@code speak()} 静默 return 的反面教材）：模型缺失、合成空、播放异常都
 *       经 {@link Listener#onNotice} 显式上报，并记一行 {@code RuntimeStats.run("tts", …)} 进自检表。</li>
 * </ul>
 *
 * <p>线程：所有引擎/播放状态只在单工作线程上读写（{@code tts-speak}）；UI 线程只发命令与收通知。
 * {@link #stop()} 用代次号作废在途任务——阻塞在 {@code AudioTrack.write} 里的旧任务醒来后会自行收尾，
 * 不会再出声。
 */
public final class TtsEngine {

    private static final String TAG = "TTS";
    private static final TtsModelKind KIND = TtsModelKind.MELO;
    /** 实测 avgRTF 1.51→1.36（2→4 线程）后收益见顶，再高只增功耗。 */
    private static final int THREADS = 4;
    private static final long IDLE_UNLOAD_MS = 60_000L;
    /** 单块字数上限：控制首句等待（40 字 ≈ 10 s 音频 ≈ 14 s 合成）。 */
    private static final int MAX_CHUNK_CHARS = 40;
    private static final int SPEAKER_ID = 0;

    public interface Listener {
        /** 必须让用户看见的失败/提示（主线程回调）；调用方决定怎么显示。 */
        void onNotice(String text);

        /** 朗读状态变化（主线程回调）：true＝合成/播放进行中，false＝结束/停止/失败。 */
        default void onSpeakingChanged(boolean speaking) {
        }
    }

    private final Context appContext;
    private final SettingsStore settings;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-speak");
        t.setDaemon(true);
        return t;
    });

    /** 代次：{@link #stop()} 递增，在途任务发现代次不符即闭嘴退出。 */
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean speaking;
    /** 工作线程自有；仅由工作线程赋值/释放。 */
    private SherpaTtsEngine engine;
    private volatile AudioTrack track;
    private volatile long lastFinishAt;

    public TtsEngine(Context context, SettingsStore settings, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.settings = settings;
        this.listener = listener;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    /** 读一段文本；正在读时先停旧的再从头读（按钮语义＝「重新朗读」）。 */
    public void speak(String text) {
        String clean = normalize(text);
        if (clean.isEmpty()) {
            notice("朗读：没有可读文本");
            return;
        }
        if (!settings.ttsEnabled()) {
            notice("朗读已关闭（在应用设置中开启）");
            return;
        }
        stop();
        setSpeakingState(true);
        int gen = generation.incrementAndGet();
        worker.execute(() -> doSpeak(clean, gen));
    }

    /** 停止当前朗读；已停下时是 no-op（幂等，关闭面板/切前台都可直接调）。 */
    public void stop() {
        generation.incrementAndGet();
        setSpeakingState(false);
        AudioTrack t = track;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable err) {
                Log.w(TAG, "track stop threw " + err.getClass().getSimpleName());
            }
        }
    }

    /** 面板关闭/服务解绑：先停，再释放引擎（401 MB 峰值决定它不常驻）。 */
    public void unload() {
        stop();
        worker.execute(this::releaseEngine);
    }

    /**
     * 前台切走：只在空闲时释放引擎，朗读中不打断（与 MT 的 {@code unloadIfIdle} 同语义）——
     * 读一半被切走是用户主动换 App，停声比省内存更难受；读完由空闲计时兜底。
     */
    public void unloadIfIdle() {
        if (speaking) {
            return;
        }
        worker.execute(() -> {
            if (engine != null) {
                Log.i(TAG, "foreground left, release idle engine");
            }
            releaseEngine();
        });
    }

    public void shutdown() {
        unload();
        worker.shutdown();
    }

    private void doSpeak(String text, int gen) {
        long t0 = System.currentTimeMillis();
        boolean ok = false;
        AudioTrack local = null;
        try {
            SherpaTtsEngine e = ensureEngine();
            if (e == null) {
                fail(gen, "朗读失败：模型未就绪（见 TTS 日志）");
                return;
            }
            List<String> chunks = split(text);
            long totalSamples = 0;
            local = newTrack(e.sampleRate);
            track = local;
            local.play();
            for (String chunk : chunks) {
                if (!isCurrent(gen)) {
                    return;
                }
                // 设置页可能在读一半时关掉朗读或改语速：每句边界现读，当前句读完再执行
                if (!settings.ttsEnabled()) {
                    Log.i(TAG, "tts disabled mid-read, stop at chunk boundary");
                    notice("朗读已关闭，已停止");
                    return;
                }
                long tSynth = System.nanoTime();
                float[] samples = e.generateSamples(chunk, SPEAKER_ID, settings.ttsSpeed());
                long synthMs = (System.nanoTime() - tSynth) / 1_000_000L;
                if (samples == null) {
                    fail(gen, "朗读失败：合成出错（见 TTS 日志）");
                    return;
                }
                totalSamples += samples.length;
                long audioMs = (long) samples.length * 1000L / e.sampleRate;
                Log.i(TAG, "synth chars=" + chunk.length() + " synthMs=" + synthMs
                        + " audioMs=" + audioMs + " queuedSamples=" + totalSamples);
                byte[] pcm = toPcm16(samples);
                int off = 0;
                while (off < pcm.length && isCurrent(gen)) {
                    int n = local.write(pcm, off, pcm.length - off);
                    if (n <= 0) {
                        if (!isCurrent(gen)) {
                            return;
                        }
                        throw new IllegalStateException("AudioTrack.write=" + n);
                    }
                    off += n;
                }
            }
            if (!isCurrent(gen)) {
                return;
            }
            drain(local, totalSamples, gen);
            ok = isCurrent(gen);
            Log.i(TAG, "speak done chars=" + text.length() + " chunks=" + chunks.size()
                    + " samples=" + totalSamples
                    + " wallMs=" + (System.currentTimeMillis() - t0));
        } catch (Throwable err) {
            Log.e(TAG, "speak failed gen=" + gen + " "
                    + err.getClass().getName() + ": " + err.getMessage(), err);
            fail(gen, "朗读失败：" + err.getClass().getSimpleName());
        } finally {
            boolean current = generation.get() == gen;
            if (current) {
                setSpeakingState(false);
                track = null;
                RuntimeStats.of(appContext).run("tts", System.currentTimeMillis() - t0, ok);
                scheduleIdleUnload();
            } else if (!speaking) {
                // 被 stop() 打断：stop 在主线程、不碰工作线程私有的 engine，在这里补一次空闲卸载。
                scheduleIdleUnload();
            }
            if (local != null) {
                try {
                    local.pause();
                    local.flush();
                    local.release();
                } catch (Throwable err) {
                    Log.w(TAG, "track release threw " + err.getClass().getSimpleName());
                }
            }
        }
    }

    /** 等缓冲放完：帧数按写入采样计，另给 10 s 余量防慢时钟；超时只警告，不装成功。 */
    private void drain(AudioTrack t, long frames, int gen) throws InterruptedException {
        long deadline = System.currentTimeMillis()
                + frames * 1000L / Math.max(1, t.getSampleRate()) + 10_000L;
        while (isCurrent(gen) && t.getPlaybackHeadPosition() < frames) {
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "drain timeout frames=" + frames
                        + " head=" + t.getPlaybackHeadPosition());
                return;
            }
            Thread.sleep(20);
        }
    }

    private boolean isCurrent(int gen) {
        return speaking && generation.get() == gen;
    }

    /** 状态切换只在主线程通知一次；UI 侧不必再自己 post。 */
    private void setSpeakingState(boolean value) {
        boolean changed = speaking != value;
        speaking = value;
        if (!changed) {
            return;
        }
        main.post(() -> {
            try {
                listener.onSpeakingChanged(value);
            } catch (Throwable err) {
                Log.w(TAG, "speaking listener threw " + err.getClass().getSimpleName());
            }
        });
    }

    private void fail(int gen, String reason) {
        if (!isCurrent(gen)) {
            return;
        }
        Log.e(TAG, reason);
        notice(reason);
    }

    private void notice(String text) {
        main.post(() -> {
            try {
                listener.onNotice(text);
            } catch (Throwable err) {
                Log.w(TAG, "listener threw " + err.getClass().getSimpleName());
            }
        });
    }

    private SherpaTtsEngine ensureEngine() {
        if (engine != null) {
            return engine;
        }
        engine = SherpaTtsEngine.create(appContext, KIND, false, THREADS);
        if (engine == null) {
            Log.e(TAG, "engine load failed kind=" + KIND.alias);
        }
        return engine;
    }

    private void releaseEngine() {
        SherpaTtsEngine e = engine;
        engine = null;
        if (e != null) {
            e.release();
            Log.i(TAG, "engine unloaded (idle/foreground left)");
        }
    }

    private void scheduleIdleUnload() {
        lastFinishAt = System.currentTimeMillis();
        main.postDelayed(() -> worker.execute(() -> {
            if (speaking || System.currentTimeMillis() - lastFinishAt < IDLE_UNLOAD_MS) {
                return;
            }
            releaseEngine();
        }), IDLE_UNLOAD_MS);
    }

    private static AudioTrack newTrack(int sampleRate) {
        int channelMask = AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
        int buf = Math.max(minBuf, sampleRate / 4 * 2);
        return new AudioTrack.Builder()
                // 无障碍覆盖层代读文本：用 ASSISTANCE_ACCESSIBILITY 而不是 MEDIA，
                // 音量键走"无障碍"通道，也不参与媒体静音/被其他 App 抢占。
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private static byte[] toPcm16(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            if (v > 1f) {
                v = 1f;
            } else if (v < -1f) {
                v = -1f;
            }
            int s = (int) (v * 32767f);
            out[i * 2] = (byte) (s & 0xff);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    /** 按句切块：标点切 + 长度上限兜底（无标点的长英文段也能分出首句）。 */
    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            boolean cut = c == '。' || c == '！' || c == '？' || c == '；'
                    || c == '!' || c == '?' || c == ';' || c == '\n'
                    || cur.length() >= MAX_CHUNK_CHARS;
            if (cut) {
                addChunk(out, cur);
            }
        }
        addChunk(out, cur);
        return out;
    }

    private static void addChunk(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', '\n').trim();
    }
}
