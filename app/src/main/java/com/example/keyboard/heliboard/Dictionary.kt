package com.example.keyboard.heliboard

/**
 * Standard interface for HeliBoard dictionaries (Main binary, Personal, User History).
 */
interface Dictionary {
    val dictType: String
    val isReady: Boolean

    /**
     * Look up exact word in dictionary.
     */
    fun isValidWord(word: String): Boolean

    /**
     * Get unigram frequency (0 - 255).
     */
    fun getFrequency(word: String): Int

    /**
     * Get suggestions for a composed input word given previous words context.
     */
    fun getSuggestions(
        composedWord: String,
        prevWord: String?,
        prevPrevWord: String?,
        limit: Int = 10
    ): List<SuggestedWordInfo>

    /**
     * Get next word predictions given preceding words.
     */
    fun getNextWordPredictions(
        prevWord: String,
        prevPrevWord: String?,
        limit: Int = 5
    ): List<SuggestedWordInfo>

    /**
     * Record user typing a word to learn bigrams and frequencies.
     */
    fun recordWordUsage(word: String, prevWord: String? = null)

    /**
     * Release any native or memory-mapped resources.
     */
    fun close()
}
