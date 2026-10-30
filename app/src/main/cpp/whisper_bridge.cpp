#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include "whisper.h"

// The tiny model is expensive to load on Android. Cache one context per process.
// A mutex serializes inference because whisper_context is not reentrant.
static std::mutex whisper_mutex;
static whisper_context *cached_context = nullptr;
static std::string cached_path;

extern "C" JNIEXPORT jstring JNICALL
Java_ir_avanevis_study_speech_WhisperReviewEngine_nativeTranscribe(
    JNIEnv *env, jobject, jstring modelPath, jfloatArray audio) {
    if (!modelPath || !audio) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(audio);
    if (n < 16000 || n > 16000 * 30) return env->NewStringUTF("");
    const char *raw = env->GetStringUTFChars(modelPath, nullptr);
    if (!raw) return env->NewStringUTF("");
    std::string path(raw);
    env->ReleaseStringUTFChars(modelPath, raw);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(audio, 0, n, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    std::lock_guard<std::mutex> guard(whisper_mutex);
    if (!cached_context || cached_path != path) {
        if (cached_context) { whisper_free(cached_context); cached_context = nullptr; }
        cached_path.clear();
        cached_context = whisper_init_from_file_with_params(path.c_str(), whisper_context_default_params());
        if (!cached_context) return env->NewStringUTF("");
        cached_path = path;
    }
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "fa";
    params.translate = false;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.n_threads = 4;
    std::string result;
    if (whisper_full(cached_context, params, samples.data(), static_cast<int>(n)) == 0) {
        for (int i = 0; i < whisper_full_n_segments(cached_context); ++i) {
            const char *part = whisper_full_get_segment_text(cached_context, i);
            if (part) result += part;
        }
    }
    return env->NewStringUTF(result.c_str());
}
