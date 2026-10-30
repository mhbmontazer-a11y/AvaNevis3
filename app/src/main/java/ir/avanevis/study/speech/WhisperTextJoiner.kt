package ir.avanevis.study.speech

/** Conservative overlap de-duplication: never removes non-matching words. */
object WhisperTextJoiner {
    private val space = Regex("\\s+")
    private fun normalized(token: String): String = token.trim('،', '.', '؟', '!', ':', '؛', '"', '\'', '(', ')').replace('ي', 'ی').replace('ك', 'ک')
    fun removeRepeatedPrefix(previous: String, next: String, maxOverlapWords: Int = 14): String {
        val a = previous.trim().split(space).filter { it.isNotBlank() }
        val b = next.trim().split(space).filter { it.isNotBlank() }
        if (a.isEmpty() || b.isEmpty()) return next.trim()
        for (n in minOf(maxOverlapWords, a.size, b.size) downTo 2) {
            if (a.takeLast(n).map(::normalized) == b.take(n).map(::normalized)) {
                return b.drop(n).joinToString(" ")
            }
        }
        return next.trim()
    }
}
