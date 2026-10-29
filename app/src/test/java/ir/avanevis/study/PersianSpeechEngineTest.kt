package ir.avanevis.study

import ir.avanevis.study.data.preferences.DigitDisplayMode
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.PersianTextProcessor
import ir.avanevis.study.domain.StudyVoiceCommand
import ir.avanevis.study.domain.VoiceCommandParser
import org.junit.Assert.*
import org.junit.Test

/**
 * مجموعه تست‌های واحد برای راستی‌آزمایی الزامات ۷گانه بخش ۱۱ پرامپت
 */
class PersianSpeechEngineTest {

    private val defaultSettings = StudySettings()

    @Test
    fun test1_continuousFiveSentencesOrderedAndDeduplicated() {
        val rawInputs = listOf(
            "حافظه بلند مدت نقش مهمی در یادگیری پایدار دارد",
            "مرور فاصله دار یکی از مؤثر ترین روش های تثبیت اطلاعات است",
            "تکرار فعال باعث افزایش توانایی بازیابی اطلاعات می شود",
            "خواب کافی به یکپارچه سازی حافظه در هیپوکامپ کمک می کند",
            "آیا مطالعه چند حسی باعث افزایش تمرکز در جلسات طولانی می شود"
        )

        val savedNotes = mutableListOf<String>()
        var lastSaved: String? = null

        for (raw in rawInputs) {
            val processed = PersianTextProcessor.processUtterance(raw, defaultSettings)
            val dedup = PersianTextProcessor.deduplicateAgainstPrevious(lastSaved, processed)
            if (dedup.isNotBlank()) {
                savedNotes.add(dedup)
                lastSaved = dedup
            }
        }

        assertEquals(5, savedNotes.size)
        assertEquals("حافظه بلندمدت نقش مهمی در یادگیری پایدار دارد.", savedNotes[0])
        assertEquals("مرور فاصله‌دار یکی از مؤثرترین روش‌های تثبیت اطلاعات است.", savedNotes[1])
        assertEquals("تکرار فعال باعث افزایش توانایی بازیابی اطلاعات می‌شود.", savedNotes[2])
        assertTrue(savedNotes[4].endsWith("؟"))
    }

    @Test
    fun test2_persianNormalizationAndHalfSpaces() {
        val raw = "در سال 2026 استفاده از روش هاي يادداشت برداري هوشمند حدود 45 درصد سرعت مطالعه را بیشتر می کند"
        val processed = PersianTextProcessor.processUtterance(raw, defaultSettings)

        assertFalse(processed.contains('ي'))
        assertFalse(processed.contains('ك'))
        assertTrue(processed.contains("روش‌های"))
        assertTrue(processed.contains("یادداشت‌برداری"))
        assertTrue(processed.contains("می‌کند."))
        assertTrue(processed.contains("۲۰۲۶"))
        assertTrue(processed.contains("۴۵"))
    }

    @Test
    fun test3_preventDuplicateBetweenPartialAndFinalResult() {
        val prev = "حافظه بلندمدت نقش مهمی در یادگیری پایدار دارد."
        val duplicateFinal = "حافظه بلند مدت نقش مهمی در یادگیری پایدار دارد"
        val processedFinal = PersianTextProcessor.processUtterance(duplicateFinal, defaultSettings)
        val result = PersianTextProcessor.deduplicateAgainstPrevious(prev, processedFinal)

        assertEquals("", result)
    }

    @Test
    fun test5_repeatedWordsAndPersianHamzaArePreserved() {
        val raw = "این مسئله مهم است و نه نه، این روش خیلی خیلی مؤثر است"
        val processed = PersianTextProcessor.processUtterance(raw, defaultSettings)

        // Repetition can be intentional and the letter ئ is valid in Persian words.
        assertTrue(processed.contains("مسئله"))
        assertTrue(processed.contains("نه نه"))
        assertTrue(processed.contains("خیلی خیلی"))
    }

    @Test
    fun test4_voiceCommandStrictMatchingDoesNotCaptureBookSentences() {
        // وقتی کاربر فقط دستور را می‌گوید، باید به عنوان دستور شناسایی شود
        val cmd = VoiceCommandParser.parseCommandIfApplicable("حذف جمله آخر", commandsEnabled = true)
        assertEquals(StudyVoiceCommand.DELETE_LAST_SENTENCE, cmd)

        // وقتی عبارت داخل یک جمله علمی کتاب است، هرگز نباید دستور تلقی شود
        val bookSentence = "در این فصل یادداشت جدید نویسنده درباره پایان مطالعه بررسی می‌شود"
        val falseCmd = VoiceCommandParser.parseCommandIfApplicable(bookSentence, commandsEnabled = true)
        assertNull(falseCmd)
    }
}
