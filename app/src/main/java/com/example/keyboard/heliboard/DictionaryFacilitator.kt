package com.example.keyboard.heliboard

import android.content.Context
import com.example.keyboard.ClipboardDatabase
import com.example.keyboard.PersonalDictionaryDao
import com.example.keyboard.PersonalDictionaryItem
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates dictionary operations across multiple dictionaries (Main, Secondary Multilingual, Personal)
 * following HeliBoard's DictionaryFacilitator architecture with dual-dictionary weighting.
 */
class DictionaryFacilitator(private val context: Context) {

    private val logKeeper = TheLogKeeper.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val mainDictionary = BinaryDictionary(context, "main", "en")
    val secondaryDictionary = BinaryDictionary(context, "secondary", "none")

    private val personalDao: PersonalDictionaryDao by lazy {
        ClipboardDatabase.getDatabase(context).personalDictionaryDao()
    }

    private val shortcutCache = ConcurrentHashMap<String, String>()
    private val personalWordsCache = ConcurrentHashMap<String, Int>()

    var primaryLanguage: String = "en"
        private set
    var secondaryLanguage: String = "none"
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    init {
        scope.launch {
            loadAllDictionaries()
        }
    }

    suspend fun loadAllDictionaries() = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
        primaryLanguage = sp.getString("primary_language", "en") ?: "en"
        secondaryLanguage = sp.getString("secondary_language", "none") ?: "none"

        // 1. Load Main binary dictionary
        mainDictionary.loadDictionary(primaryLanguage)

        // 2. Load Secondary binary dictionary if active
        if (secondaryLanguage != "none" && secondaryLanguage != primaryLanguage) {
            secondaryDictionary.loadDictionary(secondaryLanguage)
        }

        // 3. Load personal dictionary into fast memory cache
        refreshPersonalDictionaryCache()

        isReady = true
        logKeeper.log(
            "INFO",
            "DictionaryFacilitator",
            "Dictionaries ready | primary=$primaryLanguage | secondary=$secondaryLanguage"
        )
    }

    fun updateLanguages(primary: String, secondary: String) {
        primaryLanguage = primary
        secondaryLanguage = secondary
        scope.launch {
            mainDictionary.loadDictionary(primary)
            if (secondary != "none" && secondary != primary) {
                secondaryDictionary.loadDictionary(secondary)
            }
            logKeeper.log("INFO", "DictionaryFacilitator", "Updated languages: primary=$primary, secondary=$secondary")
        }
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
        return personalWordsCache.containsKey(clean) ||
                mainDictionary.isValidWord(clean) ||
                (secondaryLanguage != "none" && secondaryDictionary.isValidWord(clean))
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

        // 3. Query primary main dictionary
        val mainSuggestions = mainDictionary.getSuggestions(clean, prevWord, prevPrevWord, limit)
        for (s in mainSuggestions) {
            if (!seen.contains(s.word.lowercase())) {
                results.add(s)
                seen.add(s.word.lowercase())
            }
        }

        // 4. Query secondary dictionary (Multilingual typing - e.g. French while English is primary)
        if (secondaryLanguage != "none" && secondaryDictionary.isReady) {
            val secondarySuggestions = secondaryDictionary.getSuggestions(clean, prevWord, prevPrevWord, limit)
            for (s in secondarySuggestions) {
                if (!seen.contains(s.word.lowercase())) {
                    // Secondary dictionary candidates receive a 0.88x weighting so primary takes priority
                    val adjustedScore = (s.score * 0.88f).toInt()
                    results.add(s.copy(score = adjustedScore, sourceDict = "secondary_$secondaryLanguage"))
                    seen.add(s.word.lowercase())
                }
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
        val results = mutableListOf<SuggestedWordInfo>()
        val seen = HashSet<String>()

        val primaryPredictions = mainDictionary.getNextWordPredictions(prevWord, prevPrevWord, limit)
        for (p in primaryPredictions) {
            results.add(p)
            seen.add(p.word.lowercase())
        }

        if (secondaryLanguage != "none" && secondaryDictionary.isReady) {
            val secondaryPredictions = secondaryDictionary.getNextWordPredictions(prevWord, prevPrevWord, limit)
            for (p in secondaryPredictions) {
                if (!seen.contains(p.word.lowercase())) {
                    results.add(p.copy(score = (p.score * 0.85f).toInt(), sourceDict = "secondary_$secondaryLanguage"))
                    seen.add(p.word.lowercase())
                }
            }
        }

        results.sortByDescending { it.score }
        return results.take(limit)
    }

    fun recordWordUsage(word: String, prevWord: String? = null) {
        scope.launch {
            mainDictionary.recordWordUsage(word, prevWord)
            if (secondaryLanguage != "none" && secondaryDictionary.isReady) {
                if (secondaryDictionary.isValidWord(word)) {
                    secondaryDictionary.recordWordUsage(word, prevWord)
                }
            }
        }
    }

    fun addPersonalWord(word: String, shortcut: String? = null) {
        scope.launch {
            personalDao.insert(PersonalDictionaryItem(word = word, shortcut = shortcut, frequency = 250))
            refreshPersonalDictionaryCache()
        }
    }

    fun removeWord(word: String) {
        val clean = word.lowercase().trim()
        mainDictionary.removeWord(clean)
        secondaryDictionary.removeWord(clean)
        personalWordsCache.remove(clean)
        shortcutCache.remove(clean)
        scope.launch {
            try {
                personalDao.getByShortcut(clean)?.let { item ->
                    personalDao.delete(item)
                }
                val dao = ClipboardDatabase.getDatabase(context).dictionaryWordDao()
                dao.deleteWord(clean)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPersonalDictionaryCache()
        }
    }

    fun loadUserBinaryDictionary(stream: java.io.InputStream) {
        mainDictionary.loadFromBinaryStream(stream)
    }

    fun close() {
        mainDictionary.close()
        secondaryDictionary.close()
        personalWordsCache.clear()
        shortcutCache.clear()
    }
}
