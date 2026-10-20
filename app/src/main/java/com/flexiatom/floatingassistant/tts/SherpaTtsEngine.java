package com.flexiatom.floatingassistant.tts;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;

/**
 * sherpa-onnx 端侧合成（读法①）：一次性加载一个模型目录，逐句合成到 wav。
 *
 * <p>**没有静默降级**。这一条是从 TouchTool 读来的反面教材（{@code TextToSpeechAction} 首行
 * {@code if (tts == null) return;}——用户点了「朗读」而机器不动作，故障只能靠人复现）。所以
 * 装配失败一律走 {@link #create} 返回 {@code null} + {@code Log.e} 带上缺哪个文件、哪条异常；
 * 合成失败返回 {@code null} 并把 throwable 打进日志。
 *
 * <p>路径全部走文件系统：{@code new OfflineTts(null, config)} 的对 {@code assetManager} 做了
 * {@code ifnull} 分派（1.13.8 字节码实测），null 走 {@code newFromFile} ⇒ 模型放
 * {@code getExternalFilesDir(null)} 下，与 OCR/MT 现行约定一致，不必把 33 MB 塞进 assets。
 */
public final class SherpaTtsEngine {

    private static final String TAG = "P5TTS";

    /** 上游模型卡给的规则序：手机号 → 日期 → 数字 → 多音字。 */
    private static final String[] FST_ORDER = {"phone.fst", "date.fst", "number.fst", "new_heteronym.fst"};

    public final TtsModelKind kind;
    public final int sampleRate;
    public final int numSpeakers;
    public final long loadMs;
    /** 实际接进配置的小规则（日志要能证明这一趟到底挂没挂规则）。 */
    public final String ruleFsts;
    public final String ruleFars;

    private final OfflineTts tts;

    private SherpaTtsEngine(OfflineTts tts, TtsModelKind kind, String ruleFsts, String ruleFars,
                           long loadMs) {
        this.tts = tts;
        this.kind = kind;
        this.ruleFsts = ruleFsts;
        this.ruleFars = ruleFars;
        this.loadMs = loadMs;
        this.sampleRate = tts.sampleRate();
        this.numSpeakers = tts.numSpeakers();
    }

    /**
     * 加载模型；**任何一步不满足都返回 null 并把原因写进日志**（不缺省、不兜底到另一档）。
     *
     * @param useFar 是否挂 {@code rule.far}（172 MB 的文本规整档）。这一档的存在与否正是要量的
     *               footprint 断崖，所以由调用方给，不在此处按"文件在就带上"自动决定。
     */
    public static SherpaTtsEngine create(Context ctx, TtsModelKind kind, boolean useFar, int threads) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            Log.e(TAG, "kind=" + kind.alias + " no external files dir");
            return null;
        }
        File dir = kind.dirOf(base);
        File model = new File(dir, "model.onnx");
        File tokens = new File(dir, "tokens.txt");
        if (!model.isFile() || !tokens.isFile()) {
            Log.e(TAG, "kind=" + kind.alias + " model missing dir=" + dir.getAbsolutePath()
                    + " exists=" + dir.isDirectory() + " model.onnx=" + model.isFile()
                    + " tokens.txt=" + tokens.isFile());
            return null;
        }

        StringBuilder fsts = new StringBuilder();
        for (String name : FST_ORDER) {
            File f = new File(dir, name);
            if (f.isFile()) {
                if (fsts.length() > 0) {
                    fsts.append(',');
                }
                fsts.append(f.getAbsolutePath());
            }
        }
        File far = new File(dir, "rule.far");
        String ruleFars = useFar ? far.getAbsolutePath() : "";
        if (useFar && !far.isFile()) {
            Log.e(TAG, "kind=" + kind.alias + " 要求挂 rule.far 但文件不在：" + ruleFars);
            return null;
        }

        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(model.getAbsolutePath());
        vits.setTokens(tokens.getAbsolutePath());
        File lexicon = new File(dir, "lexicon.txt");
        vits.setLexicon(lexicon.isFile() ? lexicon.getAbsolutePath() : "");
        // jieba / espeak 词典目录：模型自带才指（两档各自不同，写死一份会让另一档谎报路径）。
        File dict = new File(dir, "dict");
        vits.setDictDir(dict.isDirectory() ? dict.getAbsolutePath() : "");
        File espeak = new File(dir, "espeak-ng-data");
        vits.setDataDir(espeak.isDirectory() ? espeak.getAbsolutePath() : "");
        vits.setNoiseScale(0.667f);
        vits.setNoiseScaleW(0.8f);
        vits.setLengthScale(1.0f);

        OfflineTtsModelConfig modelCfg = new OfflineTtsModelConfig();
        modelCfg.setVits(vits);
        modelCfg.setNumThreads(threads);
        modelCfg.setProvider("cpu");
        modelCfg.setDebug(false);

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelCfg);
        config.setRuleFsts(fsts.toString());
        config.setRuleFars(ruleFars);

        long t = System.nanoTime();
        OfflineTts tts;
        try {
            tts = new OfflineTts(null, config);
        } catch (Throwable err) {
            Log.e(TAG, "kind=" + kind.alias + " load failed: " + err.getClass().getName()
                    + ": " + err.getMessage(), err);
            return null;
        }
        long loadMs = (System.nanoTime() - t) / 1_000_000L;
        Log.i(TAG, "loaded kind=" + kind.alias + " loadMs=" + loadMs
                + " threads=" + threads + " sampleRate=" + tts.sampleRate()
                + " numSpeakers=" + tts.numSpeakers()
                + " dictDir=" + (vits.getDictDir().isEmpty() ? "-" : vits.getDictDir())
                + " dataDir=" + (vits.getDataDir().isEmpty() ? "-" : vits.getDataDir())
                + " ruleFsts=" + (fsts.length() == 0 ? "-" : fsts.toString())
                + " ruleFars=" + (ruleFars.isEmpty() ? "-" : ruleFars));
        return new SherpaTtsEngine(tts, kind, fsts.toString(), ruleFars, loadMs);
    }

    /** 一次合成的量测结果；{@code wav} 为落盘路径（{@code null}＝没写出去）。 */
    public static final class Synthesis {
        public final String text;
        public final long genMs;
        public final int samples;
        public final float audioSec;
        public final float rtf;
        public final File wav;

        Synthesis(String text, long genMs, int samples, int sampleRate, File wav) {
            this.text = text;
            this.genMs = genMs;
            this.samples = samples;
            this.audioSec = sampleRate > 0 ? (float) samples / sampleRate : 0f;
            this.rtf = this.audioSec > 0 ? genMs / 1000f / this.audioSec : 0f;
            this.wav = wav;
        }
    }

    /**
     * 合成一句并返回原始浮点采样（生产播放用，不落盘）；失败返回 {@code null}，原因进日志。
     * 与 {@link #synthesize} 共用同一条「不静默」约定：空文本、空音频、异常都各打一行。
     */
    public float[] generateSamples(String text, int sid, float speed) {
        if (text == null || text.isEmpty()) {
            Log.e(TAG, "generateSamples empty text kind=" + kind.alias);
            return null;
        }
        try {
            GeneratedAudio audio = tts.generate(text, sid, speed);
            if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                Log.e(TAG, "generate samples empty kind=" + kind.alias + " text=[" + text + "]");
                return null;
            }
            return audio.getSamples();
        } catch (Throwable err) {
            Log.e(TAG, "generate samples failed kind=" + kind.alias + " text=[" + text + "] "
                    + err.getClass().getName() + ": " + err.getMessage(), err);
            return null;
        }
    }

    /** 合成一句并落 wav；失败返回 {@code null}（异常进日志，不吞）。 */
    public Synthesis synthesize(String text, int sid, float speed, File wavOut) {
        GeneratedAudio audio;
        long t = System.nanoTime();
        try {
            audio = tts.generate(text, sid, speed);
        } catch (Throwable err) {
            Log.e(TAG, "generate failed kind=" + kind.alias + " text=[" + text + "] "
                    + err.getClass().getName() + ": " + err.getMessage(), err);
            return null;
        }
        long genMs = (System.nanoTime() - t) / 1_000_000L;
        if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
            Log.e(TAG, "generate empty kind=" + kind.alias + " text=[" + text + "]");
            return null;
        }
        boolean saved = false;
        try {
            File parent = wavOut.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Log.e(TAG, "mkdirs failed " + parent.getAbsolutePath());
            } else {
                saved = audio.save(wavOut.getAbsolutePath());
            }
        } catch (Throwable err) {
            Log.e(TAG, "wav save threw " + err.getClass().getName() + ": " + err.getMessage());
        }
        if (!saved) {
            Log.e(TAG, "wav not written kind=" + kind.alias + " → " + wavOut.getAbsolutePath());
        }
        return new Synthesis(text, genMs, audio.getSamples().length, audio.getSampleRate(),
                saved ? wavOut : null);
    }

    public void release() {
        try {
            tts.release();
        } catch (Throwable err) {
            Log.e(TAG, "release threw " + err.getClass().getName() + ": " + err.getMessage());
        }
    }
}
