// CortexBoard native bridge: minimal, allocation-light wrapper over the llama.cpp C API.
// One Session = model + context + token history used for KV-cache prefix reuse,
// which keeps multi-step agent turns fast (each step shares the long system/tool prefix).

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <atomic>

#include "llama.h"
#include "ggml-backend.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "CortexNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) LOGI(__VA_ARGS__)
#endif

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;  // tokens currently resident in KV cache (seq 0)
    int n_batch = 512;
};

std::atomic<bool> g_abort{false};
bool g_backend_ready = false;

void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGE("%s", text);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Prompt arrives as raw UTF-8 bytes (avoids JNI modified-UTF-8 issues with emoji).
std::string jbytes(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t)text.size(), nullptr, 0, false, true);
    std::vector<llama_token> toks(std::max(n, 0));
    if (n > 0) {
        llama_tokenize(vocab, text.data(), (int32_t)text.size(), toks.data(), n, false, true);
    }
    return toks;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        llama_token_to_piece(vocab, t, big.data(), -n, 0, false);
        return big;
    }
    return std::string(buf, static_cast<size_t>(n));
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 code point.
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char)s[i - 1];
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            size_t need = (c < 0x80) ? 1 : ((c >> 5) == 0x6) ? 2 : ((c >> 4) == 0xE) ? 3 : ((c >> 3) == 0x1E) ? 4 : 1;
            size_t have = n - (i - 1);
            return have >= need ? n : i - 1;
        }
        --i;
        ++back;
    }
    return n;
}

bool emit(JNIEnv *env, jobject cb, jmethodID mid, const std::string &bytes) {
    jbyteArray arr = env->NewByteArray((jsize)bytes.size());
    env->SetByteArrayRegion(arr, 0, (jsize)bytes.size(), reinterpret_cast<const jbyte *>(bytes.data()));
    jboolean keep = env->CallBooleanMethod(cb, mid, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return keep == JNI_TRUE;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_hermitech_cortexboard_llm_LlamaNative_nativeInit(JNIEnv *env, jobject, jstring libDir) {
    if (g_backend_ready) return;
    llama_log_set(log_cb, nullptr);
    std::string dir = jstr(env, libDir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    g_backend_ready = true;
    LOGI("backends loaded from %s", dir.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_hermitech_cortexboard_llm_LlamaNative_nativeLoad(JNIEnv *env, jobject, jstring path,
                                                          jint nCtx, jint nThreads) {
    std::string p = jstr(env, path);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(p.c_str(), mp);
    if (!model) {
        LOGE("failed to load model %s", p.c_str());
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_batch = (int)cp.n_batch;
    LOGI("model ready: ctx=%u threads=%d", llama_n_ctx(ctx), nThreads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_hermitech_cortexboard_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

JNIEXPORT jint JNICALL
Java_com_hermitech_cortexboard_llm_LlamaNative_nativeCountTokens(JNIEnv *env, jobject, jlong h,
                                                                 jbyteArray text) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s) return -1;
    return (jint)tokenize(s->vocab, jbytes(env, text)).size();
}

JNIEXPORT void JNICALL
Java_com_hermitech_cortexboard_llm_LlamaNative_nativeAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

/**
 * Generate a completion for a fully formatted prompt.
 * Returns number of generated tokens, or a negative error code:
 *  -1 bad handle, -2 prompt too long for context, -3 decode failure.
 * callback.onBytes(byte[]) -> Boolean (false = stop).
 */
JNIEXPORT jint JNICALL
Java_com_hermitech_cortexboard_llm_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jlong h,
                                                              jbyteArray promptBytes, jint maxTokens,
                                                              jfloat temp, jfloat topP, jint topK,
                                                              jfloat repeatPenalty, jint seed,
                                                              jobject callback) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s) return -1;
    g_abort.store(false);

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbCls, "onBytes", "([B)Z");

    std::vector<llama_token> prompt = tokenize(s->vocab, jbytes(env, promptBytes));
    const int n_ctx = (int)llama_n_ctx(s->ctx);
    if ((int)prompt.size() + 8 >= n_ctx) return -2;
    int budget = std::min((int)maxTokens, n_ctx - (int)prompt.size() - 1);

    // --- KV-cache prefix reuse -------------------------------------------------
    size_t common = 0;
    while (common < s->cached.size() && common < prompt.size() && s->cached[common] == prompt[common]) ++common;
    if (common == prompt.size() && common > 0) --common;  // must decode >=1 token to get logits
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    s->cached.resize(common);

    // --- prompt ingestion in n_batch chunks ------------------------------------
    for (size_t i = common; i < prompt.size(); i += (size_t)s->n_batch) {
        int n = (int)std::min((size_t)s->n_batch, prompt.size() - i);
        llama_batch b = llama_batch_get_one(prompt.data() + i, n);
        if (llama_decode(s->ctx, b) != 0) {
            llama_memory_clear(mem, true);
            s->cached.clear();
            return -3;
        }
        s->cached.insert(s->cached.end(), prompt.begin() + (long)i, prompt.begin() + (long)i + n);
        if (g_abort.load()) return 0;
    }

    // --- sampler chain ------------------------------------------------------------
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, repeatPenalty, 0.0f, 0.0f));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t)seed));
    }

    std::string pending;
    int generated = 0;
    llama_token tok;
    bool keep = true;
    while (generated < budget && keep && !g_abort.load()) {
        tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        pending += piece(s->vocab, tok);
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            keep = emit(env, callback, onBytes, pending.substr(0, ok));
            pending.erase(0, ok);
        }
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, b) != 0) {
            llama_sampler_free(smpl);
            llama_memory_clear(mem, true);
            s->cached.clear();
            return -3;
        }
        s->cached.push_back(tok);
        ++generated;
    }
    if (!pending.empty() && keep) emit(env, callback, onBytes, pending);
    llama_sampler_free(smpl);
    return generated;
}

}  // extern "C"
