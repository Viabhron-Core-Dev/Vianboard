package com.example.keyboard.engine

import android.content.Context
import java.io.File

data class EngineConfiguration(
    val autoCorrectAggressiveness: Float = 1.0f,
    val isNextWordPredictionEnabled: Boolean = true,
    val maxSuggestions: Int = 3,
    val isIncognito: Boolean = false
)

data class EngineSuggestion(
    val word: String,
    val score: Int = 0,
    val isAutoCorrectionCandidate: Boolean = false,
    val source: String = "heliboard"
)

data class SuggestionResult(
    val suggestions: List<EngineSuggestion>,
    val rawTypedWord: String,
    val hasAutoCorrectionCandidate: Boolean = false
)

data class UserDictionaryEntry(
    val word: String,
    val shortcut: String? = null,
    val frequency: Int = 250,
    val locale: String = "en"
)

/**
 * Decoupled engine contract interface.
 * Enables the dictionary and prediction engine to be swapped or upgraded
 * without modifying UI, IME connection, toolbar, or settings code.
 */
interface IKeyboardEngine {
    val engineName: String

    /** Initialize engine with assets, dictionaries, and user preferences */
    fun initialize(context: Context, config: EngineConfiguration)
    
    /** Reset the active word buffer and coordinate history */
    fun resetBuffer()
    
    /** Process a discrete keypress with continuous spatial touch coordinates */
    fun onKeyPress(charCode: Int, touchX: Float = -1f, touchY: Float = -1f, timestamp: Long = System.currentTimeMillis())
    
    /** Process backspace / character deletion */
    fun onBackspace()
    
    /** Retrieve ranked candidate suggestions for the current buffer & context */
    suspend fun getSuggestions(
        typedWord: String,
        previousWord: String? = null,
        prevPrevWord: String? = null,
        maxSuggestions: Int = 3
    ): SuggestionResult
    
    /** Commit word notification (updates dynamic user history & bigram weights) */
    fun onWordCommitted(
        word: String,
        wasAutocorrected: Boolean = false,
        contextWords: List<String> = emptyList()
    )
    
    /** Revert / undo commit notification (penalizes false autocorrect entries) */
    fun onWordReverted(revertedWord: String, correctedWord: String)
    
    /** Import external binary dictionary file (.dict) */
    suspend fun loadBinaryDictionary(dictFile: File): Boolean
    
    /** Import user dictionary entries & shortcuts */
    suspend fun importUserEntries(entries: List<UserDictionaryEntry>): Int
    
    /** Add a single personal word */
    fun addPersonalWord(word: String, shortcut: String? = null)

    /** Update live configuration (e.g. sensitivity, incognito) */
    fun updateConfiguration(config: EngineConfiguration)
    
    /** Release memory and resources */
    fun release()
}
