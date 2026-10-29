package ir.avanevis.study.domain

import ir.avanevis.study.data.preferences.DigitDisplayMode
import ir.avanevis.study.data.preferences.StudySettings

/**
 * پردازشگر تخصصی متن فارسی برای اصلاح خروجی موتورهای تشخیص گفتار (ASR)
 * شامل: یکسان‌سازی حروف عربی/فارسی، درج نیم‌فاصله هوشمند، نشانه‌گذاری و تبدیل ارقام.
 */
object PersianTextProcessor {

    private const val ZWNJ = "‌"
    private val whitespace = Regex("\\s+")
    private val verbPrefix = Regex("(^|\\s)(ن?می)\\s+(?=[آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی]{2,})")
    private val pluralSuffix = Regex("([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی])\\s+(ها(?:ی|یی|یم|یت|یش|یمان|یتان|یشان)?)(?=\\s|$|[.،؟!؛:])")
    private val comparativeNonJoining = Regex("([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی][اآدذرزژو])\\s+(ترین|تر)(?=\\s|$|[.،؟!؛:])")
    private val comparativeJoining = Regex("([آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی][بپتثجچحخسشصضطظعغفقکگلمنهی])\\s+(ترین|تر)(?=\\s|$|[.،؟!؛:])")
    private val passiveSuffix = Regex("(ثبت|ذخیره|تولید|طراحی|پیاده|ساخته|شناخته|گفته|نوشته|خوانده)\\s+(شده)(?=\\s|$|[.،؟!؛:])")
    private val commaCommand = Regex("\\s+ویرگول(?=\\s|$)")
    private val questionCommand = Regex("\\s+علامت سوال(?=\\s|$)")
    private val questionHamzaCommand = Regex("\\s+علامت سؤال(?=\\s|$)")
    private val colonCommand = Regex("\\s+دو نقطه(?=\\s|$)")
    private val punctuation = Regex("[.،؟!؛:«»\u200c]")
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    // ترکیبات رایج علمی و کتابی که در موتورهای گفتار با فاصله معمولی برگردانده می‌شوند
    private val COMPOUND_WORDS = mapOf(
        "بلند مدت" to "بلندمدت",
        "کوتاه مدت" to "کوتاه‌مدت",
        "میان مدت" to "میان‌مدت",
        "فاصله دار" to "فاصله‌دار",
        "معنی دار" to "معنی‌دار",
        "هدف مند" to "هدفمند",
        "یادداشت برداری" to "یادداشت‌برداری",
        "برنامه ریزی" to "برنامه‌ریزی",
        "تصمیم گیری" to "تصمیم‌گیری",
        "نتیجه گیری" to "نتیجه‌گیری",
        "اندازه گیری" to "اندازه‌گیری",
        "شبیه سازی" to "شبیه‌سازی",
        "بهینه سازی" to "بهینه‌سازی",
        "ذخیره سازی" to "ذخیره‌سازی",
        "پیاده سازی" to "پیاده‌سازی",
        "دسته بندی" to "دسته‌بندی",
        "شماره گذاری" to "شماره‌گذاری",
        "ولویت بندی" to "اولویت‌بندی",
        "اولویت بندی" to "اولویت‌بندی",
        "فوق العاده" to "فوق‌العاده",
        "بین المللی" to "بین‌المللی",
        "نا خودآگاه" to "ناخودآگاه",
        "خود به خود" to "خودبه‌خود",
        "راست به چپ" to "راست‌به‌چپ",
        "نیم فاصله" to "نیم‌فاصله",
        "پیش زمینه" to "پیش‌زمینه",
        "پس زمینه" to "پس‌زمینه",
        "صرفه جویی" to "صرفه‌جویی",
        "انعطاف پذیری" to "انعطاف‌پذیری",
        "یکپارچه سازی" to "یکپارچه‌سازی",
        "چند حسی" to "چندحسی"
    )

    // کلمات پرسشی برای تشخیص خودکار علامت سؤال «؟»
    private val QUESTION_INDICATORS = listOf(
        "آیا ", "چرا ", "چگونه ", "چطور ", "کدام ", "کجا ", "چند ", "کی ",
        "چیست", "کیست", "کجاست", "چگونه است", "آیا"
    )

    fun processUtterance(
        rawText: String,
        settings: StudySettings = StudySettings()
    ): String {
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        // ۱. استانداردسازی حروف عربی به فارسی (ی، ک، ه)
        text = normalizePersianCharacters(text)

        // کلمات تکراری را حذف نمی‌کنیم؛ ممکن است تکرار عمدی و معنادار باشد.
        // ادغام بافرهای تکراری در UtteranceAccumulator انجام می‌شود.

        // ۲. اصلاح نیم‌فاصله‌ها (در صورت فعال بودن در تنظیمات)
        if (settings.fixHalfSpace) {
            text = applyPersianHalfSpaces(text)
        }

        // ۳. تبدیل عبارات گفتاری علائم نگارشی و درج نقطه/علامت سؤال انتهای جمله
        if (settings.autoPunctuation) {
            text = applySmartPunctuation(text)
        }

        // ۴. تبدیل ارقام به فارسی یا لاتین
        text = convertDigits(text, settings.digitMode)

        // ۵. پاک‌سازی فاصله‌های اضافه
        text = text.replace(whitespace, " ").trim()

        return text
    }

    fun normalizePersianCharacters(input: String): String {
        return input
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace('ة', 'ه')
            .replace('ۀ', 'ه')
            .replace("ـ", "") // حذف کشیدگی حروف (تطویل)
    }

    fun applyPersianHalfSpaces(input: String): String {
        var result = input

        // جایگزینی واژگان مرکب پرکاربرد
        for ((spaced, correct) in COMPOUND_WORDS) {
            result = result.replace(spaced, correct)
        }

        // ۱. پیشوند افعال استمراری «می» و «نمی» + فاصله -> نیم‌فاصله
        // مثال: «می شود» -> «می‌شود»، «نمی تواند» -> «نمی‌تواند»
        result = result.replace(
            verbPrefix,
            "$1$2$ZWNJ"
        )

        // ۲. پسوند جمع «ها» و «های» و «هایی»
        // مثال: «روش های» -> «روش‌های»، «کتاب ها» -> «کتاب‌ها»
        result = result.replace(
            pluralSuffix,
            "$1$ZWNJ$2"
        )

        // ۳. پسوند صفت تفضیلی و عالی «تر» و «ترین»
        // الف) حروفی که از چپ نمی‌چسبند (ا آ د ذ ر ز ژ و): مستقیم بدون نیم‌فاصله («مؤثر ترین» -> «مؤثرترین»)
        result = result.replace(
            comparativeNonJoining,
            "$1$2"
        )
        // ب) حروفی که از چپ می‌چسبند: درج نیم‌فاصله («بزرگ تر» -> «بزرگ‌تر»، «مهم ترین» -> «مهم‌ترین»)
        result = result.replace(
            comparativeJoining,
            "$1$ZWNJ$2"
        )

        // ۴. پسوند «شده» در صفات مفعولی (مثال: «ثبت شده» -> «ثبت‌شده»)
        result = result.replace(
            passiveSuffix,
            "$1$ZWNJ$2"
        )

        return result
    }


    fun applySmartPunctuation(input: String): String {
        var text = input.trim()
        if (text.isEmpty()) return text

        // تبدیل علائم گفتاری صریح در صورت بیان شدن
        text = text
            .replace(commaCommand, "،")
            .replace(questionCommand, "؟")
            .replace(questionHamzaCommand, "؟")
            .replace(colonCommand, ":")

        val lastChar = text.last()
        val hasEndingPunctuation = lastChar in charArrayOf('.', '؟', '!', '؛', ':', '…')

        if (!hasEndingPunctuation) {
            val isQuestion = QUESTION_INDICATORS.any { indicator ->
                text.startsWith(indicator) || text.contains(" $indicator")
            }
            text += if (isQuestion) "؟" else "."
        }
        return text
    }

    fun convertDigits(input: String, mode: DigitDisplayMode): String {

        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (mode) {
                DigitDisplayMode.PERSIAN -> {
                    when (ch) {
                        in '0'..'9' -> sb.append(persianDigits[ch - '0'])
                        in '٠'..'٩' -> sb.append(persianDigits[ch - '٠'])
                        else -> sb.append(ch)
                    }
                }
                DigitDisplayMode.LATIN -> {
                    val pIdx = persianDigits.indexOf(ch)
                    val aIdx = arabicDigits.indexOf(ch)
                    when {
                        pIdx >= 0 -> sb.append(('0'.code + pIdx).toChar())
                        aIdx >= 0 -> sb.append(('0'.code + aIdx).toChar())
                        else -> sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * بررسی همپوشانی یا تکرار بین جمله قبلی ثبت‌شده و جمله جدید
     * برای تشخیص متن کاملاً تکراری؛ ادغام اصلی نشست‌ها در موتور گفتار انجام می‌شود.
     */
    fun deduplicateAgainstPrevious(previousSentence: String?, newSentence: String): String {
        val cleanNew = newSentence.trim()
        if (previousSentence.isNullOrBlank() || cleanNew.isEmpty()) return cleanNew

        val normPrev = stripPunctuation(normalizePersianCharacters(previousSentence)).trim()
        val normNew = stripPunctuation(normalizePersianCharacters(cleanNew)).trim()

        // اگر دقیقاً تکراری است، رشته خالی برمی‌گرداند تا ذخیره نشود
        if (normPrev == normNew) return ""

        // Never cut the prefix from a fuller sentence: the older fragment might have
        // been saved by a previous app version. Slicing creates "سلام / حالت چطوره".
        // The speech engine now merges revisions BEFORE committing them to the database.

        return cleanNew
    }

    private fun stripPunctuation(s: String): String {
        return s.replace(punctuation, " ").replace(whitespace, " ").trim()
    }
}
