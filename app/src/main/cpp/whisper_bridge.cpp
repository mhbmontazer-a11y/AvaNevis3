#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <thread>
#include "whisper.h"

namespace {
std::mutex inference_mutex;
whisper_context *context = nullptr;
std::string loaded_path;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_avanevis_study_speech_WhisperReviewEngine_nativeTranscribe(
    JNIEnv *env, jobject, jstring modelPath, jfloatArray audio) {
    if (!modelPath || !audio) return env->NewStringUTF("");
    const jsize n = env->GetArrayLength(audio);
    if (n < 16000 || n > 16000 * 30) return env->NewStringUTF("");
    const char *raw = env->GetStringUTFChars(modelPath, nullptr);
    if (!raw) return nullptr;
    const std::string path(raw);
    env->ReleaseStringUTFChars(modelPath, raw);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(audio, 0, n, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    std::lock_guard<std::mutex> lock(inference_mutex);
    if (!context || loaded_path != path) {
        if (context) { whisper_free(context); context = nullptr; }
        loaded_path.clear();
        whisper_context_params cp = whisper_context_default_params();
        context = whisper_init_from_file_with_params(path.c_str(), cp);
        if (!context) {
            jclass cls = env->FindClass("java/lang/IllegalStateException");
            if (cls) env->ThrowNew(cls, "Whisper model could not be loaded");
            return nullptr;
        }
        loaded_path = path;
    }
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language = "fa";
    p.translate = false;
    p.no_context = true;
    p.single_segment = false;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    std::string result;
    const int rc = whisper_full(context, p, samples.data(), static_cast<int>(n));
    if (rc != 0) {
        jclass errorClass = env->FindClass("java/lang/IllegalStateException");
        if (errorClass) env->ThrowNew(errorClass, "whisper_full inference failed");
        return nullptr;
    }
    {
        for (int i = 0; i < whisper_full_n_segments(context); ++i) {
            const char *part = whisper_full_get_segment_text(context, i);
            if (part) { if (!result.empty()) result += " "; result += part; }
        }
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ir_avanevis_study_speech_WhisperReviewEngine_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    if (context) { whisper_free(context); context = nullptr; }
    loaded_path.clear();
}
