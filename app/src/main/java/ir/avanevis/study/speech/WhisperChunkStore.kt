package ir.avanevis.study.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Crash-recoverable PCM16LE spool. Files are immutable once renamed to .pcm. */
internal class WhisperChunkStore(context: Context) {
    val dir: File = File(context.filesDir, "whisper-pending").apply { check(isDirectory || mkdirs()) }

    /** Recover a partially written capture; never discard it without inspecting its contents. */
    fun recover(): List<File> {
        dir.listFiles()?.filter { it.name.endsWith(".recording") }?.forEach { part ->
            if (part.length() >= 32000L) {
                val recovered = File(dir, part.name.removeSuffix(".recording") + ".pcm")
                if (!part.renameTo(recovered)) throw IllegalStateException("بازیابی فایل صوتی ناموفق بود: ${part.name}")
            } else {
                // Keep short captures as well: they may contain the end of a word.
                if (part.length() > 0) {
                    val recovered = File(dir, part.name.removeSuffix(".recording") + ".pcm")
                    if (!part.renameTo(recovered)) throw IllegalStateException("بازیابی فایل صوتی ناموفق بود")
                } else part.delete()
            }
        }
        return dir.listFiles()?.filter { it.name.endsWith(".pcm") }?.sortedWith(compareBy<File> { file ->
            // Sort by embedded capture timestamp across different sessions.
            Regex("_n[0-9]+_([0-9]{13})-").find(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
        }.thenBy { it.name }) ?: emptyList()
    }

    fun openCapture(sessionId: Long, notebookId: Long, hasOverlap: Boolean = false): Capture {
        val id = "s${sessionId}_n${notebookId}_%013d-%s".format(System.currentTimeMillis(), UUID.randomUUID().toString()) + if (hasOverlap) "_ov1_" else "_ov0_"
        return Capture(File(dir, "$id.recording"))
    }

    class Capture(private val staging: File) {
        private val out = FileOutputStream(staging)
        private var closed = false
        var sizeBytes: Long = 0; private set
        fun append(data: ByteArray, length: Int) {
            check(!closed)
            require(length >= 0 && length % 2 == 0)
            out.write(data, 0, length)
            sizeBytes += length
        }
        fun sync() { if (!closed) out.fd.sync() }
        fun finish(): File? {
            if (!closed) { out.fd.sync(); out.close(); closed = true }
            if (sizeBytes == 0L) { staging.delete(); return null }
            val ready = File(staging.parentFile, staging.name.removeSuffix(".recording") + ".pcm")
            check(staging.renameTo(ready)) { "تثبیت قطعهٔ صوتی ناموفق بود" }
            return ready
        }
        fun abortWithoutDeleting() { if (!closed) { runCatching { out.fd.sync() }; out.close(); closed = true } }
    }
}
