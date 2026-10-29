package ir.avanevis.study.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.min

/** Optional on-device Whisper post-session review; never replaces Vosk live notes. */
internal class WhisperReviewEngine(private val context: Context) {
    private external fun nativeTranscribe(modelPath: String, samples: FloatArray): String

    companion object {
        private val available: Boolean by lazy {
            runCatching { System.loadLibrary("avanevis_whisper") }.isSuccess
        }
    }

    fun transcribeFile(pcm: File): List<String> {
        check(available) { "کتابخانهٔ بومی ویسپر نصب نشده است" }
        val model = installModel()
        val results = mutableListOf<String>()
        // Stream from disk: bounded memory even after a long study session.
        // 22 seconds + 2 seconds overlap to reduce words lost at segment edges.
        val windowSamples = 22 * 16000
        val overlapSamples = 2 * 16000
        val buffer = ByteArray(windowSamples * 2)
        RandomAccessFile(pcm, "r").use { input ->
            var startSample = 0L
            val totalSamples = input.length() / 2L
            while (startSample < totalSamples) {
                input.seek(startSample * 2)
                val count = min(buffer.size.toLong(), (totalSamples - startSample) * 2).toInt()
                input.readFully(buffer, 0, count)
                val floats = FloatArray(count / 2) { i ->
                    val lo = buffer[2 * i].toInt() and 255
                    val hi = buffer[2 * i + 1].toInt()
                    ((hi shl 8) or lo).toShort() / 32768.0f
                }
                val text = nativeTranscribe(model.absolutePath, floats).trim()
                if (text.isNotBlank()) results.add(text)
                if (startSample + count / 2 >= totalSamples) break
                startSample += windowSamples - overlapSamples
            }
        }
        return results
    }

    private fun installModel(): File {
        val target = File(context.filesDir, "ggml-tiny.bin")
        if (target.isFile && target.length() > 50_000_000) return target
        val temp = File(context.filesDir, "ggml-tiny.bin.tmp")
        try {
            context.assets.open("whisper/ggml-tiny.bin").use { source ->
                FileOutputStream(temp).use { out -> source.copyTo(out, 65536) }
            }
            check(temp.length() > 50_000_000) { "مدل Whisper ناقص است" }
            check(temp.renameTo(target)) { "ذخیره مدل Whisper ممکن نشد" }
        } finally { temp.delete() }
        return target
    }
}
