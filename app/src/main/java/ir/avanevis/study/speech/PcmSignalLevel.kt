package ir.avanevis.study.speech

import kotlin.math.log10
import kotlin.math.sqrt

/** True dBFS relative to signed 16-bit PCM full scale; silence is -120 dBFS. */
internal object PcmSignalLevel {
    fun rmsDbFs(pcm: ByteArray, length: Int): Float {
        val count = length.coerceIn(0, pcm.size) / 2
        if (count == 0) return -120f
        var sum = 0.0
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xff
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt() / 32768.0
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        return if (rms <= 1e-6) -120f else (20.0 * log10(rms)).toFloat().coerceIn(-120f, 0f)
    }
}
