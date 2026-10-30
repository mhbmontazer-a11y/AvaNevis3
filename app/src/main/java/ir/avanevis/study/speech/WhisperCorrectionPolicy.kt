package ir.avanevis.study.speech

import ir.avanevis.study.domain.PsychologyLexicon

/** Conservative two-ASR arbitration; never let an unrelated Whisper segment overwrite notes. */
internal object WhisperCorrectionPolicy {
    private val junk = Regex("[.،؟!؛\\s]+")
    private fun normalized(s: String): String = s.replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun shouldReplace(original: String, candidate: String): Boolean {
        val a = normalized(original)
        val b = normalized(candidate)
        if (a == b || a.length < 5 || b.length < 5) return false
        if (junk.matches(candidate) || b.split(' ').distinct().size == 1 && b.length > 50) return false
        if (a.length > b.length * 1.5 || b.length > a.length * 1.5) return false
        val prev = IntArray(b.length + 1) { it }
        val next = IntArray(b.length + 1)
        for (i in a.indices) {
            next[0] = i + 1
            for (j in b.indices) {
                next[j + 1] = minOf(next[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            next.copyInto(prev)
        }
        val similarity = 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
        val oldScore = PsychologyLexicon.technicalScore(original)
        val newScore = PsychologyLexicon.technicalScore(candidate)
        if (newScore < oldScore) return false
        // Favor keeping the original unless the two recognizers strongly agree.
        return similarity >= if (newScore > oldScore) 0.76 else 0.85
    }
}
