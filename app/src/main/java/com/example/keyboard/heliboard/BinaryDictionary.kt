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
 * spatial QWERTY proximity scoring, n-gram probability calculations, and multilingual support.
 */
class BinaryDictionary(
    private val context: Context,
    override val dictType: String = "main",
    var currentLanguage: String = "en"
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

    private var root = CompactNode()
    private val bigrams = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val trigrams = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val exactWordSet = ConcurrentHashMap.newKeySet<String>()

    // Standard QWERTY layout key coordinates (row, col) for spatial proximity calculation
    private val qwertyCoords = mapOf(
        'q' to Pair(0f, 0f), 'w' to Pair(0f, 1f), 'e' to Pair(0f, 2f), 'r' to Pair(0f, 3f), 't' to Pair(0f, 4f),
        'y' to Pair(0f, 5f), 'u' to Pair(0f, 6f), 'i' to Pair(0f, 7f), 'o' to Pair(0f, 8f), 'p' to Pair(0f, 9f),
        'a' to Pair(1f, 0.5f), 's' to Pair(1f, 1.5f), 'd' to Pair(1f, 2.5f), 'f' to Pair(1f, 3.5f), 'g' to Pair(1f, 4.5f),
        'h' to Pair(1f, 5.5f), 'j' to Pair(1f, 6.5f), 'k' to Pair(1f, 7.5f), 'l' to Pair(1f, 8.5f),
        'z' to Pair(2f, 1.5f), 'x' to Pair(2f, 2.5f), 'c' to Pair(2f, 3.5f), 'v' to Pair(2f, 4.5f), 'b' to Pair(2f, 5.5f),
        'n' to Pair(2f, 6.5f), 'm' to Pair(2f, 7.5f)
    )

    fun loadFromBinaryStream(stream: java.io.InputStream) {
        try {
            java.io.BufferedReader(java.io.InputStreamReader(stream)).use { reader ->
                var line: String?
                var rank = 1
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim()?.lowercase() ?: continue
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val parts = trimmed.split(Regex("\\s+"))
                    val word = parts[0]
                    val freq = if (parts.size > 1) parts[1].toIntOrNull() ?: (255 - kotlin.math.min(200, rank * 2)).coerceAtLeast(10)
                               else (255 - kotlin.math.min(200, rank * 2)).coerceAtLeast(10)
                    insertWord(word, freq)
                    rank++
                }
            }
            logKeeper.log("INFO", "BinaryDictionary", "Loaded binary stream lexicon ($dictType) | total words: ${exactWordSet.size}")
        } catch (e: Exception) {
            logKeeper.log("ERROR", "BinaryDictionary", "Error parsing stream dict: ${e.message}")
        }
    }

    suspend fun loadDictionary(languageCode: String = currentLanguage) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        currentLanguage = languageCode
        root = CompactNode()
        exactWordSet.clear()
        bigrams.clear()
        trigrams.clear()

        try {
            if (languageCode == "en") {
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
                    loadBuiltInLexicon(languageCode)
                }
            } else {
                loadBuiltInLexicon(languageCode)
            }
            isReady = true
            val elapsed = System.currentTimeMillis() - startTime
            logKeeper.log("INFO", "HeliBoardBinaryDict", "Loaded $dictType ($languageCode) ${exactWordSet.size} words in ${elapsed}ms")
        } catch (e: Exception) {
            logKeeper.log("ERROR", "HeliBoardBinaryDict", "Error loading dictionary ($languageCode): ${e.message}")
            loadBuiltInLexicon(languageCode)
            isReady = true
        }
    }

    private fun loadBuiltInLexicon(languageCode: String) {
        when (languageCode) {
            "fr" -> {
                val frenchWords = listOf(
                    "bonjour", "merci", "oui", "non", "beaucoup", "vous", "faire", "avec", "pour", "dans",
                    "tout", "plus", "bien", "temps", "homme", "femme", "monde", "jour", "autre", "bon",
                    "nouveau", "heure", "chose", "vie", "enfant", "grand", "petit", "premier", "voir", "pouvoir",
                    "aller", "vouloir", "venir", "dire", "avoir", "etre", "salut", "comment", "pourquoi", "quand",
                    "qui", "quoi", "maintenant", "toujours", "jamais", "trop", "tres", "ici", "la", "ami",
                    "maison", "travail", "demain", "aujourd'hui", "soir", "matin", "nuit", "aide", "message",
                    "france", "francais", "bonne", "notre", "votre", "leur", "aussi", "peut", "faire", "donner"
                )
                frenchWords.forEachIndexed { idx, word ->
                    insertWord(word, (255 - min(200, idx * 2)).coerceAtLeast(25))
                }
                // French bigrams
                bigrams.getOrPut("bonjour") { ConcurrentHashMap() }["a"] = 100
                bigrams.getOrPut("bonjour") { ConcurrentHashMap() }["tout"] = 90
                bigrams.getOrPut("merci") { ConcurrentHashMap() }["beaucoup"] = 120
                bigrams.getOrPut("s'il") { ConcurrentHashMap() }["vous"] = 110
                bigrams.getOrPut("vous") { ConcurrentHashMap() }["plait"] = 110
                bigrams.getOrPut("comment") { ConcurrentHashMap() }["allez"] = 90
                bigrams.getOrPut("je") { ConcurrentHashMap() }["suis"] = 100
                bigrams.getOrPut("je") { ConcurrentHashMap() }["vais"] = 95
            }
            "es" -> {
                val spanishWords = listOf(
                    "hola", "gracias", "por", "favor", "bueno", "amigo", "tiempo", "donde", "cuando", "hacer",
                    "todo", "bien", "ahora", "mundo", "dia", "casa", "vida", "hombre", "mujer", "nino",
                    "grande", "pequeno", "primero", "ver", "poder", "ir", "querer", "venir", "decir", "tener",
                    "estar", "ser", "saludos", "como", "porque", "quien", "que", "siempre", "nunca", "mucho",
                    "muy", "aqui", "alli", "trabajo", "manana", "hoy", "noche", "tarde", "ayuda", "mensaje",
                    "espanol", "espana", "buenos", "dias", "noches", "hasta", "luego", "pronto", "adios", "usted"
                )
                spanishWords.forEachIndexed { idx, word ->
                    insertWord(word, (255 - min(200, idx * 2)).coerceAtLeast(25))
                }
                bigrams.getOrPut("hola") { ConcurrentHashMap() }["amigo"] = 100
                bigrams.getOrPut("hola") { ConcurrentHashMap() }["como"] = 95
                bigrams.getOrPut("muchas") { ConcurrentHashMap() }["gracias"] = 120
                bigrams.getOrPut("por") { ConcurrentHashMap() }["favor"] = 120
                bigrams.getOrPut("buenos") { ConcurrentHashMap() }["dias"] = 110
                bigrams.getOrPut("buenas") { ConcurrentHashMap() }["noches"] = 105
                bigrams.getOrPut("como") { ConcurrentHashMap() }["estas"] = 110
            }
            "de" -> {
                val germanWords = listOf(
                    "hallo", "danke", "bitte", "ja", "nein", "gut", "freund", "zeit", "wo", "wann",
                    "machen", "alles", "jetzt", "welt", "tag", "haus", "leben", "mann", "frau", "kind",
                    "gross", "klein", "erste", "sehen", "konnen", "gehen", "wollen", "kommen", "sagen", "haben",
                    "sein", "wie", "warum", "wer", "was", "immer", "nie", "sehr", "viel", "hier",
                    "dort", "arbeit", "morgen", "heute", "nacht", "abend", "hilfe", "nachricht", "deutsch", "deutschland"
                )
                germanWords.forEachIndexed { idx, word ->
                    insertWord(word, (255 - min(200, idx * 2)).coerceAtLeast(25))
                }
                bigrams.getOrPut("vielen") { ConcurrentHashMap() }["dank"] = 120
                bigrams.getOrPut("guten") { ConcurrentHashMap() }["tag"] = 110
                bigrams.getOrPut("guten") { ConcurrentHashMap() }["morgen"] = 105
                bigrams.getOrPut("wie") { ConcurrentHashMap() }["geht"] = 100
            }
            "it" -> {
                val italianWords = listOf(
                    "ciao", "grazie", "prego", "si", "no", "buono", "amico", "tempo", "dove", "quando",
                    "fare", "tutto", "bene", "adesso", "mondo", "giorno", "casa", "vita", "uomo", "donna",
                    "bambino", "grande", "piccolo", "primo", "vedere", "potere", "andare", "volere", "venire", "dire",
                    "avere", "essere", "come", "perche", "chi", "cosa", "sempre", "mai", "molto", "qui",
                    "la", "lavoro", "domani", "oggi", "notte", "sera", "aiuto", "messaggio", "italiano", "italia"
                )
                italianWords.forEachIndexed { idx, word ->
                    insertWord(word, (255 - min(200, idx * 2)).coerceAtLeast(25))
                }
                bigrams.getOrPut("grazie") { ConcurrentHashMap() }["mille"] = 120
                bigrams.getOrPut("buon") { ConcurrentHashMap() }["giorno"] = 110
                bigrams.getOrPut("buona") { ConcurrentHashMap() }["sera"] = 105
                bigrams.getOrPut("come") { ConcurrentHashMap() }["stai"] = 100
            }
            "pt" -> {
                val portugueseWords = listOf(
                    "ola", "obrigado", "por", "favor", "sim", "nao", "bom", "amigo", "tempo", "onde",
                    "quando", "fazer", "tudo", "bem", "agora", "mundo", "dia", "casa", "vida", "homem",
                    "mulher", "crianca", "grande", "pequeno", "primeiro", "ver", "poder", "ir", "querer", "vir",
                    "dizer", "ter", "estar", "ser", "como", "porque", "quem", "que", "sempre", "nunca",
                    "muito", "aqui", "ali", "trabalho", "amanha", "hoje", "noite", "tarde", "ajuda", "mensagem"
                )
                portugueseWords.forEachIndexed { idx, word ->
                    insertWord(word, (255 - min(200, idx * 2)).coerceAtLeast(25))
                }
                bigrams.getOrPut("muito") { ConcurrentHashMap() }["obrigado"] = 120
                bigrams.getOrPut("bom") { ConcurrentHashMap() }["dia"] = 110
                bigrams.getOrPut("boa") { ConcurrentHashMap() }["noite"] = 105
                bigrams.getOrPut("tudo") { ConcurrentHashMap() }["bem"] = 110
            }
            else -> {
                // English fallback
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
                bigrams.getOrPut("how") { ConcurrentHashMap() }["are"] = 100
                bigrams.getOrPut("are") { ConcurrentHashMap() }["you"] = 120
                bigrams.getOrPut("thank") { ConcurrentHashMap() }["you"] = 120
                bigrams.getOrPut("good") { ConcurrentHashMap() }["morning"] = 110
                bigrams.getOrPut("good") { ConcurrentHashMap() }["night"] = 105
                bigrams.getOrPut("see") { ConcurrentHashMap() }["you"] = 90
            }
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

    fun getSpatialDistance(c1: Char, c2: Char): Float {
        if (c1 == c2) return 0f
        val p1 = qwertyCoords[c1.lowercaseChar()] ?: return 3.0f
        val p2 = qwertyCoords[c2.lowercaseChar()] ?: return 3.0f
        val dx = p1.second - p2.second
        val dy = p1.first - p2.first
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun calculateWordProximityScore(typed: String, candidate: String): Int {
        val t = typed.lowercase()
        val c = candidate.lowercase()
        if (t == c) return 1000

        val maxLen = maxOf(t.length, c.length)
        if (abs(t.length - c.length) > 2) return 0

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

    fun removeWord(word: String) {
        val clean = word.lowercase().trim()
        if (clean.isEmpty()) return
        exactWordSet.remove(clean)
        var curr = root
        for (char in clean) {
            val next = curr.children?.get(char) ?: return
            curr = next
        }
        curr.isTerminal = false
        curr.frequency = 0
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
        exactWordSet.clear()
        bigrams.clear()
        trigrams.clear()
    }
}
