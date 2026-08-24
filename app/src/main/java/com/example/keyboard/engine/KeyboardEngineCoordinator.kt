package com.example.keyboard.engine

import android.content.Context
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the active IKeyboardEngine implementation, coordinates suggestion retrieval,
 * latency diagnostics, and event routing.
 */
class KeyboardEngineCoordinator private constructor(private val context: Context) {

    private val logKeeper = TheLogKeeper.getInstance(context)
    private var activeEngine: IKeyboardEngine = HeliBoardEngineAdapter()
    private var currentConfig = EngineConfiguration()

    init {
        activeEngine.initialize(context, currentConfig)
        logKeeper.log("INFO", "KeyboardEngineCoordinator", "Initialized with engine: ${activeEngine.engineName}")
    }

    companion object {
        @Volatile
        private var instance: KeyboardEngineCoordinator? = null

        fun getInstance(context: Context): KeyboardEngineCoordinator {
            return instance ?: synchronized(this) {
                instance ?: KeyboardEngineCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }

    fun setEngine(engine: IKeyboardEngine) {
        val oldName = activeEngine.engineName
        activeEngine.release()
        activeEngine = engine
        activeEngine.initialize(context, currentConfig)
        logKeeper.log("INFO", "KeyboardEngineCoordinator", "Engine swapped: $oldName -> ${engine.engineName}")
    }

    fun updateConfig(config: EngineConfiguration) {
        this.currentConfig = config
        activeEngine.updateConfiguration(config)
    }

    fun onKeyPress(charCode: Int, touchX: Float = -1f, touchY: Float = -1f) {
        activeEngine.onKeyPress(charCode, touchX, touchY, System.currentTimeMillis())
    }

    fun onBackspace() {
        activeEngine.onBackspace()
    }

    fun resetBuffer() {
        activeEngine.resetBuffer()
    }

    suspend fun getSuggestions(
        typedWord: String,
        previousWord: String? = null,
        prevPrevWord: String? = null,
        maxSuggestions: Int = 3
    ): SuggestionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val result = activeEngine.getSuggestions(
            typedWord = typedWord,
            previousWord = previousWord,
            prevPrevWord = prevPrevWord,
            maxSuggestions = maxSuggestions
        )
        val elapsed = System.currentTimeMillis() - startTime
        if (!currentConfig.isIncognito && typedWord.isNotEmpty()) {
            logKeeper.log(
                "INFO",
                "KeyboardEngineCoordinator",
                "SUGGESTION_GENERATED | typed=$typedWord | count=${result.suggestions.size} | has_autocorrect=${result.hasAutoCorrectionCandidate} | elapsed_ms=$elapsed"
            )
        }
        result
    }

    fun onWordCommitted(word: String, wasAutocorrected: Boolean, contextWords: List<String>) {
        activeEngine.onWordCommitted(word, wasAutocorrected, contextWords)
    }

    fun onWordReverted(revertedWord: String, correctedWord: String) {
        activeEngine.onWordReverted(revertedWord, correctedWord)
    }

    fun addPersonalWord(word: String, shortcut: String? = null) {
        activeEngine.addPersonalWord(word, shortcut)
    }

    suspend fun loadBinaryDictionary(dictFile: File): Boolean {
        return activeEngine.loadBinaryDictionary(dictFile)
    }

    suspend fun importUserEntries(entries: List<UserDictionaryEntry>): Int {
        return activeEngine.importUserEntries(entries)
    }

    fun release() {
        activeEngine.release()
    }
}
