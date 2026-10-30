package ir.avanevis.study

import ir.avanevis.study.speech.WhisperMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperMetricsTest {
    @Test fun exact() { assertEquals(0.0, WhisperMetrics.wer("تداعی آزاد", "تداعی آزاد"), 0.0) }
    @Test fun replacement() { assertEquals(0.5, WhisperMetrics.wer("تداعی آزاد", "تداعی ذهن"), 0.0) }
    @Test fun deletion() { assertEquals(0.25, WhisperMetrics.cer("سلام", "سام"), 0.0) }
}
