package ir.avanevis.study.domain

import android.content.Context
import kotlin.math.abs

/** Conservative offline terminology normalization. The user's lexicon is the source of truth. */
object PsychologyLexicon {
    private val words = Regex("[\\p{L}\\p{M}]+")
    private val separators = Regex("[\\s\\u200c]+")
    private val eid = Regex("(?<![\\p{L}\\p{M}])عید(?![\\p{L}\\p{M}])")
    private val anchors = listOf("فروید", "زیگموند", "ایگو", "سوپرایگو", "سوپر ایگو", "روان‌کاوی", "روانکاوی", "ناهشیار", "لیبیدو", "اصل لذت", "نهاد روان", "من برتر")
    private val holiday = Regex("عید\\s+(نوروز|فطر|قربان|غدیر|میلاد|مبعث)")
    private val aliases = mapOf(
        "روانشناسی" to "روان‌شناسی", "روانشاسی" to "روان‌شناسی",
        "روانشناس" to "روان‌شناس", "روانکاوی" to "روان‌کاوی",
        "روانکاو" to "روان‌کاو", "رواندرمانی" to "روان‌درمانی",
        "روانپزشکی" to "روان‌پزشکی", "روانپزشک" to "روان‌پزشک",
        "سوپر ایگو" to "سوپرایگو", "سوپر اگو" to "سوپرایگو",
        "نا خودآگاه" to "ناخودآگاه", "نا خود اگاه" to "ناخودآگاه",
        "خود اگاهی" to "خودآگاهی", "تداعی ازاد" to "تداعی آزاد",
        "طرحواره درمانی" to "طرحواره‌درمانی", "طرحوارهدرمانی" to "طرحواره‌درمانی",
        "شناخت درمانی" to "شناخت‌درمانی", "روان پویشی" to "روان‌پویشی"
    )
    private data class Index(
        val phrases: Map<String, String>,
        val fuzzy: Map<String, List<Pair<String, String>>>,
        val maxWords: Int,
        val terms: Set<String>
    )
    @Volatile private var index = Index(emptyMap(), emptyMap(), 1, emptySet())

    private fun normalize(s: String): String = s.replace('ي', 'ی').replace('ك', 'ک').replace('ى', 'ی').replace('ة', 'ه')
    private fun key(s: String): String = separators.replace(normalize(s).trim(), " ")
    private fun priority(s: String) = if ('\u200c' in s) 1 else 0

    @Synchronized fun initialize(context: Context) {
        if (index.terms.isNotEmpty()) return
        val lines = context.assets.open("psychology_lexicon.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        loadEntries(lines)
    }

    /** Testable without an Android device; also supports reloading the packaged lexicon. */
    @Synchronized fun loadEntries(entries: List<String>) {
        val terms = entries.asSequence().map { normalize(it.trim()) }
            .filter { it.isNotBlank() && !it.startsWith('#') }.distinct().toSet()
        val phrases = HashMap<String, String>()
        for (term in terms) {
            val k = key(term)
            if (k.isBlank()) continue
            val old = phrases[k]
            if (old == null || priority(term) > priority(old)) phrases[k] = term
        }
        for ((variant, target) in aliases) if (target in terms) phrases[key(variant)] = target
        // Fuzzy matches are only considered for long words and only inside a domain context.
        val fuzzy = HashMap<String, MutableList<Pair<String, String>>>()
        for ((k, target) in phrases) {
            if (' ' in k || k.length !in 9..24) continue
            for (length in k.length - 1..k.length + 1) {
                fuzzy.getOrPut("${k.take(3)}:$length") { mutableListOf() }.add(k to target)
            }
        }
        index = Index(phrases, fuzzy, (phrases.keys.maxOfOrNull { it.count { c -> c == ' ' } + 1 } ?: 1).coerceAtMost(6), terms)
    }

    private fun oneEdit(a: String, b: String): Boolean {
        if (a == b || abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var errors = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++errors > 1) return false
            if (a.length > b.length) i++ else if (a.length < b.length) j++ else { i++; j++ }
        }
        return errors + (a.length - i) + (b.length - j) == 1
    }

    private fun inContext(s: String): Boolean = anchors.any { s.contains(it) }

    /** Score favors exact multiword terminology, not generic words or hallucinated additions. */
    fun technicalScore(text: String): Int {
        val idx = index
        val normalized = normalize(text)
        val tokens = words.findAll(normalized).map { it.value }.toList()
        var score = anchors.count { normalized.contains(it) } * 2
        var i = 0
        while (i < tokens.size) {
            var found = 0
            for (n in minOf(idx.maxWords, tokens.size - i) downTo 2) {
                if (idx.phrases.containsKey(tokens.subList(i, i + n).joinToString(" "))) { found = n; break }
            }
            if (found > 0) { score += 2; i += found } else i++
        }
        return score
    }

    fun correct(input: String): String {
        if (input.isBlank()) return input
        val idx = index
        val text = normalize(input)
        val tokens = words.findAll(text).toList()
        if (tokens.isEmpty()) return text
        val result = StringBuilder(text.length + 20)
        var cursor = 0
        var i = 0
        val domain = inContext(text)
        while (i < tokens.size) {
            var replacement: String? = null
            var used = 1
            for (n in minOf(idx.maxWords, tokens.size - i) downTo 1) {
                // Only join whitespace or ZWNJ. Never consume punctuation between terms.
                if (n > 1 && (i until i + n - 1).any { j ->
                    text.substring(tokens[j].range.last + 1, tokens[j + 1].range.first)
                        .any { !it.isWhitespace() && it != '\u200c' }
                }) continue
                val k = tokens.subList(i, i + n).joinToString(" ") { it.value }
                val target = idx.phrases[k]
                if (target != null) { replacement = target; used = n; break }
            }
            if (replacement == null && domain) {
                val term = tokens[i].value
                if (term.length in 9..24) {
                    val matches = idx.fuzzy["${term.take(3)}:${term.length}"].orEmpty()
                        .filter { (k, _) -> k.takeLast(2) == term.takeLast(2) && oneEdit(term, k) }
                        .map { it.second }.distinct()
                    if (matches.size == 1) replacement = matches.single()
                }
            }
            result.append(text, cursor, tokens[i].range.first)
            result.append(replacement ?: tokens[i].value)
            cursor = tokens[i + used - 1].range.last + 1
            i += used
        }
        result.append(text, cursor, text.length)
        val output = result.toString()
        // Only replace the specific occurrence near a psychoanalytic anchor; preserve holiday phrases.
        return eid.replace(output) { m ->
            val start = m.range.first
            val neighborhood = output.substring((start - 90).coerceAtLeast(0), (start + 90).coerceAtMost(output.length))
            if (inContext(neighborhood) && !holiday.containsMatchIn(neighborhood)) "اید" else m.value
        }
    }
}
