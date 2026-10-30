package ir.avanevis.study.speech

/** Deterministic Unicode codepoint CER and whitespace-token WER. */
object WhisperMetrics {
    private fun <T> distance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val curr = IntArray(b.size + 1)
            curr[0] = i + 1
            for (j in b.indices) curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1,
                prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = curr
        }
        return prev[b.size]
    }
    fun cer(reference: String, hypothesis: String): Double {
        val a = reference.codePoints().toArray().toList()
        val b = hypothesis.codePoints().toArray().toList()
        return distance(a, b).toDouble() / a.size.coerceAtLeast(1)
    }
    fun wer(reference: String, hypothesis: String): Double {
        val a = reference.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = hypothesis.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return distance(a, b).toDouble() / a.size.coerceAtLeast(1)
    }
}
