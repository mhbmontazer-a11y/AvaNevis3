package ir.avanevis.study.domain

import android.content.Context

/** Offline spelling normalization based on the user-provided psychology lexicon.
 * Deliberately avoids fuzzy rewriting: many Persian words are valid homophones.
 */
object PsychologyLexicon {
    @Volatile private var canonical: Map<String, String> = emptyMap()
    private val token = Regex("[\\p{L}\\p{M}\\u200c]+")
    private val contextTerms = listOf(
        "فروید", "ایگو", "سوپرایگو", "روان‌کاوی", "روانکاوی", "نهاد", "من برتر", "ناهشیار"
    )
    private val explicitForms = mapOf(
        "روانشناسی" to "روان‌شناسی", "روانشناس" to "روان‌شناس",
        "روانکاوی" to "روان‌کاوی", "روانکاو" to "روان‌کاو",
        "رواندرمانی" to "روان‌درمانی", "روانپزشکی" to "روان‌پزشکی",
        "روانپزشک" to "روان‌پزشک", "خودآگاهی" to "خودآگاهی"
    )

    @Synchronized fun initialize(context: Context) {
        if (canonical.isNotEmpty()) return
        val entries = context.assets.open("psychology_lexicon.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val words = entries.map { it.trim().replace('ي', 'ی').replace('ك', 'ک') }
            .filter { it.isNotBlank() }.toSet()
        val preferred = HashMap<String, String>()
        // Only select a canonical form when it is explicitly present in the supplied lexicon.
        for ((variant, target) in explicitForms) {
            if (target in words) preferred[variant] = target
        }
        canonical = preferred
    }

    fun correct(input: String): String {
        if (input.isBlank()) return input
        val normalized = input.replace('ي', 'ی').replace('ك', 'ک')
        var result = token.replace(normalized) { match -> canonical[match.value] ?: match.value }
        // 'عید' is a legitimate word; only change it in psychoanalytic context.
        if (contextTerms.any { result.contains(it) }) {
            result = result.replace(Regex("(?<![\\p{L}\\p{M}])عید(?![\\p{L}\\p{M}])"), "اید")
        }
        return result
    }
}
