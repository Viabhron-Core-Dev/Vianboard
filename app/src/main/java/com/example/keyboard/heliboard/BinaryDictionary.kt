package com.example.keyboard.heliboard

import android.content.Context
import com.example.R
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

/**
 * High-performance dictionary implementation following HeliBoard's binary dictionary model.
 * Optimized for low RAM (Android 15 Go, 3GB RAM) with compact indexed node representation,
 * spatial QWERTY proximity scoring, and n-gram probability calculations.
 */
class BinaryDictionary(
    private val context: Context,
    override val dictType: String = "main"
) : Dictionary {

    private val logKeeper = TheLogKeeper.getInstance(context)

    @Volatile
    override var isReady: Boolean = false
        private set

    // Root trie node structure
    private class CompactNode {
        var isTerminal: Boolean = false
        var frequency: Int = 0
        var children: HashMap<Char, CompactNode>? = null
    }

    private val root = CompactNode()
    private val bigrams = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val trigrams = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val exactWordSet = ConcurrentHashMap.newKeySet<String>()

    // Spatial QWERTY keyboard coordinates (row, column) for proximity calculations
    private val qwertyCoords = mapOf(
        'q' to Pair(0f, 0f), 'w' to Pair(0f, 1f), 'e' to Pair(0f, 2f), 'r' to Pair(0f, 3f),
        't' to Pair(0f, 4f), 'y' to Pair(0f, 5f), 'u' to Pair(0f, 6f), 'i' to Pair(0f, 7f),
        'o' to Pair(0f, 8f), 'p' to Pair(0f, 9f),
        'a' to Pair(1f, 0.5f), 's' to Pair(1f, 1.5f), 'd' to Pair(1f, 2.5f), 'f' to Pair(1f, 3.5f),
        'g' to Pair(1f, 4.5f), 'h' to Pair(1f, 5.5f), 'j' to Pair(1f, 6.5f), 'k' to Pair(1f, 7.5f),
        'l' to Pair(1f, 8.5f),
        'z' to Pair(2f, 1.5f), 'x' to Pair(2f, 2.5f), 'c' to Pair(2f, 3.5f), 'v' to Pair(2f, 4.5f),
        'b' to Pair(2f, 5.5f), 'n' to Pair(2f, 6.5f), 'm' to Pair(2f, 7.5f)
    )

    // Common fallback bigram predictions for English
    private val staticBigramSeed = mapOf(
        "how" to listOf("are", "to", "do", "much", "many", "is", "about"),
        "what" to listOf("is", "are", "do", "to", "a", "time", "about"),
        "where" to listOf("are", "is", "were", "do", "can"),
        "when" to listOf("is", "are", "will", "did", "you"),
        "why" to listOf("are", "is", "did", "do", "not"),
        "who" to listOf("is", "are", "was", "will"),
        "i" to listOf("am", "have", "will", "don't", "can", "think", "love", "would", "need", "just"),
        "you" to listOf("are", "can", "will", "have", "know", "think", "want", "should"),
        "he" to listOf("is", "was", "will", "said", "has", "can"),
        "she" to listOf("is", "was", "will", "said", "has", "can"),
        "it" to listOf("is", "was", "will", "can", "would", "has"),
        "we" to listOf("are", "can", "will", "have", "need", "should"),
        "they" to listOf("are", "were", "will", "have", "can"),
        "this" to listOf("is", "was", "will", "one", "week", "month"),
        "that" to listOf("is", "was", "will", "you", "we"),
        "in" to listOf("the", "a", "my", "this", "our", "an", "your"),
        "on" to listOf("the", "a", "my", "this", "your", "time"),
        "at" to listOf("the", "a", "home", "work", "least", "once"),
        "to" to listOf("the", "be", "do", "see", "get", "make", "go", "have", "my"),
        "for" to listOf("the", "a", "you", "me", "this", "your"),
        "of" to listOf("the", "a", "my", "this", "course", "them"),
        "with" to listOf("you", "the", "a", "my", "me", "us"),
        "and" to listOf("the", "I", "a", "we", "then", "you"),
        "is" to listOf("a", "the", "not", "this", "that", "it"),
        "are" to listOf("you", "we", "they", "not", "the"),
        "good" to listOf("morning", "night", "afternoon", "idea", "luck", "job", "time"),
        "thank" to listOf("you", "god", "heavens"),
        "see" to listOf("you", "that", "the", "it"),
        "let" to listOf("me", "us", "you", "them")
    )

    init {
        for ((word, list) in staticBigramSeed) {
            val nextMap = bigrams.getOrPut(word) { ConcurrentHashMap() }
            list.forEachIndexed { index, nextWord ->
                nextMap[nextWord] = 200 - (index * 15)
            }
        }
    }

    suspend fun loadDictionary() = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // Load bundled frequency dictionary from resources or default word list
            val resId = try {
                val id = context.resources.getIdentifier("google_10k_english", "raw", context.packageName)
                if (id != 0) id else context.resources.getIdentifier("hermit_dave_en_50k", "raw", context.packageName)
            } catch (e: Exception) {
                0
            }

            if (resId != 0) {
                context.resources.openRawResource(resId).use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        var line: String?
                        var rank = 1
                        while (reader.readLine().also { line = it } != null) {
                            val trimmed = line?.trim()?.lowercase() ?: continue
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                            
                            val parts = trimmed.split(Regex("\\s+"))
                            val word = parts[0]
                            val freq = if (parts.size > 1) {
                                parts[1].toIntOrNull() ?: (255 - min(250, rank / 100))
                            } else {
                                (255 - min(250, rank / 40)).coerceAtLeast(10)
                            }
                            insertWord(word, freq)
                            rank++
                        }
                    }
                }
            } else {
                // Fallback basic English core lexicon
                loadBuiltInLexicon()
            }
            isReady = true
            val elapsed = System.currentTimeMillis() - startTime
            logKeeper.log("INFO", "HeliBoardBinaryDict", "Loaded ${exactWordSet.size} words in ${elapsed}ms")
        } catch (e: Exception) {
            logKeeper.log("ERROR", "HeliBoardBinaryDict", "Error loading dictionary: ${e.message}")
            loadBuiltInLexicon()
            isReady = true
        }
    }

    private fun loadBuiltInLexicon() {
        val coreWords = listOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with",
            "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if",
            "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "no", "just",
            "him", "know", "take", "people", "into", "year", "your", "good", "some", "could", "them", "see",
            "other", "than", "then", "now", "look", "only", "come", "its", "over", "think", "also", "back",
            "after", "use", "two", "how", "our", "work", "first", "well", "way", "even", "new", "want", "because",
            "any", "these", "give", "day", "most", "us", "hello", "thanks", "please", "keyboard", "android",
            "message", "phone", "today", "tomorrow", "tonight", "morning", "night", "sorry", "great", "awesome",
            "nice", "love", "happy", "where", "why", "here", "help", "need", "send", "call", "home", "work"
        )
        coreWords.forEachIndexed { index, word ->
            insertWord(word, (255 - min(200, index * 2)).coerceAtLeast(20))
        }
    }

    fun insertWord(word: String, frequency: Int) {
        val cleanWord = word.lowercase().trim()
        if (cleanWord.isEmpty()) return
        exactWordSet.add(cleanWord)

        var curr = root
        for (ch in cleanWord) {
            if (curr.children == null) {
                curr.children = HashMap(4)
            }
            var next = curr.children!![ch]
            if (next == null) {
                next = CompactNode()
                curr.children!![ch] = next
            }
            curr = next
        }
        curr.isTerminal = true
        curr.frequency = curr.frequency.coerceAtLeast(frequency)
    }

    override fun isValidWord(word: String): Boolean {
        return exactWordSet.contains(word.lowercase().trim())
    }

    override fun getFrequency(word: String): Int {
        val clean = word.lowercase().trim()
        var curr = root
        for (ch in clean) {
            curr = curr.children?.get(ch) ?: return 0
        }
        return if (curr.isTerminal) curr.frequency else 0
    }

    /**
     * Calculates spatial key distance on standard QWERTY keyboard.
     * Returns 0.0 for identical characters, 1.0 for immediately adjacent keys, up to ~9.0 for opposite keys.
     */
    fun getSpatialDistance(c1: Char, c2: Char): Float {
        if (c1 == c2) return 0f
        val p1 = qwertyCoords[c1.lowercaseChar()] ?: return 3.0f
        val p2 = qwertyCoords[c2.lowercaseChar()] ?: return 3.0f
        val dx = p1.second - p2.second
        val dy = p1.first - p2.first
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * HeliBoard fuzzy proximity scoring algorithm.
     */
    fun calculateWordProximityScore(typed: String, candidate: String): Int {
        val t = typed.lowercase()
        val c = candidate.lowercase()
        if (t == c) return 1000

        val maxLen = maxOf(t.length, c.length)
        if (abs(t.length - c.length) > 2) return 0

        // Levenshtein edit distance with spatial weights
        var totalPenalty = 0f
        var matchCount = 0

        var i = 0
        var j = 0
        while (i < t.length && j < c.length) {
            val charT = t[i]
            val charC = c[j]
            if (charT == charC) {
                matchCount++
                i++
                j++
            } else {
                // Check if transposition (e.g. "teh" -> "the")
                if (i + 1 < t.length && j + 1 < c.length && t[i] == c[j + 1] && t[i + 1] == c[j]) {
                    totalPenalty += 1.0f
                    i += 2
                    j += 2
                    matchCount += 2
                } else {
                    val dist = getSpatialDistance(charT, charC)
                    totalPenalty += if (dist <= 1.5f) (0.8f * dist) else 2.5f
                    i++
                    j++
                }
            }
        }
        totalPenalty += (t.length - i) * 2.0f + (c.length - j) * 1.5f

        val score = ((1.0f - (totalPenalty / (maxLen * 2.0f))).coerceIn(0f, 1f) * 800).toInt()
        return score
    }

    override fun getSuggestions(
        composedWord: String,
        prevWord: String?,
        prevPrevWord: String?,
        limit: Int
    ): List<SuggestedWordInfo> {
        val query = composedWord.lowercase().trim()
        if (query.isEmpty()) return emptyList()

        val results = mutableListOf<SuggestedWordInfo>()
        val seen = HashSet<String>()

        // 1. Check exact match
        if (exactWordSet.contains(query)) {
            val freq = getFrequency(query)
            val bigramBoost = getBigramBoost(query, prevWord, prevPrevWord)
            val score = 800 + freq + bigramBoost
            results.add(
                SuggestedWordInfo(
                    word = composedWord,
                    score = score,
                    kind = SuggestedWordInfo.Kind.TYPED,
                    sourceDict = dictType
                )
            )
            seen.add(query)
        }

        // 2. Prefix search from trie
        val prefixMatches = mutableListOf<Pair<String, Int>>()
        findPrefixMatches(root, query, StringBuilder(), prefixMatches, limit = 40)

        for ((word, freq) in prefixMatches) {
            if (seen.contains(word)) continue
            val bigramBoost = getBigramBoost(word, prevWord, prevPrevWord)
            val prefixBonus = if (word.startsWith(query)) 150 else 0
            val score = 500 + freq + prefixBonus + bigramBoost
            results.add(
                SuggestedWordInfo(
                    word = matchOriginalCase(composedWord, word),
                    score = score,
                    kind = SuggestedWordInfo.Kind.SUGGESTION,
                    sourceDict = dictType
                )
            )
            seen.add(word)
        }

        // 3. Proximity / Error Correction Search if query length >= 3
        if (query.length >= 3 && results.size < limit) {
            val fuzzyCandidates = findFuzzyCandidates(query, limit = 15)
            for ((word, proximityScore) in fuzzyCandidates) {
                if (seen.contains(word)) continue
                val freq = getFrequency(word)
                val bigramBoost = getBigramBoost(word, prevWord, prevPrevWord)
                val totalScore = proximityScore + freq + bigramBoost
                val kind = if (proximityScore >= 600) SuggestedWordInfo.Kind.CORRECTION else SuggestedWordInfo.Kind.SUGGESTION
                results.add(
                    SuggestedWordInfo(
                        word = matchOriginalCase(composedWord, word),
                        score = totalScore,
                        kind = kind,
                        sourceDict = dictType
                    )
                )
                seen.add(word)
            }
        }

        // Sort by total score descending
        results.sortByDescending { it.score }
        return results.take(limit)
    }

    private fun findPrefixMatches(
        node: CompactNode,
        query: String,
        currentPath: StringBuilder,
        outList: MutableList<Pair<String, Int>>,
        limit: Int
    ) {
        // Navigate down query path
        var curr = node
        for (ch in query) {
            curr = curr.children?.get(ch) ?: return
            currentPath.append(ch)
        }
        collectAllDescendants(curr, currentPath, outList, limit)
    }

    private fun collectAllDescendants(
        node: CompactNode,
        currentPath: StringBuilder,
        outList: MutableList<Pair<String, Int>>,
        limit: Int
    ) {
        if (outList.size >= limit) return
        if (node.isTerminal) {
            outList.add(Pair(currentPath.toString(), node.frequency))
        }
        node.children?.forEach { (ch, child) ->
            if (outList.size >= limit) return@forEach
            currentPath.append(ch)
            collectAllDescendants(child, currentPath, outList, limit)
            currentPath.setLength(currentPath.length - 1)
        }
    }

    private fun findFuzzyCandidates(query: String, limit: Int): List<Pair<String, Int>> {
        val candidates = mutableListOf<Pair<String, Int>>()
        // Score words that have similar length
        for (w in exactWordSet) {
            if (abs(w.length - query.length) <= 1) {
                val score = calculateWordProximityScore(query, w)
                if (score > 350) {
                    candidates.add(Pair(w, score))
                }
            }
        }
        candidates.sortByDescending { it.second }
        return candidates.take(limit)
    }

    private fun getBigramBoost(word: String, prevWord: String?, prevPrevWord: String?): Int {
        var boost = 0
        if (prevWord != null) {
            val p = prevWord.lowercase()
            val bigramFreq = bigrams[p]?.get(word.lowercase()) ?: 0
            boost += (bigramFreq * 2)

            if (prevPrevWord != null) {
                val trigramKey = "${prevPrevWord.lowercase()}_$p"
                val trigramFreq = trigrams[trigramKey]?.get(word.lowercase()) ?: 0
                boost += (trigramFreq * 3)
            }
        }
        return boost.coerceAtMost(300)
    }

    override fun getNextWordPredictions(
        prevWord: String,
        prevPrevWord: String?,
        limit: Int
    ): List<SuggestedWordInfo> {
        val p = prevWord.lowercase().trim()
        if (p.isEmpty()) return emptyList()

        val results = mutableListOf<SuggestedWordInfo>()
        val seen = HashSet<String>()

        // 1. Trigram context
        if (prevPrevWord != null) {
            val trigramKey = "${prevPrevWord.lowercase().trim()}_$p"
            trigrams[trigramKey]?.entries?.sortedByDescending { it.value }?.forEach { entry ->
                results.add(
                    SuggestedWordInfo(
                        word = entry.key,
                        score = 400 + entry.value * 2,
                        kind = SuggestedWordInfo.Kind.PREDICTION,
                        sourceDict = dictType
                    )
                )
                seen.add(entry.key)
            }
        }

        // 2. Bigram context
        bigrams[p]?.entries?.sortedByDescending { it.value }?.forEach { entry ->
            if (!seen.contains(entry.key)) {
                results.add(
                    SuggestedWordInfo(
                        word = entry.key,
                        score = 300 + entry.value,
                        kind = SuggestedWordInfo.Kind.PREDICTION,
                        sourceDict = dictType
                    )
                )
                seen.add(entry.key)
            }
        }

        results.sortByDescending { it.score }
        return results.take(limit)
    }

    override fun recordWordUsage(word: String, prevWord: String?) {
        val w = word.lowercase().trim()
        if (w.isEmpty()) return

        exactWordSet.add(w)
        val currentFreq = getFrequency(w)
        insertWord(w, (currentFreq + 1).coerceAtMost(255))

        if (prevWord != null) {
            val p = prevWord.lowercase().trim()
            val nextMap = bigrams.getOrPut(p) { ConcurrentHashMap() }
            val prevFreq = nextMap[w] ?: 0
            nextMap[w] = (prevFreq + 10).coerceAtMost(255)
        }
    }

    private fun matchOriginalCase(source: String, candidate: String): String {
        if (source.isEmpty() || candidate.isEmpty()) return candidate
        if (source.all { it.isUpperCase() }) return candidate.uppercase()
        if (source[0].isUpperCase()) {
            return candidate.replaceFirstChar { it.uppercaseChar() }
        }
        return candidate.lowercase()
    }

    override fun close() {
        // Clear memory mappings and caches
        exactWordSet.clear()
        bigrams.clear()
        trigrams.clear()
    }
}
