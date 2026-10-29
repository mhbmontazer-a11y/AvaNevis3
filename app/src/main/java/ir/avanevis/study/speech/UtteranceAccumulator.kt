package ir.avanevis.study.speech

/**
 * Collects mutable ASR hypotheses WITHOUT saving them as individual notes.
 * A recognizer may emit: "سلام" -> "سلام حالت" -> "سلام حالت چطوره".
 * The partial is replaced each time; only final results are folded into the pending utterance.
 *
 * Some Android recognizers close the microphone at short pauses even when longer silence
 * extras were requested. The next recognition session is merged into the same pending
 * utterance until the app's quiet-period timer expires.
 */
class UtteranceAccumulator {
    private var finalizedParts = ""
    private var liveHypothesis = ""

    val hasText: Boolean get() = finalizedParts.isNotBlank() || liveHypothesis.isNotBlank()
    val hasLiveHypothesis: Boolean get() = liveHypothesis.isNotBlank()
    val preview: String get() = merge(finalizedParts, liveHypothesis)

    fun onPartial(hypothesis: String): String {
        // Partial results are revisions, NOT new phrases.
        liveHypothesis = hypothesis.trim()
        return preview
    }

    fun onFinal(finalTranscript: String): String {
        val best = finalTranscript.trim().ifBlank { liveHypothesis }
        finalizedParts = merge(finalizedParts, best)
        liveHypothesis = ""
        return finalizedParts
    }

    fun onRecognizerFailure(): String = onFinal("")

    fun finish(): String {
        val result = preview
        clear()
        return result
    }

    fun clear() {
        finalizedParts = ""
        liveHypothesis = ""
    }

    companion object {
        private val spaces = Regex("\\s+")

        /** Join revisions/adjacent ASR sessions with the greatest *word* overlap. */
        fun merge(earlier: String, later: String): String {
            val first = earlier.trim().replace(spaces, " ")
            val second = later.trim().replace(spaces, " ")
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first

            val a = first.split(' ')
            val b = second.split(' ')
            val aKey = a.map(::comparisonKey)
            val bKey = b.map(::comparisonKey)

            // If the provider repeats an expanding version of its previous output,
            // keep only the most complete version.
            if (aKey.size <= bKey.size && aKey == bKey.take(aKey.size)) return second
            if (bKey.size <= aKey.size && bKey == aKey.take(bKey.size)) return first

            for (overlap in minOf(a.size, b.size) downTo 1) {
                if (aKey.takeLast(overlap) == bKey.take(overlap)) {
                    if (overlap == b.size) return first
                    val left = first.trimEnd('.', '،', '؟', '!', ' ', '؛')
                    return "$left ${b.drop(overlap).joinToString(" ")}".trim()
                }
            }
            // A provider may punctuate each short ASR session independently.
            // Until the user's logical utterance is finalized, strip that boundary.
            val left = first.trimEnd('.', '،', '؟', '!', ' ', '؛')
            return "$left $second"
        }

        private fun comparisonKey(word: String): String = word
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .trim('.', '،', '؟', '!', '؛', ':', '«', '»')
    }
}
