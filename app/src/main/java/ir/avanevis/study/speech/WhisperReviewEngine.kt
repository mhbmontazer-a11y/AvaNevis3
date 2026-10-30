package ir.avanevis.study.speech

import android.content.Context
import java.io.File

/** Sole ASR backend. Each PCM chunk is 16kHz mono signed 16-bit little-endian. */
internal class WhisperReviewEngine(private val context: Context) {
    private external fun nativeTranscribe(modelPath: String, samples: FloatArray): String
    private external fun nativeRelease()
    fun release() { if (available) nativeRelease() }
    companion object {
        private val available: Boolean by lazy { runCatching { System.loadLibrary("avanevis_whisper") }.isSuccess }
    }
    fun transcribeFile(file: File): String {
        require(file.isFile && file.length() <= 30L * 16000 * 2) { "فایل صوتی نامعتبر یا بیش از ۳۰ ثانیه" }
        return transcribePcm(file.readBytes())
    }
    fun transcribePcm(pcm: ByteArray): String {
        check(available) { "کتابخانهٔ بومی Whisper در APK موجود نیست" }
        check(WhisperModelManager.isInstalled(context)) { "مدل Whisper Large-v3 Turbo نصب نشده" }
        require(pcm.size % 2 == 0 && pcm.size <= 30 * 16000 * 2)
        // Whisper requires >=1 second. Pad short tails instead of dropping them.
        val floats = FloatArray(maxOf(16000, pcm.size / 2)) { i ->
            if (i * 2 + 1 >= pcm.size) 0f else {
                val lo = pcm[i * 2].toInt() and 0xff
                val hi = pcm[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768f
            }
        }
        // Native initialization/inference errors must never be treated as silence.
        return nativeTranscribe(WhisperModelManager.modelFile(context).absolutePath, floats).trim()
    }
}
