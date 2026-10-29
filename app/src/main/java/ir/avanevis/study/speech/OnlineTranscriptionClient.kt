package ir.avanevis.study.speech

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** HTTPS only. No provider API key in the client application. */
internal class OnlineTranscriptionClient {
    fun transcribe(baseUrl: String, accessToken: String, pcm: ByteArray): String {
        val url = URL("${baseUrl.trimEnd('/')}/transcribe")
        require(url.protocol == "https") { "ارتباط با سرور باید HTTPS باشد" }
        val connection = (url.openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 90000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "audio/wav")
        connection.setRequestProperty("X-AvaNevis-Token", accessToken)
        try {
            val wav = wavBytes(pcm)
            connection.setFixedLengthStreamingMode(wav.size)
            connection.outputStream.use { it.write(wav) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) } ?: ""
                throw IllegalStateException("خطای سرور ($status): $detail")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("text", "").trim()
        } finally { connection.disconnect() }
    }

    private fun wavBytes(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun ascii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        fun le16(value: Int) { out.write(value and 255); out.write((value ushr 8) and 255) }
        fun le32(value: Int) { repeat(4) { out.write((value ushr (it * 8)) and 255) } }
        ascii("RIFF"); le32(pcm.size + 36); ascii("WAVEfmt "); le32(16)
        le16(1); le16(1); le32(16000); le32(32000); le16(2); le16(16)
        ascii("data"); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
