package ir.avanevis.study.speech

/**
 * Picks a usable recognition alternative without pretending to improve the underlying ASR.
 * A provider may return missing, -1 or NaN confidence; preserve its first-ranked hypothesis
 * in those cases. Ignore blank alternatives and never manufacture a confidence value.
 */
data class SpeechHypothesis(val text: String, val confidence: Float)

object SpeechHypothesisSelector {
    fun select(candidates: List<String>?, confidenceScores: FloatArray?): SpeechHypothesis {
        val items = candidates.orEmpty().mapIndexedNotNull { index, text ->
            val cleaned = text.trim()
            if (cleaned.isBlank()) null else index to cleaned
        }
        if (items.isEmpty()) return SpeechHypothesis("", -1f)
        val ranked = items.mapNotNull { (index, text) ->
            val score = confidenceScores?.getOrNull(index) ?: return@mapNotNull null
            if (!score.isFinite() || score < 0f || score > 1f) null
            else SpeechHypothesis(text, score) to index
        }
        if (ranked.isEmpty()) return SpeechHypothesis(items.first().second, -1f)
        // Preserve provider ordering on ties; only change order with actual confidence evidence.
        return ranked.maxWithOrNull(compareBy<Pair<SpeechHypothesis, Int>> { it.first.confidence }
            .thenBy { -it.second })!!.first
    }
}
