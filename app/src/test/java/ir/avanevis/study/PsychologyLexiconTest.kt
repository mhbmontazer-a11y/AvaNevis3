package ir.avanevis.study

import ir.avanevis.study.domain.PsychologyLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PsychologyLexiconTest {
    @Before fun setup() {
        PsychologyLexicon.loadEntries(listOf("روان‌شناسی", "روان‌کاوی", "سوپرایگو", "تداعی آزاد", "طرحواره‌درمانی", "روان‌درمانی", "روان‌پزشکی", "فروید", "ایگو", "ناهشیار", "ناخودآگاه"))
    }
    @Test fun joinsSpecializedPhrases() {
        assertEquals("تداعی آزاد و روان‌کاوی", PsychologyLexicon.correct("تداعی آزاد و روان کاوی"))
    }
    @Test fun normalizesArabicCharacters() {
        assertEquals("روان‌کاوی", PsychologyLexicon.correct("روان كاوي"))
    }
    @Test fun protectsPunctuation() {
        assertEquals("تداعی، آزاد", PsychologyLexicon.correct("تداعی، آزاد"))
    }
    @Test fun psychoanalyticIdIsContextual() {
        assertEquals("فروید درباره اید گفت", PsychologyLexicon.correct("فروید درباره عید گفت"))
        assertEquals("عید نوروز مبارک", PsychologyLexicon.correct("عید نوروز مبارک"))
        assertEquals("عید فطر و فروید", PsychologyLexicon.correct("عید فطر و فروید"))
    }
    @Test fun doesNotGuessShortWords() {
        assertEquals("این عید است", PsychologyLexicon.correct("این عید است"))
    }
    @Test fun terminologyScore() {
        assertTrue(PsychologyLexicon.technicalScore("فروید و ایگو") > PsychologyLexicon.technicalScore("سلام دنیا"))
    }
}
