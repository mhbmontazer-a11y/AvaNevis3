package ir.avanevis.study

import ir.avanevis.study.speech.PcmSignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSignalLevelTest {
    @Test fun silenceIsMinus120Dbfs() {
        assertEquals(-120f, PcmSignalLevel.rmsDbFs(ByteArray(4096), 4096))
    }
    @Test fun maximumPcmAmplitudeIsApproximatelyZeroDbfs() {
        val bytes = byteArrayOf(0xff.toByte(), 0x7f, 0xff.toByte(), 0x7f)
        assertTrue(PcmSignalLevel.rmsDbFs(bytes, bytes.size) > -0.1f)
    }
    @Test fun emptyInputIsSafe() {
        assertEquals(-120f, PcmSignalLevel.rmsDbFs(byteArrayOf(), 0))
    }
    @Test fun quieterAudioHasLowerDbfs() {
        val quiet = byteArrayOf(0, 0x10, 0, 0x10)
        val loud = byteArrayOf(0, 0x40, 0, 0x40)
        assertTrue(PcmSignalLevel.rmsDbFs(loud, 4) > PcmSignalLevel.rmsDbFs(quiet, 4))
    }
}
