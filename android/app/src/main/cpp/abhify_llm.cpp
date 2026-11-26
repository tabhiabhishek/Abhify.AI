// JNI bridge between the Abhify Android app and llama.cpp (v0.7.0), using only the C API in llama.h.
// One model and one context at a time; every call comes from LlmEngine's single worker thread.
// Text crosses the boundary as UTF-8 byte arrays (JNI's NewStringUTF can't carry emoji correctly).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "AbhifyLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::atomic<bool> g_cancel{false};
static bool g_backend_ready = false;

static void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static jstring jstr(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }   // ASCII-only callers

static std::string json_escape(const std::string &s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if ((unsigned char) c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += c;
    }
    return o;
}

/** Length of the longest prefix of s that ends on a complete UTF-8 character. */
static size_t utf8_complete_prefix(const std::string &s) {
    size_t i = s.size();
    // walk back at most 3 bytes to find the lead byte of the last character
    for (size_t back = 1; back <= 4 && back <= s.size(); back++) {
        unsigned char c = (unsigned char) s[s.size() - back];
        if ((c & 0xC0) == 0x80) continue;                         // continuation byte
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return back >= need ? i : s.size() - back;
    }
    return i;
}

static void unload_all() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeInit(JNIEnv *env, jobject, jstring jlibdir) {
    if (!g_backend_ready) {
        llama_log_set(log_cb, nullptr);
        const char *dir = env->GetStringUTFChars(jlibdir, nullptr);
        ggml_backend_load_all_from_path(dir);                  // picks the best CPU variant (+ Vulkan if packaged)
        env->ReleaseStringUTFChars(jlibdir, dir);
        llama_backend_init();
        g_backend_ready = true;
    }
    // {"system":"...","devices":[{"name":..,"type":..,"description":..}]}
    std::string out = "{\"system\":\"" + json_escape(llama_print_system_info()) + "\",\"devices\":[";
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        const char *type = ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU ? "cpu"
                         : ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU ? "gpu"
                         : ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_IGPU ? "igpu" : "other";
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(d, &free, &total);
        char b[64]; snprintf(b, sizeof b, ",\"total_mb\":%zu}", total / (1024 * 1024));
        out += std::string(i ? "," : "") + "{\"name\":\"" + json_escape(ggml_backend_dev_name(d)) + "\",\"type\":\"" + type +
               "\",\"description\":\"" + json_escape(ggml_backend_dev_description(d)) + "\"" + b;
    }
    out += "]}";
    return jstr(env, out);
}

/** Returns null on success, or an error message. gpuLayers == 0 means strictly CPU (no device offload at all). */
extern "C" JNIEXPORT jstring JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint gpu_layers) {
    unload_all();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    static ggml_backend_dev_t cpu_only[1] = {nullptr};     // empty device list: weights stay in system memory
    if (gpu_layers == 0) { mp.n_gpu_layers = 0; mp.devices = cpu_only; }
    else mp.n_gpu_layers = gpu_layers;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) return jstr(env, "The model file could not be loaded (corrupted, incompatible, or not enough memory).");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = false;
    if (gpu_layers == 0) { cp.offload_kqv = false; cp.op_offload = false; }
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { unload_all(); return jstr(env, "Not enough memory to start the model with this context size."); }
    LOGI("loaded: ctx=%d threads=%d gpu_layers=%d", n_ctx, n_threads, gpu_layers);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeUnload(JNIEnv *, jobject) { unload_all(); }

extern "C" JNIEXPORT void JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeCancel(JNIEnv *, jobject) { g_cancel = true; }

extern "C" JNIEXPORT jstring JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeModelDesc(JNIEnv *env, jobject) {
    if (!g_model) return nullptr;
    char b[256]; llama_model_desc(g_model, b, sizeof b);
    return jstr(env, b);
}

// ---------------------------------------------------------------------------------------------------------------------
// Embeddings (semantic search): a second, small model (e.g. bge-small, BERT) with its own context, separate from the chat
// model above, so loading or freeing it never touches the chat model. Same single worker thread as everything else.
static llama_model *g_emb_model = nullptr;
static llama_context *g_emb_ctx = nullptr;

static void emb_unload() {
    if (g_emb_ctx) { llama_free(g_emb_ctx); g_emb_ctx = nullptr; }
    if (g_emb_model) { llama_model_free(g_emb_model); g_emb_model = nullptr; }
}

/** Loads the embedding model on the CPU (it is small). Returns null on success, or an error message. */
extern "C" JNIEXPORT jstring JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeEmbedLoad(JNIEnv *env, jobject, jstring jpath, jint n_threads) {
    emb_unload();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    static ggml_backend_dev_t cpu_only[1] = {nullptr};
    mp.n_gpu_layers = 0; mp.devices = cpu_only;
    g_emb_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_emb_model) return jstr(env, "The embedding model could not be loaded (corrupted, incompatible, or not enough memory).");
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 512; cp.n_batch = 512; cp.n_ubatch = 512;           // an encoder sees the whole text at once
    cp.n_seq_max = 1;
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    cp.embeddings = true;                                           // pooling: the model's own (CLS for bge)
    cp.offload_kqv = false; cp.op_offload = false;
    g_emb_ctx = llama_init_from_model(g_emb_model, cp);
    if (!g_emb_ctx) { emb_unload(); return jstr(env, "Not enough memory to start the embedding model."); }
    LOGI("embedding model loaded: dims=%d pooling=%d", llama_model_n_embd(g_emb_model), (int) llama_pooling_type(g_emb_ctx));
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeEmbedUnload(JNIEnv *, jobject) { emb_unload(); }

/** The embedding of one UTF-8 text (truncated to the context), or null on failure. Not normalized (Kotlin does that). */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeEmbed(JNIEnv *env, jobject, jbyteArray jtext) {
    if (!g_emb_model || !g_emb_ctx) return nullptr;
    jsize len = env->GetArrayLength(jtext);
    std::string text(len, '\0'); env->GetByteArrayRegion(jtext, 0, len, (jbyte *) text.data());
    const llama_vocab *vocab = llama_model_get_vocab(g_emb_model);
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, false);
    std::vector<llama_token> tok(std::max(n, 1));
    n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tok.data(), (int32_t) tok.size(), true, false);
    if (n <= 0) return nullptr;
    const int max_tok = (int) llama_n_ctx(g_emb_ctx);
    if (n > max_tok) { tok[max_tok - 1] = tok[n - 1]; n = max_tok; }  // keep the closing [SEP]
    llama_memory_clear(llama_get_memory(g_emb_ctx), true);
    int rc = llama_model_has_encoder(g_emb_model) && !llama_model_has_decoder(g_emb_model)
        ? llama_encode(g_emb_ctx, llama_batch_get_one(tok.data(), n)) : llama_decode(g_emb_ctx, llama_batch_get_one(tok.data(), n));
    if (rc != 0) return nullptr;
    const float *e = llama_get_embeddings_seq(g_emb_ctx, 0);
    if (!e) e = llama_get_embeddings(g_emb_ctx);
    if (!e) return nullptr;
    const int dims = llama_model_n_embd(g_emb_model);
    jfloatArray out = env->NewFloatArray(dims);
    env->SetFloatArrayRegion(out, 0, dims, e);
    return out;
}

/** Formats messages with the model's own chat template; falls back to ChatML (what Qwen uses). */
static std::string format_chat(const std::vector<std::string> &roles, const std::vector<std::string> &contents) {
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); i++) msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    size_t total = 0;
    for (auto &c : contents) total += c.size();
    std::vector<char> buf(total * 2 + 1024);
    int n = tmpl ? llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size()) : -1;
    if (n > (int) buf.size()) { buf.resize(n + 1); n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size()); }
    if (n >= 0) return std::string(buf.data(), n);
    std::string s;
    for (size_t i = 0; i < roles.size(); i++) s += "<|im_start|>" + roles[i] + "\n" + contents[i] + "<|im_end|>\n";
    return s + "<|im_start|>assistant\n";
}

/**
 * Generates a reply. callback: an object with `boolean onBytes(byte[])` (return false to stop).
 * Returns a JSON string with timings, or {"error": "..."}.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_ai_abhify_android_llm_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jobjectArray jroles, jobjectArray jcontents,
                                                      jint max_tokens, jfloat temp, jint seed, jobject callback) {
    if (!g_model || !g_ctx) return jstr(env, "{\"error\":\"No model is loaded.\"}");
    g_cancel = false;
    using clk = std::chrono::steady_clock;
    const auto t0 = clk::now();

    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < env->GetArrayLength(jroles); i++) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jbyteArray) env->GetObjectArrayElement(jcontents, i);
        const char *rs = env->GetStringUTFChars(r, nullptr); roles.emplace_back(rs); env->ReleaseStringUTFChars(r, rs);
        jsize n = env->GetArrayLength(c);
        std::string s(n, '\0'); env->GetByteArrayRegion(c, 0, n, (jbyte *) s.data()); contents.push_back(std::move(s));
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    const std::string prompt = format_chat(roles, contents);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    int n_tok = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_tok);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_tok, true, true) < 0)
        return jstr(env, "{\"error\":\"Could not tokenize the prompt.\"}");
    const int n_ctx = (int) llama_n_ctx(g_ctx);
    if (n_tok + 8 >= n_ctx) return jstr(env, "{\"error\":\"The conversation is too long for this model's context. Start a new chat.\"}");
    if (max_tokens > n_ctx - n_tok - 4) max_tokens = n_ctx - n_tok - 4;

    llama_memory_clear(llama_get_memory(g_ctx), true);
    for (int i = 0; i < n_tok; i += 512) {
        if (g_cancel) return jstr(env, "{\"error\":\"cancelled\",\"stopped\":\"cancelled\"}");
        int n = std::min(512, n_tok - i);
        if (llama_decode(g_ctx, llama_batch_get_one(tokens.data() + i, n)) != 0)
            return jstr(env, "{\"error\":\"The model failed while reading the prompt (out of memory?).\"}");
    }
    const auto t_prompt = clk::now();

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));
    }

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbClass, "onBytes", "([B)Z");
    std::string pending;
    int n_gen = 0;
    double first_ms = -1;
    const char *stopped = "length";
    char piece[256];
    for (; n_gen < max_tokens; n_gen++) {
        if (g_cancel) { stopped = "cancelled"; break; }
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (first_ms < 0) first_ms = std::chrono::duration<double, std::milli>(clk::now() - t0).count();
        if (llama_vocab_is_eog(vocab, tok)) { stopped = "end"; break; }
        int n = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, false);
        if (n > 0) pending.append(piece, n);
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, (const jbyte *) pending.data());
            jboolean go = env->CallBooleanMethod(callback, onBytes, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            // 7.1.0: a Java exception in the callback must not stay pending (the next JNI call would abort the app)
            if (env->ExceptionCheck()) { env->ExceptionClear(); stopped = "cancelled"; n_gen++; break; }
            if (!go) { stopped = "cancelled"; n_gen++; break; }
        }
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1)) != 0) { stopped = "error"; break; }
    }
    llama_sampler_free(smpl);
    const auto t_end = clk::now();
    const double prompt_ms = std::chrono::duration<double, std::milli>(t_prompt - t0).count();
    const double gen_ms = std::chrono::duration<double, std::milli>(t_end - t_prompt).count();
    char out[512];
    snprintf(out, sizeof out,
             "{\"prompt_tokens\":%d,\"generated_tokens\":%d,\"prompt_ms\":%.1f,\"first_token_ms\":%.1f,\"generation_ms\":%.1f,"
             "\"tokens_per_second\":%.2f,\"stopped\":\"%s\"}",
             n_tok, n_gen, prompt_ms, first_ms, gen_ms, gen_ms > 0 ? n_gen * 1000.0 / gen_ms : 0.0, stopped);
    return jstr(env, out);
}
