/**
 * mnn_llm_jni.cpp - MNN-LLM JNI 桥接层 (v11.1)
 *
 * 将 MNN-LLM C++ API 包装为 JNI 方法，供 Kotlin MnnLlmEngine 类调用。
 *
 * 依赖：MNN 原生库（由 CMake 从 MNN 源码编译）
 * 编译：通过 app/src/main/cpp/CMakeLists.txt 构建
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <mutex>

// MNN-LLM 头文件
// 编译时需要 MNN 源码的 include 路径
#ifdef MNN_LLM_AVAILABLE
#include "llm/llm.hpp"
#endif

#define TAG "MnnLlmJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 全局会话结构
struct LlmSession {
#ifdef MNN_LLM_AVAILABLE
    std::unique_ptr<MNN::Transformer::Llm> model;
#endif
    std::string modelPath;
    int backend;
    int threads;
    bool generating;
    std::mutex mutex;
};

// 会话存储（简单实现，生产环境需要更复杂的生命周期管理）
static std::unordered_map<jlong, std::unique_ptr<LlmSession>> g_sessions;
static jlong g_nextId = 1;

extern "C" {

/**
 * nativeInit: 初始化 MNN-LLM 引擎
 *
 * @param modelPath 模型目录路径
 * @param backend 后端类型 (0=CPU, 1=GPU/OpenCL)
 * @param threads CPU 线程数
 * @return 会话指针 (0 = 失败)
 */
JNIEXPORT jlong JNICALL
Java_com_hud_navi_MnnLlmEngine_nativeInit(
    JNIEnv *env, jobject thiz,
    jstring modelPath, jint backend, jint threads) {

#ifdef MNN_LLM_AVAILABLE
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Initializing MNN-LLM: path=%s, backend=%d, threads=%d", path, backend, threads);

    auto session = std::make_unique<LlmSession>();
    session->modelPath = path;
    session->backend = backend;
    session->threads = threads;
    session->generating = false;

    // 创建 MNN-LLM 模型实例
    // 具体 API 取决于 MNN 版本，以下为典型用法
    MNN::BackendType backendType = (backend == 1) ?
        MNN::BackendType::MNN_BACKEND_OPENCL :
        MNN::BackendType::MNN_BACKEND_CPU;

    session->model = MNN::Transformer::Llm::createLLM(path);
    if (!session->model) {
        LOGE("Failed to create LLM model");
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }

    // 配置推理参数
    session->model->setThreadNum(threads);

    env->ReleaseStringUTFChars(modelPath, path);

    jlong id = g_nextId++;
    g_sessions[id] = std::move(session);

    LOGI("MNN-LLM initialized, session id=%lld", (long long)id);
    return id;
#else
    LOGW("MNN-LLM not compiled in this build");
    return 0;
#endif
}

/**
 * nativeGenerate: 同步生成回复
 */
JNIEXPORT jstring JNICALL
Java_com_hud_navi_MnnLlmEngine_nativeGenerate(
    JNIEnv *env, jobject thiz,
    jlong ptr, jstring prompt, jint maxTokens, jfloat temperature) {

#ifdef MNN_LLM_AVAILABLE
    auto it = g_sessions.find(ptr);
    if (it == g_sessions.end() || !it->second->model) {
        LOGE("Invalid session or model not loaded");
        return nullptr;
    }

    auto &session = it->second;
    std::lock_guard<std::mutex> lock(session->mutex);

    if (session->generating) {
        LOGW("Already generating");
        return nullptr;
    }

    session->generating = true;

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    LOGI("Generating: prompt length=%d, maxTokens=%d", (int)promptCpp.length(), maxTokens);

    // 生成回复
    std::string result;
    auto generator = session->model->createSession({
        /* .temperature = */ temperature,
        /* .topP = */ 0.9f,
        /* .topK = */ 40,
        /* .seed = */ -1,
        /* .maxNewTokens = */ maxTokens
    });

    session->model->embedding(promptCpp);
    while (!session->model->isStop()) {
        session->model->generate();
        if (!session->model->isStop()) {
            result += session->model->dumpCurrentToken();
        }
    }

    session->generating = false;

    LOGI("Generated %d chars", (int)result.length());
    return env->NewStringUTF(result.c_str());
#else
    return nullptr;
#endif
}

/**
 * nativeGenerateStream: 流式生成回复
 */
JNIEXPORT jstring JNICALL
Java_com_hud_navi_MnnLlmEngine_nativeGenerateStream(
    JNIEnv *env, jobject thiz,
    jlong ptr, jstring prompt, jint maxTokens, jfloat temperature,
    jobject callback) {

#ifdef MNN_LLM_AVAILABLE
    auto it = g_sessions.find(ptr);
    if (it == g_sessions.end() || !it->second->model) {
        return nullptr;
    }

    auto &session = it->second;
    std::lock_guard<std::mutex> lock(session->mutex);

    if (session->generating) return nullptr;
    session->generating = true;

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // 获取回调方法
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");

    std::string fullResult;

    auto generator = session->model->createSession({
        temperature, 0.9f, 40, -1, maxTokens
    });

    session->model->embedding(promptCpp);
    while (!session->model->isStop()) {
        session->model->generate();
        if (!session->model->isStop()) {
            std::string token = session->model->dumpCurrentToken();
            fullResult += token;

            // 回调到 Kotlin
            jstring jToken = env->NewStringUTF(token.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jToken);
            env->DeleteLocalRef(jToken);
        }
    }

    session->generating = false;
    return env->NewStringUTF(fullResult.c_str());
#else
    return nullptr;
#endif
}

/**
 * nativeStopGeneration: 停止生成
 */
JNIEXPORT void JNICALL
Java_com_hud_navi_MnnLlmEngine_nativeStopGeneration(
    JNIEnv *env, jobject thiz, jlong ptr) {

#ifdef MNN_LLM_AVAILABLE
    auto it = g_sessions.find(ptr);
    if (it != g_sessions.end()) {
        it->second->generating = false;
        LOGI("Generation stop requested");
    }
#endif
}

/**
 * nativeRelease: 释放引擎
 */
JNIEXPORT void JNICALL
Java_com_hud_navi_MnnLlmEngine_nativeRelease(
    JNIEnv *env, jobject thiz, jlong ptr) {

#ifdef MNN_LLM_AVAILABLE
    auto it = g_sessions.find(ptr);
    if (it != g_sessions.end()) {
        it->second->model.reset();
        g_sessions.erase(it);
        LOGI("Session released: %lld", (long long)ptr);
    }
#endif
}

} // extern "C"
