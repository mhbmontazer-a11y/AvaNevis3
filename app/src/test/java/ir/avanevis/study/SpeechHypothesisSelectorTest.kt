package ir.avanevis.study

import ir.avanevis.study.speech.SpeechHypothesisSelector
import org.junit.Assert.*
import org.junit.Test

class SpeechHypothesisSelectorTest {
    @Test fun picksFirstWhenConfidenceIsUnavailable() {
        val best = SpeechHypothesisSelector.select(listOf("  متن اول  ", "متن دوم"), null)
        assertEquals("متن اول", best.text)
        assertEquals(-1f, best.confidence)
    }

    @Test fun picksHighestValidConfidence() {
        val best = SpeechHypothesisSelector.select(
            listOf("متن اول", "متن دوم", "متن سوم"), floatArrayOf(0.40f, 0.92f, 0.70f))
        assertEquals("متن دوم", best.text)
        assertEquals(0.92f, best.confidence)
    }

    @Test fun skipsBlankAlternativesAndTheirScores() {
        val best = SpeechHypothesisSelector.select(listOf("  ", "معتبر"), floatArrayOf(0.99f, 0.42f))
        assertEquals("معتبر", best.text)
        assertEquals(0.42f, best.confidence)
    }

    @Test fun invalidScoresDoNotBecomeFakeConfidence() {
        val best = SpeechHypothesisSelector.select(listOf("اول", "دوم"), floatArrayOf(Float.NaN, -1f))
        assertEquals("اول", best.text)
        assertEquals(-1f, best.confidence)
    }

    @Test fun tiesRespectRecognizerOrdering() {
        val best = SpeechHypothesisSelector.select(listOf("اول", "دوم"), floatArrayOf(0.75f, 0.75f))
        assertEquals("اول", best.text)
    }

    @Test fun handlesMissingOrEmptyResults() {
        assertEquals("", SpeechHypothesisSelector.select(null, null).text)
        assertEquals("", SpeechHypothesisSelector.select(listOf(" "), floatArrayOf(0.9f)).text)
    }
}
