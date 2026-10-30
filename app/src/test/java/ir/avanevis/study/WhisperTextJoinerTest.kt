package ir.avanevis.study

import ir.avanevis.study.speech.WhisperTextJoiner
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTextJoinerTest {
    @Test fun removesOnlyMatchingOverlap() {
        assertEquals("در کتاب", WhisperTextJoiner.removeRepeatedPrefix("من درباره روان شناسی صحبت کردم", "صحبت کردم در کتاب"))
    }
    @Test fun keepsUnmatchedWords() {
        assertEquals("یک مفهوم تازه", WhisperTextJoiner.removeRepeatedPrefix("این یک نظریه است", "یک مفهوم تازه"))
    }
    @Test fun preservesSingleWordRepetition() {
        assertEquals("سلام دوباره", WhisperTextJoiner.removeRepeatedPrefix("سلام", "سلام دوباره"))
    }
}
