package ir.avanevis.study.speech

/** Reject unrelated output and most hallucinations; leave original text intact on doubt. */
internal object WhisperCorrectionPolicy {
    private fun normalize(s: String): String = s
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun shouldReplace(original: String, candidate: String): Boolean {
        val a = normalize(original)
        val b = normalize(candidate)
        if (a == b || a.length < 8 || b.length < 8) return false
        if (a.length > b.length * 2 || b.length > a.length * 2) return false
        val prev = IntArray(b.length + 1) { it }
        val next = IntArray(b.length + 1)
        for (i in a.indices) {
            next[0] = i + 1
            for (j in b.indices) {
                next[j + 1] = minOf(next[j] + 1, prev[j + 1] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            next.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length) >= 0.70
    }
}
