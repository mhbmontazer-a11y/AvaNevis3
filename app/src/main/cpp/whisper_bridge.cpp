#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" JNIEXPORT jstring JNICALL
Java_ir_avanevis_study_speech_WhisperReviewEngine_nativeTranscribe(
    JNIEnv *env, jobject, jstring modelPath, jfloatArray audio) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params ctx_params = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, ctx_params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(audio);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(audio, 0, n, samples.data());
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "fa";
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.n_threads = 4;
    std::string result;
    if (whisper_full(ctx, params, samples.data(), static_cast<int>(n)) == 0) {
        const int count = whisper_full_n_segments(ctx);
        for (int i = 0; i < count; ++i) {
            const char *part = whisper_full_get_segment_text(ctx, i);
            if (part) result += part;
        }
    }
    whisper_free(ctx);
    return env->NewStringUTF(result.c_str());
}
