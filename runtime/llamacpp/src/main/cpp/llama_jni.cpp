#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct Handle {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
};

std::string jstring_text(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int32_t needed = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, false);
    if (needed <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int32_t count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), needed, true, false);
    if (count < 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_androml_runtime_llamacpp_LlamaCppNative_nativeOpen(JNIEnv * env, jclass, jstring path, jint context_tokens, jint threads) {
    llama_backend_init();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.use_mmap = true;
    auto * model = llama_model_load_from_file(jstring_text(env, path).c_str(), params);
    if (!model) return 0;
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(std::clamp(context_tokens, 128, 32768));
    ctx_params.n_batch = std::min<uint32_t>(ctx_params.n_ctx, 2048);
    ctx_params.n_threads = std::clamp(threads, 1, 64);
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.embeddings = false;
    auto * context = llama_init_from_model(model, ctx_params);
    if (!context) {
        llama_model_free(model);
        return 0;
    }
    auto * handle = new Handle{model, context};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_androml_runtime_llamacpp_LlamaCppNative_nativeGenerate(JNIEnv * env, jobject, jlong raw, jstring prompt, jint max_tokens, jdouble temperature) {
    auto * handle = reinterpret_cast<Handle *>(raw);
    if (!handle || !handle->model || !handle->context) return env->NewStringUTF("");
    handle->cancelled.store(false);
    const auto * vocab = llama_model_get_vocab(handle->model);
    auto tokens = tokenize(vocab, jstring_text(env, prompt));
    if (tokens.empty() || llama_decode(handle->context, llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()))) != 0) return env->NewStringUTF("");

    auto chain_params = llama_sampler_chain_default_params();
    auto * sampler = llama_sampler_chain_init(chain_params);
    if (temperature <= 0.0) llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(static_cast<float>(temperature)));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    std::string output;
    const int32_t limit = std::clamp<int32_t>(max_tokens, 1, 8192);
    for (int32_t i = 0; i < limit && !handle->cancelled.load(); ++i) {
        const llama_token token = llama_sampler_sample(sampler, handle->context, -1);
        llama_sampler_accept(sampler, token);
        if (token == llama_vocab_eos(vocab)) break;
        char piece[4096];
        const int32_t size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (size > 0) output.append(piece, static_cast<size_t>(size));
        auto next = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(handle->context, next) != 0) break;
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androml_runtime_llamacpp_LlamaCppNative_nativeCancel(JNIEnv *, jobject, jlong raw) {
    if (auto * handle = reinterpret_cast<Handle *>(raw)) handle->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androml_runtime_llamacpp_LlamaCppNative_nativeClose(JNIEnv *, jobject, jlong raw) {
    auto * handle = reinterpret_cast<Handle *>(raw);
    if (!handle) return;
    if (handle->context) llama_free(handle->context);
    if (handle->model) llama_model_free(handle->model);
    delete handle;
}
