package ir.avanevis.study

import ir.avanevis.study.speech.UtteranceAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceAccumulatorTest {
    @Test fun progressivePartialsNeverCreateSeparateNotes() {
        val accumulator = UtteranceAccumulator()
        assertEquals("سلام", accumulator.onPartial("سلام"))
        assertEquals("سلام حالت", accumulator.onPartial("سلام حالت"))
        assertEquals("سلام حالت چطوره", accumulator.onPartial("سلام حالت چطوره"))
        assertEquals("سلام حالت چطوره", accumulator.onFinal("سلام حالت چطوره"))
        assertEquals("سلام حالت چطوره", accumulator.finish())
        assertFalse(accumulator.hasText)
    }

    @Test fun shortPauseAcrossRecognizerRestartsIsOneUtterance() {
        val accumulator = UtteranceAccumulator()
        accumulator.onPartial("سلام")
        accumulator.onFinal("سلام")
        assertEquals("سلام حالت", accumulator.onPartial("حالت"))
        assertEquals("سلام حالت", accumulator.onFinal("حالت"))
        accumulator.onPartial("چطوره")
        accumulator.onFinal("چطوره")
        assertEquals("سلام حالت چطوره", accumulator.finish())
    }

    @Test fun overlappingWindowsAndFullerRevisionsNeverRepeatWords() {
        val accumulator = UtteranceAccumulator()
        accumulator.onFinal("سلام حالت")
        assertEquals("سلام حالت چطوره", accumulator.onPartial("سلام حالت چطوره"))
        assertEquals("سلام حالت چطوره", accumulator.onFinal("سلام حالت چطوره"))
        assertEquals("سلام حالت چطوره", accumulator.finish())

        assertEquals("امروز هوا خیلی خوبه", UtteranceAccumulator.merge("امروز هوا", "هوا خیلی خوبه"))
        assertEquals("امروز هوا", UtteranceAccumulator.merge("امروز هوا", "امروز"))
        assertEquals("سلام حالت چطوره", UtteranceAccumulator.merge("سلام.", "حالت چطوره"))
    }

    @Test fun errorDoesNotClearTheLastHypothesis() {
        val accumulator = UtteranceAccumulator()
        accumulator.onPartial("مطالعه با تمرکز")
        assertEquals("مطالعه با تمرکز", accumulator.onRecognizerFailure())
        assertTrue(accumulator.hasText)
        assertEquals("مطالعه با تمرکز بهتر است", accumulator.onFinal("بهتر است"))
        assertEquals("مطالعه با تمرکز بهتر است", accumulator.finish())
    }
}
