package com.example.keyboard.engine

import android.content.Context
import com.example.keyboard.heliboard.DictionaryFacilitator
import com.example.keyboard.heliboard.Suggest
import com.example.keyboard.heliboard.SuggestedWords
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adapter bridging HeliBoard's DictionaryFacilitator and Suggest engine to IKeyboardEngine.
 */
class HeliBoardEngineAdapter : IKeyboardEngine {
    override val engineName: String = "HeliBoardEngine"

    private lateinit var context: Context
    private lateinit var facilitator: DictionaryFacilitator
    private lateinit var suggest: Suggest
    private var config: EngineConfiguration = EngineConfiguration()
    private val activeBuffer = StringBuilder()

    override fun initialize(context: Context, config: EngineConfiguration) {
        this.context = context
        this.config = config
        this.facilitator = DictionaryFacilitator(context)
        this.suggest = Suggest(context, facilitator)
        this.suggest.autoCorrectThreshold = 1.0f - (config.autoCorrectAggressiveness * 0.3f)
        TheLogKeeper.getInstance(context).log("INFO", "HeliBoardEngineAdapter", "Engine initialized with aggressiveness=${config.autoCorrectAggressiveness}")
    }

    override fun resetBuffer() {
        activeBuffer.clear()
    }

    override fun onKeyPress(charCode: Int, touchX: Float, touchY: Float, timestamp: Long) {
        if (charCode > 0) {
            activeBuffer.append(charCode.toChar())
        }
    }

    override fun onBackspace() {
        if (activeBuffer.isNotEmpty()) {
            activeBuffer.deleteCharAt(activeBuffer.length - 1)
        }
    }

    override suspend fun getSuggestions(
        typedWord: String,
        previousWord: String?,
        prevPrevWord: String?,
        maxSuggestions: Int
    ): SuggestionResult = withContext(Dispatchers.Default) {
        val rawSuggested: SuggestedWords = suggest.getSuggestedWords(
            composedWord = typedWord,
            prevWord = previousWord,
            prevPrevWord = prevPrevWord,
            limit = maxSuggestions
        )

        val suggestions = rawSuggested.suggestionsList.map { info ->
            EngineSuggestion(
                word = info.word,
                score = info.score,
                isAutoCorrectionCandidate = info.isAutoCorrectionCandidate,
                source = "heliboard_binary"
            )
        }

        SuggestionResult(
            suggestions = suggestions,
            rawTypedWord = rawSuggested.rawTypedWord,
            hasAutoCorrectionCandidate = rawSuggested.hasAutoCorrectionCandidate
        )
    }

    override fun onWordCommitted(word: String, wasAutocorrected: Boolean, contextWords: List<String>) {
        if (!config.isIncognito && ::facilitator.isInitialized) {
            val prev = contextWords.getOrNull(0)
            facilitator.recordWordUsage(word.lowercase(), prev?.lowercase())
            TheLogKeeper.getInstance(context).log("INFO", "HeliBoardEngineAdapter", "WORD_COMMITTED | word=$word | autocorrected=$wasAutocorrected | prev=$prev")
        }
        resetBuffer()
    }

    override fun onWordReverted(revertedWord: String, correctedWord: String) {
        TheLogKeeper.getInstance(context).log("INFO", "HeliBoardEngineAdapter", "WORD_REVERTED | reverted=$revertedWord | original=$correctedWord")
    }

    override suspend fun loadBinaryDictionary(dictFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (dictFile.exists() && dictFile.canRead()) {
                val stream = dictFile.inputStream()
                facilitator.loadUserBinaryDictionary(stream)
                TheLogKeeper.getInstance(context).log("INFO", "HeliBoardEngineAdapter", "LOAD_BINARY_DICT_SUCCESS | file=${dictFile.name}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            TheLogKeeper.getInstance(context).log("ERROR", "HeliBoardEngineAdapter", "LOAD_BINARY_DICT_FAILED | error=${e.message}")
            false
        }
    }

    override suspend fun importUserEntries(entries: List<UserDictionaryEntry>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (entry in entries) {
            facilitator.addPersonalWord(entry.word, entry.shortcut)
            count++
        }
        TheLogKeeper.getInstance(context).log("INFO", "HeliBoardEngineAdapter", "USER_ENTRIES_IMPORTED | count=$count")
        count
    }

    override fun addPersonalWord(word: String, shortcut: String?) {
        if (::facilitator.isInitialized) {
            facilitator.addPersonalWord(word, shortcut)
        }
    }

    override fun updateConfiguration(config: EngineConfiguration) {
        this.config = config
        if (::suggest.isInitialized) {
            suggest.autoCorrectThreshold = 1.0f - (config.autoCorrectAggressiveness * 0.3f)
        }
    }

    override fun release() {
        if (::facilitator.isInitialized) {
            facilitator.close()
        }
    }
}
