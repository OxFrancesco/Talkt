#include <jni.h>
#include <android/log.h>
#include <parakeet/engine.h>
#include <parakeet/log.h>
#include <chrono>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {
std::mutex engine_mutex;
std::unique_ptr<parakeet::Engine> engine;
std::string engine_key;

std::string utf8(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Could not read native argument");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring java_string(JNIEnv *env, const std::string &value) {
    auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    auto cls = env->FindClass("java/lang/String");
    auto ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(cls, ctor, bytes, encoding));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(encoding);
    return result;
}

void log_line(enum ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "Talkt", text);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_buddytools_talkt_transcription_ParakeetNative_hexagonAvailable(JNIEnv *, jobject) {
    return TALKT_HEXAGON;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_buddytools_talkt_transcription_ParakeetNative_transcribe(
    JNIEnv *env, jobject, jstring model, jstring backend, jstring libraries, jstring cache, jstring wav) {
    try {
        std::lock_guard<std::mutex> lock(engine_mutex);
        const auto model_path = utf8(env, model);
        const auto backend_name = utf8(env, backend);
        const auto library_path = utf8(env, libraries);
        const auto key = model_path + ":" + backend_name;
        double load_ms = 0;
        if (!engine || engine_key != key) {
            engine.reset();
            engine_key.clear();
            parakeet_log_set(log_line, nullptr);
            setenv("DSP_LIBRARY_PATH", (library_path + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp;/dsp").c_str(), 0);
            parakeet::EngineOptions options;
            options.model_gguf_path = model_path;
            options.backend = backend_name;
            options.n_gpu_layers = backend_name == "cpu" ? 0 : 99;
            options.n_threads = 4;
            options.backends_dir = library_path;
            options.opencl_cache_dir = utf8(env, cache);
            options.long_form_window_frames = 750;
            const auto start = std::chrono::steady_clock::now();
            engine = std::make_unique<parakeet::Engine>(options);
            engine_key = key;
            load_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        }
        const auto result = engine->transcribe(utf8(env, wav));
        const auto cls = env->FindClass("org/buddytools/talkt/transcription/ParakeetNativeResult");
        const auto ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;IDDDD)V");
        return env->NewObject(cls, ctor, java_string(env, result.text), java_string(env, engine->backend_name()),
            static_cast<jint>(engine->backend_device()), load_ms, result.preprocess_ms, result.encoder_ms, result.decode_ms);
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
