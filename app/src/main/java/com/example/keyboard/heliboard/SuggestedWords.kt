package com.example.keyboard.heliboard

/**
 * Encapsulates a collection of suggestions produced by HeliBoard's Suggest engine.
 */
data class SuggestedWords(
    val suggestionsList: List<SuggestedWordInfo>,
    val rawTypedWord: String,
    val hasAutoCorrectionCandidate: Boolean,
    val isPunctuationSuggestions: Boolean = false,
    val isPrediction: Boolean = false
) {
    val size: Int get() = suggestionsList.size
    val isEmpty: Boolean get() = suggestionsList.isEmpty()

    fun getWord(index: Int): String? {
        return suggestionsList.getOrNull(index)?.word
    }

    fun getInfo(index: Int): SuggestedWordInfo? {
        return suggestionsList.getOrNull(index)
    }

    val autoCorrectWord: String?
        get() {
            if (!hasAutoCorrectionCandidate || suggestionsList.isEmpty()) return null
            // Top candidate is auto-correct recommendation
            return suggestionsList.firstOrNull { it.isAutoCorrectionCandidate }?.word
                ?: suggestionsList.firstOrNull()?.word
        }

    companion object {
        val EMPTY = SuggestedWords(
            suggestionsList = emptyList(),
            rawTypedWord = "",
            hasAutoCorrectionCandidate = false
        )
    }
}
