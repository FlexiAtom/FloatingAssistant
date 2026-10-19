// 悬浮助手 · P2-T：STQ 特化 libllama 的薄 JNI 桥（端侧整屏 MT）。
// 语向不在此层：入参即完整 user 消息，提示词由 Java 侧 MtPrompt 按官方句式组装。
// 只链引擎四库（llama/ggml/ggml-cpu/ggml-base）+ log；聊天模板走 llama_chat_apply_template
// 内置 HUNYUAN_DENSE 渲染器，故不链 libllama-common.so。
// API 全部按当前头文件（~/clone/llama-stq/include/llama.h），不用已废弃的
// llama_load_model_from_file / llama_new_context_with_model / llama_sample_token_greedy。
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define TAG "LlamaMt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 一次翻译最多生成的 token；覆盖整屏译文足够，防失控长生成。
static const int kMaxGen = 256;

struct MtCtx {
    llama_model          * model   = nullptr;
    llama_context        * ctx     = nullptr;
    const llama_vocab    * vocab   = nullptr;
    llama_sampler        * smpl    = nullptr;
    std::string            tmpl;          // 聊天模板串
    int32_t                n_ctx         = 0;  // 有效上下文
    int32_t                n_batch       = 0;  // 单次 decode 逻辑上限
    llama_batch            batch;              // 复用：容量 = n_batch
    bool                   ready         = false;
};

// 把 [0,n) 个 token 作为一段喂给模型；pos 从 *n_past 起递增，最后一段的末 token 出 logits。
// with_logits 标记本段是否需要在末位取 logits（prefill 尾段 / 生成每步为 true）。
static int eval_range(MtCtx * S, const llama_token * toks, int n, llama_pos pos0, bool with_logits) {
    S->batch.n_tokens = n;
    for (int i = 0; i < n; i++) {
        S->batch.token[i]    = toks[i];
        S->batch.pos[i]      = pos0 + i;
        S->batch.n_seq_id[i] = 1;
        S->batch.seq_id[i][0] = 0;
        S->batch.logits[i]   = 0;
    }
    if (with_logits) {
        S->batch.logits[n - 1] = 1;   // 只末位取 logits
    }
    return llama_decode(S->ctx, S->batch);
}

// 单 token 步（生成期）：pos = n_past，取 logits。
static int eval_one(MtCtx * S, llama_token tok, llama_pos pos) {
    S->batch.n_tokens = 1;
    S->batch.token[0]    = tok;
    S->batch.pos[0]      = pos;
    S->batch.n_seq_id[0] = 1;
    S->batch.seq_id[0][0] = 0;
    S->batch.logits[0]   = 1;
    return llama_decode(S->ctx, S->batch);
}

// UTF-8 字符串的两遍分词：先探需要多少 token，再实际写入。
// 返回 token 数；<0 表示出错。out 会被 resize。
static int tokenize_utf8(const llama_vocab * vocab, const std::string & text,
                         bool add_special, bool parse_special, std::vector<llama_token> & out) {
    int n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, parse_special);
    if (n < 0) {
        out.resize((size_t) -n);
        n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), add_special, parse_special);
    }
    if (n < 0) {
        return -1;
    }
    out.resize((size_t) n);
    return n;
}

static void release_ctx(MtCtx * S) {
    if (!S) return;
    llama_batch_free(S->batch);
    if (S->smpl)  llama_sampler_free(S->smpl);
    if (S->ctx)   llama_free(S->ctx);
    if (S->model) llama_model_free(S->model);
    delete S;
}

extern "C" {

// nativeInit(String modelPath, int nCtx, int nThreads) -> long (MtCtx* 句柄；0=失败)
JNIEXPORT jlong JNICALL
Java_com_flexiatom_floatingassistant_mt_LlamaMtEngine_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring jpath, jint nCtx, jint nThreads) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    std::string modelPath(path);
    env->ReleaseStringUTFChars(jpath, path);

    static bool backend_inited = false;
    if (!backend_inited) {
        llama_backend_init();
        backend_inited = true;
    }

    MtCtx * S = new MtCtx();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;         // 全 CPU（本设备无可用加速路径）
    S->model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!S->model) {
        LOGE("model load failed: %s", modelPath.c_str());
        delete S;
        return 0;
    }
    S->vocab = llama_model_get_vocab(S->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx      = (uint32_t) nCtx;
    cp.n_batch    = (uint32_t) nCtx;   // 逻辑批次上限取 n_ctx，分块由 n_parallel 之外的位置控制
    cp.n_threads     = (uint32_t) nThreads;
    cp.n_threads_batch = (uint32_t) nThreads;
    S->ctx = llama_init_from_model(S->model, cp);
    if (!S->ctx) {
        LOGE("ctx init failed (nCtx=%d nThreads=%d)", nCtx, nThreads);
        llama_model_free(S->model);
        delete S;
        return 0;
    }
    S->n_ctx   = llama_n_ctx(S->ctx);
    S->n_batch = (int32_t) llama_n_batch(S->ctx);
    S->batch   = llama_batch_init(S->n_batch > 0 ? S->n_batch : S->n_ctx, 0, 1);

    // 贪心采样链（切片按 temp=0 口径，与已证句一致）
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    S->smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(S->smpl, llama_sampler_init_greedy());

    const char * t = llama_model_chat_template(S->model, nullptr);
    if (t) {
        S->tmpl = t;
    }

    LOGI("mt init ok nCtx=%d nBatch=%d tmplLen=%zu addBos=%d",
         S->n_ctx, S->n_batch, S->tmpl.size(), (int) llama_vocab_get_add_bos(S->vocab));
    S->ready = true;
    return (jlong) (intptr_t) S;
}

// nativeTranslate(long handle, String user) -> byte[] (UTF-8；空=失败/降级)
//
// **本层不再拼任何提示词**：入参就是完整的 user 消息内容，由 Java 侧（MtPrompt）组装。
// 从前这里写死 `"Translate into English: " + text`（zh→en 切片硬编），于是换语向必须重编 .so；
// 语言表与官方句式都在 Java 那边，把策略留在 C++ 只会让每次改句式都过一次 NDK。
JNIEXPORT jbyteArray JNICALL
Java_com_flexiatom_floatingassistant_mt_LlamaMtEngine_nativeTranslate(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jstring juser) {
    MtCtx * S = (MtCtx *) (intptr_t) handle;
    if (!S || !S->ready || !juser) {
        return env->NewByteArray(0);
    }
    const char * cuser = env->GetStringUTFChars(juser, nullptr);
    if (!cuser) return env->NewByteArray(0);
    std::string user(cuser);
    env->ReleaseStringUTFChars(juser, cuser);

    if (user.empty()) {
        return env->NewByteArray(0);
    }

    // 走内置聊天模板（HUNYUAN_DENSE）；失败则退化为裸 prompt（保底）。
    std::string prompt = user;
    if (!S->tmpl.empty()) {
        llama_chat_message msg[1];
        msg[0].role    = "user";
        msg[0].content = user.c_str();
        int need = llama_chat_apply_template(S->tmpl.c_str(), msg, 1, /*add_ass=*/ true, nullptr, 0);
        if (need > 0) {
            std::string rendered((size_t) need, '\0');
            int got = llama_chat_apply_template(S->tmpl.c_str(), msg, 1, true, rendered.data(), need);
            if (got >= 0) {
                rendered.resize((size_t) got);
                prompt = rendered;
            } else {
                LOGE("chat_apply_template write failed rc=%d → raw prompt", got);
            }
        } else {
            LOGE("chat_apply_template size failed rc=%d → raw prompt", need);
        }
    }

    // 分词：parse_special 让模板里的特殊 token 正确展开；
    // add_special 仅在模型自带 BOS 时才前置（避免与模板里的 BOS 重复）。
    const bool add_bos = llama_vocab_get_add_bos(S->vocab);
    std::vector<llama_token> embd;
    int nTok = tokenize_utf8(S->vocab, prompt, /*add_special=*/ !S->tmpl.empty() ? false : add_bos,
                             /*parse_special=*/ true, embd);
    if (nTok <= 0) {
        LOGE("tokenize failed n=%d", nTok);
        return env->NewByteArray(0);
    }

    // 截断到 n_ctx 预留生成余量；超长页丢尾（记 truncated）。
    const int32_t cap = S->n_ctx - kMaxGen;
    bool truncated = false;
    if ((int) embd.size() > cap) {
        embd.resize((size_t) (cap > 0 ? cap : 0));
        truncated = true;
    }
    if (embd.empty()) {
        return env->NewByteArray(0);
    }

    // 复用同一 model+ctx：每页开始前清 KV。
    llama_memory_clear(llama_get_memory(S->ctx), /*data=*/ true);

    llama_pos n_past = 0;
    // 分块 prefill：每块 <= n_batch，仅最后一块末位取 logits。
    int chunk = S->n_batch > 0 ? S->n_batch : S->n_ctx;
    size_t off = 0;
    while (off < embd.size()) {
        int rem = (int) (embd.size() - off);
        int take = rem < chunk ? rem : chunk;
        bool last = (off + (size_t) take) >= embd.size();
        if (eval_range(S, embd.data() + off, take, n_past, /*with_logits=*/ last) != 0) {
            LOGE("prefill decode failed at off=%zu take=%d", off, take);
            return env->NewByteArray(0);
        }
        n_past += take;
        off += take;
    }

    // 贪心生成。Hy-MT2 在此量化下不吐 EOG：句子讲完会复读 BOS(120000) 等控制 token
    // （special=false 解码成空串）。故显式把 BOS / 控制 token 当终止符，避免跑满 kMaxGen 白烧算力。
    const llama_token bos = llama_vocab_bos(S->vocab);
    std::string out;
    int gen = 0;
    int empty_streak = 0;
    for (; gen < kMaxGen; gen++) {
        llama_token id = llama_sampler_sample(S->smpl, S->ctx, -1);
        if (llama_vocab_is_eog(S->vocab, id)) {
            break;
        }
        // 复读 BOS（模型"想重新开始"）或任何控制 token → 视为本句结束。
        if (id == bos || llama_vocab_is_control(S->vocab, id)) {
            break;
        }
        llama_sampler_accept(S->smpl, id);

        char piece[256];
        int pn = llama_token_to_piece(S->vocab, id, piece, (int32_t) sizeof(piece), 0, /*special=*/ false);
        if (pn > 0) {
            out.append(piece, (size_t) pn);
            empty_streak = 0;
        } else if (++empty_streak >= 8) {
            break;   // 连续空解码：兜底防退化循环
        }

        if (n_past >= S->n_ctx) {   // 上下文创满，停止
            break;
        }
        if (eval_one(S, id, n_past) != 0) {
            LOGE("gen decode failed at n_past=%d", n_past);
            break;
        }
        n_past += 1;
    }

    LOGI("mt translate inTok=%zu truncated=%d gen=%d outChars=%zu",
         embd.size(), (int) truncated, gen, out.size());

    if (out.empty()) {
        return env->NewByteArray(0);
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (arr) {
        env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    }
    return arr;
}

// nativeRelease(long handle)
JNIEXPORT void JNICALL
Java_com_flexiatom_floatingassistant_mt_LlamaMtEngine_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    release_ctx((MtCtx *) (intptr_t) handle);
}

} // extern "C"
