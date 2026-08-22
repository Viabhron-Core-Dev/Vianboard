package com.example.keyboard.heliboard

import android.content.Context
import com.example.logkeeper.TheLogKeeper

/**
 * Suggest engine corresponding to HeliBoard's Suggest class.
 * Computes suggestion lists, autocorrection decisions, and candidate layouts.
 */
class Suggest(
    private val context: Context,
    val dictionaryFacilitator: DictionaryFacilitator
) {
    private val logKeeper = TheLogKeeper.getInstance(context)

    // Autocorrect threshold: ratio between typed word score and correction candidate
    var autoCorrectThreshold: Float = 0.85f
    var isAutocorrectEnabled: Boolean = true

    /**
     * Compute suggested words for the currently composed text.
     */
    fun getSuggestedWords(
        composedWord: String,
        prevWord: String?,
        prevPrevWord: String?,
        limit: Int = 10
    ): SuggestedWords {
        val rawTyped = composedWord.trim()
        if (rawTyped.isEmpty()) {
            // Next-word prediction mode
            if (prevWord != null) {
                val predictions = dictionaryFacilitator.getNextWordPredictions(prevWord, prevPrevWord, limit)
                return SuggestedWords(
                    suggestionsList = predictions,
                    rawTypedWord = "",
                    hasAutoCorrectionCandidate = false,
                    isPrediction = true
                )
            }
            return SuggestedWords.EMPTY
        }

        val rawCandidates = dictionaryFacilitator.getSuggestions(rawTyped, prevWord, prevPrevWord, limit)
        if (rawCandidates.isEmpty()) {
            return SuggestedWords(
                suggestionsList = listOf(
                    SuggestedWordInfo(
                        word = rawTyped,
                        score = 100,
                        kind = SuggestedWordInfo.Kind.TYPED
                    )
                ),
                rawTypedWord = rawTyped,
                hasAutoCorrectionCandidate = false
            )
        }

        val firstCandidate = rawCandidates.first()
        val isExactMatch = firstCandidate.word.equals(rawTyped, ignoreCase = true)
        val hasAutoCorrection = isAutocorrectEnabled && (
            firstCandidate.kind == SuggestedWordInfo.Kind.SHORTCUT ||
            (firstCandidate.kind == SuggestedWordInfo.Kind.CORRECTION && !isExactMatch && firstCandidate.score >= 500) ||
            (!isExactMatch && !dictionaryFacilitator.isValidWord(rawTyped) && firstCandidate.score >= 600)
        )

        // Ensure typed word is available to user if auto-correct is suggesting a different word
        val finalSuggestions = mutableListOf<SuggestedWordInfo>()
        if (hasAutoCorrection && !isExactMatch) {
            // Place top correction first, but keep typed word as alternate
            finalSuggestions.addAll(rawCandidates)
            if (finalSuggestions.none { it.word.equals(rawTyped, ignoreCase = true) }) {
                finalSuggestions.add(
                    SuggestedWordInfo(
                        word = rawTyped,
                        score = 200,
                        kind = SuggestedWordInfo.Kind.TYPED
                    )
                )
            }
        } else {
            finalSuggestions.addAll(rawCandidates)
        }

        return SuggestedWords(
            suggestionsList = finalSuggestions.take(limit),
            rawTypedWord = rawTyped,
            hasAutoCorrectionCandidate = hasAutoCorrection,
            isPrediction = false
        )
    }

    /**
     * Determines whether pressing Space or punctuation should automatically commit the auto-corrected candidate.
     */
    fun shouldAutoCorrect(suggestedWords: SuggestedWords): Boolean {
        if (!isAutocorrectEnabled) return false
        if (!suggestedWords.hasAutoCorrectionCandidate) return false
        val best = suggestedWords.suggestionsList.firstOrNull() ?: return false
        if (best.word.equals(suggestedWords.rawTypedWord, ignoreCase = true)) return false
        return best.score >= 500
    }
}
