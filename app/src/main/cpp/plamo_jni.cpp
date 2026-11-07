#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define LOG_TAG "PlamoNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex g_mutex;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
bool g_backend_initialized = false;

void releaseLocked() {
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

std::string fromJava(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_charles_livecaptionn_translation_PlamoNative_nativeLoadModel(
        JNIEnv *env, jclass, jstring modelPath, jint requestedThreads) {
    const std::string path = fromJava(env, modelPath);
    if (path.empty()) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    releaseLocked();

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // portable CPU baseline for ARM64

    g_model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!g_model) {
        LOGE("Failed to load GGUF model");
        return JNI_FALSE;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = 4096;
    contextParams.n_batch = 512;
    const int hardwareThreads = static_cast<int>(std::thread::hardware_concurrency());
    const int safeDefault = std::max(2, std::min(4, hardwareThreads > 0 ? hardwareThreads : 4));
    const int threads = requestedThreads > 0
            ? std::max(1, std::min(8, static_cast<int>(requestedThreads)))
            : safeDefault;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;

    g_context = llama_init_from_model(g_model, contextParams);
    if (!g_context) {
        LOGE("Failed to create llama context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_charles_livecaptionn_translation_PlamoNative_nativeTranslate(
        JNIEnv *env, jclass, jstring promptValue, jint maxTokensValue) {
    const std::string prompt = fromJava(env, promptValue);
    if (prompt.empty()) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context) return env->NewStringUTF("");

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const int32_t needed = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            nullptr, 0, true, true);
    if (needed >= 0) return env->NewStringUTF("");
    std::vector<llama_token> tokens(static_cast<size_t>(-needed));
    const int32_t tokenCount = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (tokenCount <= 0 || tokenCount > static_cast<int32_t>(tokens.size())) {
        return env->NewStringUTF("");
    }
    tokens.resize(static_cast<size_t>(tokenCount));

    llama_memory_clear(llama_get_memory(g_context), true);
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    if (!batch.token) return env->NewStringUTF("");

    for (int32_t i = 0; i < tokenCount; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == tokenCount - 1) ? 1 : 0;
    }
    batch.n_tokens = tokenCount;

    if (llama_decode(g_context, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    const int maxTokens = std::max(1, std::min(1024, static_cast<int>(maxTokensValue)));
    std::string output;
    output.reserve(2048);

    for (int generated = 0; generated < maxTokens; ++generated) {
        const llama_token next = llama_sampler_sample(sampler, g_context, -1);
        llama_sampler_accept(sampler, next);
        if (llama_vocab_is_eog(vocab, next)) break;

        char piece[512];
        int32_t pieceLength = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        std::string pieceText;
        if (pieceLength < 0) {
            std::vector<char> largePiece(static_cast<size_t>(-pieceLength));
            pieceLength = llama_token_to_piece(vocab, next, largePiece.data(),
                                               static_cast<int32_t>(largePiece.size()), 0, true);
            if (pieceLength > 0) pieceText.assign(largePiece.data(), static_cast<size_t>(pieceLength));
        } else if (pieceLength > 0) {
            pieceText.assign(piece, static_cast<size_t>(pieceLength));
        }
        // PFML3 translation ends when the model emits the next element tag.
        if (pieceText == "<|plamo:tag|>" || pieceText == "<|plamo:bos|>") break;
        output.append(pieceText);

        batch.n_tokens = 1;
        batch.token[0] = next;
        batch.pos[0] = tokenCount + generated;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(g_context, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    // Remove accidental leading/trailing whitespace and control markers.
    const auto first = output.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) output.clear();
    else {
        const auto last = output.find_last_not_of(" \t\r\n");
        output = output.substr(first, last - first + 1);
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_charles_livecaptionn_translation_PlamoNative_nativeRelease(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseLocked();
}
