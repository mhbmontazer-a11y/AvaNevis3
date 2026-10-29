package ir.avanevis.study.domain

/**
 * تشخیص هوشمند دستورات صوتی اختیاری
 * فقط زمانی دستور را تشخیص می‌دهد که عبارت کوتاه و دقیقاً منطبق بر الگوی دستور باشد
 * تا هنگام خواندن جملات کتاب که حاوی کلماتی مثل «یادداشت» یا «مطالعه» هستند، اشتباهاً فعال نشود.
 */
enum class StudyVoiceCommand {
    NEW_NOTEBOOK_SESSION, // «یادداشت جدید»
    NEW_PARAGRAPH_LINE,   // «خط بعد»
    DELETE_LAST_SENTENCE, // «حذف جمله آخر»
    STOP_STUDY_SESSION    // «پایان مطالعه»
}

object VoiceCommandParser {

    private val EXACT_COMMANDS: Map<String, StudyVoiceCommand> = mapOf(
        "یادداشت جدید" to StudyVoiceCommand.NEW_NOTEBOOK_SESSION,
        "دفتر جدید" to StudyVoiceCommand.NEW_NOTEBOOK_SESSION,
        "خط بعد" to StudyVoiceCommand.NEW_PARAGRAPH_LINE,
        "خط بعدی" to StudyVoiceCommand.NEW_PARAGRAPH_LINE,
        "پاراگراف بعد" to StudyVoiceCommand.NEW_PARAGRAPH_LINE,
        "حذف جمله آخر" to StudyVoiceCommand.DELETE_LAST_SENTENCE,
        "پاک کردن جمله آخر" to StudyVoiceCommand.DELETE_LAST_SENTENCE,
        "جمله آخر حذف شود" to StudyVoiceCommand.DELETE_LAST_SENTENCE,
        "پایان مطالعه" to StudyVoiceCommand.STOP_STUDY_SESSION,
        "توقف مطالعه" to StudyVoiceCommand.STOP_STUDY_SESSION,
        "اتمام مطالعه" to StudyVoiceCommand.STOP_STUDY_SESSION
    )

    fun parseCommandIfApplicable(
        rawTranscript: String,
        commandsEnabled: Boolean
    ): StudyVoiceCommand? {
        if (!commandsEnabled) return null
        val normalized = PersianTextProcessor.normalizePersianCharacters(rawTranscript)
            .replace(Regex("[.،؟!؛:]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // اگر جمله بیش از ۴ کلمه دارد، قطعاً بخشی از متن کتاب است و دستور صوتی نیست
        val wordCount = normalized.split(" ").size
        if (wordCount > 4) return null

        return EXACT_COMMANDS[normalized]
    }
}
