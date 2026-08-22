package com.example.keyboard.heliboard

import android.content.Context
import com.example.keyboard.ClipboardDatabase
import com.example.keyboard.PersonalDictionaryDao
import com.example.keyboard.PersonalDictionaryItem
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates dictionary operations across multiple dictionaries (Main, Personal, History)
 * following HeliBoard's DictionaryFacilitator architecture.
 */
class DictionaryFacilitator(private val context: Context) {

    private val logKeeper = TheLogKeeper.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val mainDictionary = BinaryDictionary(context, "main")
    private val personalDao: PersonalDictionaryDao by lazy {
        ClipboardDatabase.getDatabase(context).personalDictionaryDao()
    }

    private val shortcutCache = ConcurrentHashMap<String, String>()
    private val personalWordsCache = ConcurrentHashMap<String, Int>()

    @Volatile
    var isReady: Boolean = false
        private set

    init {
        scope.launch {
            loadAllDictionaries()
        }
    }

    suspend fun loadAllDictionaries() = withContext(Dispatchers.IO) {
        // 1. Load Main binary dictionary
        mainDictionary.loadDictionary()

        // 2. Load personal dictionary into fast memory cache
        refreshPersonalDictionaryCache()

        isReady = true
        logKeeper.log("INFO", "DictionaryFacilitator", "All HeliBoard dictionaries ready.")
    }

    suspend fun refreshPersonalDictionaryCache() = withContext(Dispatchers.IO) {
        try {
            val personalWords = personalDao.getSuggestions("", 500)
            personalWordsCache.clear()
            shortcutCache.clear()
            for (item in personalWords) {
                personalWordsCache[item.word.lowercase()] = item.frequency
                if (!item.shortcut.isNullOrBlank()) {
                    shortcutCache[item.shortcut.lowercase()] = item.word
                }
            }
        } catch (e: Exception) {
            logKeeper.log("ERROR", "DictionaryFacilitator", "Error loading personal dict: ${e.message}")
        }
    }

    fun getShortcut(word: String): String? {
        return shortcutCache[word.lowercase().trim()]
    }

    fun isValidWord(word: String): Boolean {
        val clean = word.lowercase().trim()
        return personalWordsCache.containsKey(clean) || mainDictionary.isValidWord(clean)
    }

    fun getSuggestions(
        composedWord: String,
        prevWord: String?,
        prevPrevWord: String?,
        limit: Int = 10
    ): List<SuggestedWordInfo> {
        val clean = composedWord.trim()
        if (clean.isEmpty()) return emptyList()

        val results = mutableListOf<SuggestedWordInfo>()
        val seen = HashSet<String>()

        // 1. Check for personal dictionary shortcut expansion (Highest priority)
        val shortcutTarget = getShortcut(clean)
        if (shortcutTarget != null) {
            results.add(
                SuggestedWordInfo(
                    word = shortcutTarget,
                    score = 1200,
                    kind = SuggestedWordInfo.Kind.SHORTCUT,
                    sourceDict = "personal"
                )
            )
            seen.add(shortcutTarget.lowercase())
        }

        // 2. Check personal dictionary words with prefix
        for ((pWord, freq) in personalWordsCache) {
            if (pWord.startsWith(clean.lowercase())) {
                if (!seen.contains(pWord)) {
                    results.add(
                        SuggestedWordInfo(
                            word = pWord,
                            score = 950 + freq,
                            kind = SuggestedWordInfo.Kind.SUGGESTION,
                            sourceDict = "personal"
                        )
                    )
                    seen.add(pWord)
                }
            }
        }

        // 3. Query main dictionary
        val mainSuggestions = mainDictionary.getSuggestions(clean, prevWord, prevPrevWord, limit)
        for (s in mainSuggestions) {
            if (!seen.contains(s.word.lowercase())) {
                results.add(s)
                seen.add(s.word.lowercase())
            }
        }

        results.sortByDescending { it.score }
        return results.take(limit)
    }

    fun getNextWordPredictions(
        prevWord: String,
        prevPrevWord: String?,
        limit: Int = 5
    ): List<SuggestedWordInfo> {
        return mainDictionary.getNextWordPredictions(prevWord, prevPrevWord, limit)
    }

    fun recordWordUsage(word: String, prevWord: String? = null) {
        scope.launch {
            mainDictionary.recordWordUsage(word, prevWord)
        }
    }

    fun addPersonalWord(word: String, shortcut: String? = null) {
        scope.launch {
            personalDao.insert(PersonalDictionaryItem(word = word, shortcut = shortcut, frequency = 250))
            refreshPersonalDictionaryCache()
        }
    }

    fun close() {
        mainDictionary.close()
        personalWordsCache.clear()
        shortcutCache.clear()
    }
}
