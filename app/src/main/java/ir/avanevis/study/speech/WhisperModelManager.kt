package ir.avanevis.study.speech

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Downloaded model is NEVER included in the APK. All speech inference is on-device. */
object WhisperModelManager {
    const val FILE_NAME = "ggml-large-v3-turbo-q5_0.bin"
    const val MODEL_ID = "large-v3-turbo-q5_0"
    const val SHA256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"
    const val DOWNLOAD_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin"
    // Exact size from upstream model metadata (547.4 MiB). Used only as sanity check.
    const val MIN_SIZE = 550_000_000L

    fun directory(context: Context): File = File(context.filesDir, "whisper-model").apply { mkdirs() }
    fun modelFile(context: Context): File = File(directory(context), FILE_NAME)
    fun partialFile(context: Context): File = File(directory(context), "$FILE_NAME.part")
    private fun verifiedMarker(context: Context): File = File(directory(context), "$FILE_NAME.sha256")
    fun isInstalled(context: Context): Boolean = modelFile(context).let { model ->
        model.isFile && model.length() >= MIN_SIZE &&
            verifiedMarker(context).takeIf { it.isFile }?.readText()?.trim() == "$SHA256:${model.length()}"
    }

    /** Re-hash at recording startup, not for every chunk. A corrupt model is never used. */
    suspend fun verifyInstalled(context: Context): Boolean = withContext(Dispatchers.IO) {
        val model = modelFile(context)
        if (!isInstalled(context)) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(SHA256, ignoreCase = true)) {
            verifiedMarker(context).delete()
            false
        } else true
    }
    fun downloadedBytes(context: Context): Long = partialFile(context).length()
    fun availableBytes(context: Context): Long = StatFs(directory(context).absolutePath).availableBytes

    fun delete(context: Context) {
        // Only invoke when no active inference session is running.
        modelFile(context).delete()
        partialFile(context).delete()
        verifiedMarker(context).delete()
    }

    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val partial = partialFile(context)
        val target = modelFile(context)
        if (isInstalled(context)) return@withContext
        val existing = partial.length()
        require(availableBytes(context) > (MIN_SIZE - existing).coerceAtLeast(0L) + 100_000_000L) {
            "فضای ذخیره‌سازی کافی برای مدل وجود ندارد"
        }
        val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
            conn.connect()
            val status = conn.responseCode
            require(status == 200 || status == 206) { "خطای دریافت مدل: HTTP $status" }
            val append = existing > 0L && status == 206 &&
                conn.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true
            // If the server ignores Range, restart safely instead of appending duplicate bytes.
            val start = if (append) existing else 0L
            val total = if (conn.contentLengthLong > 0) start + conn.contentLengthLong else -1L
            FileOutputStream(partial, append).use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var received = start
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(received, total)
                    }
                    output.fd.sync()
                }
            }
            require(partial.length() >= MIN_SIZE) { "دانلود مدل ناقص است" }
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().buffered().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(SHA256, ignoreCase = true)) {
                partial.delete()
                "اعتبارسنجی SHA-256 مدل ناموفق بود؛ فایل حذف شد"
            }
            require(partial.renameTo(target)) { "نصب اتمیک مدل انجام نشد" }
            val marker = File(directory(context), "$FILE_NAME.sha256.tmp")
            FileOutputStream(marker).use { out ->
                out.write("$SHA256:${target.length()}".toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            require(marker.renameTo(verifiedMarker(context))) { "ثبت اعتبار مدل انجام نشد" }
        } finally {
            conn.disconnect()
        }
    }
}
