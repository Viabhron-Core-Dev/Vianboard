package com.example.keyboard.backup

import android.content.Context
import android.net.Uri
import com.example.data.AppDatabase
import com.example.data.BigramEntity
import com.example.data.WordEntity
import com.example.keyboard.ClipboardDatabase
import com.example.keyboard.ClipboardItem
import com.example.keyboard.DictionaryWordEntity
import com.example.keyboard.PersonalDictionaryItem
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupStats(
    val clipboardCount: Int = 0,
    val personalDictCount: Int = 0,
    val dictWordsCount: Int = 0,
    val wordsCount: Int = 0,
    val bigramsCount: Int = 0,
    val prefsCount: Int = 0,
    val timestamp: Long = 0
)

object BackupRestoreManager {

    private const val BACKUP_VERSION = 1

    suspend fun createBackupJson(context: Context): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("createdDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("appName", "Viaboard")

        // 1. Clipboard Items
        val clipDb = ClipboardDatabase.getDatabase(context)
        val clipDao = clipDb.clipboardDao()
        val clips = clipDao.getAllSync()
        val clipsArray = JSONArray()
        for (item in clips) {
            val clipObj = JSONObject()
            clipObj.put("id", item.id)
            clipObj.put("text", item.text)
            clipObj.put("timestamp", item.timestamp)
            clipObj.put("isPinned", item.isPinned)
            clipObj.put("isSensitive", item.isSensitive)
            clipsArray.put(clipObj)
        }
        root.put("clipboard_items", clipsArray)

        // 2. Personal Dictionary & Prompt Items
        val personalDao = clipDb.personalDictionaryDao()
        val personalItems = personalDao.getAllSync()
        val personalArray = JSONArray()
        for (p in personalItems) {
            val pObj = JSONObject()
            pObj.put("id", p.id)
            pObj.put("word", p.word)
            pObj.put("shortcut", p.shortcut ?: "")
            pObj.put("frequency", p.frequency)
            pObj.put("createdAt", p.createdAt)
            personalArray.put(pObj)
        }
        root.put("personal_dictionary", personalArray)

        // 3. Learned Dictionary Words
        val dictWordDao = clipDb.dictionaryWordDao()
        val dictWords = dictWordDao.getAllWordsSync()
        val dictWordsArray = JSONArray()
        for (dw in dictWords) {
            val dwObj = JSONObject()
            dwObj.put("word", dw.word)
            dwObj.put("frequency", dw.frequency)
            dwObj.put("source", dw.source)
            dictWordsArray.put(dwObj)
        }
        root.put("dictionary_words", dictWordsArray)

        // 4. AppDatabase Words & Bigrams
        try {
            val appDb = AppDatabase.getDatabase(context)
            val words = appDb.wordDao().getAllWordsSync()
            val wordsArray = JSONArray()
            for (w in words) {
                val wObj = JSONObject()
                wObj.put("word", w.word)
                wObj.put("frequency", w.frequency)
                wordsArray.put(wObj)
            }
            root.put("words", wordsArray)

            val bigrams = appDb.wordDao().getAllBigramsSync()
            val bigramsArray = JSONArray()
            for (b in bigrams) {
                val bObj = JSONObject()
                bObj.put("word1", b.word1)
                bObj.put("word2", b.word2)
                bObj.put("frequency", b.frequency)
                bigramsArray.put(bObj)
            }
            root.put("bigrams", bigramsArray)
        } catch (e: Exception) {
            TheLogKeeper.getInstance(context).log("WARN", "BackupManager", "Could not backup AppDb: ${e.message}")
        }

        // 5. SharedPreferences
        val prefsObj = JSONObject()
        val prefNames = listOf(
            "keyboard_prefs",
            "toolbar_prefs",
            "desktop_shortcuts_prefs",
            "longpress_prefs",
            "emoji_recents_prefs",
            "theme_prefs"
        )
        for (prefName in prefNames) {
            val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val spMap = sp.all
            val currentPrefObj = JSONObject()
            for ((key, value) in spMap) {
                when (value) {
                    is Boolean -> currentPrefObj.put(key, value)
                    is Int -> currentPrefObj.put(key, value)
                    is Long -> currentPrefObj.put(key, value)
                    is Float -> currentPrefObj.put(key, value.toDouble())
                    is String -> currentPrefObj.put(key, value)
                    is Set<*> -> {
                        val setArr = JSONArray()
                        for (s in value) setArr.put(s.toString())
                        currentPrefObj.put(key, setArr)
                    }
                }
            }
            prefsObj.put(prefName, currentPrefObj)
        }
        root.put("preferences", prefsObj)

        TheLogKeeper.getInstance(context).log("INFO", "BackupManager", "BACKUP_CREATED | clips=${clips.size} | personal=${personalItems.size}")
        root.toString(2)
    }

    suspend fun writeBackupToUri(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = createBackupJson(context)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out, "UTF-8").use { writer ->
                    writer.write(json)
                    writer.flush()
                }
            }
            TheLogKeeper.getInstance(context).log("INFO", "BackupManager", "BACKUP_EXPORTED_SUCCESS | uri=$uri")
            Result.success(Unit)
        } catch (e: Exception) {
            TheLogKeeper.getInstance(context).log("ERROR", "BackupManager", "BACKUP_EXPORT_FAILED | err=${e.message}")
            Result.failure(e)
        }
    }

    suspend fun restoreBackupFromUri(context: Context, uri: Uri): Result<BackupStats> = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line).append("\n")
                    }
                }
            }

            val jsonStr = sb.toString()
            val root = JSONObject(jsonStr)

            var clipCount = 0
            var personalCount = 0
            var dictWordsCount = 0
            var wordsCount = 0
            var bigramsCount = 0
            var prefsCount = 0

            val clipDb = ClipboardDatabase.getDatabase(context)

            // 1. Restore Clipboard
            if (root.has("clipboard_items")) {
                val clipsArray = root.getJSONArray("clipboard_items")
                val clipDao = clipDb.clipboardDao()
                for (i in 0 until clipsArray.length()) {
                    val obj = clipsArray.getJSONObject(i)
                    val item = ClipboardItem(
                        id = obj.optLong("id", 0L),
                        text = obj.getString("text"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned", false),
                        isSensitive = obj.optBoolean("isSensitive", false)
                    )
                    clipDao.insertSync(item)
                    clipCount++
                }
            }

            // 2. Restore Personal Dictionary & Prompts
            if (root.has("personal_dictionary")) {
                val personalArray = root.getJSONArray("personal_dictionary")
                val personalDao = clipDb.personalDictionaryDao()
                for (i in 0 until personalArray.length()) {
                    val obj = personalArray.getJSONObject(i)
                    val shortcut = obj.optString("shortcut", "")
                    val item = PersonalDictionaryItem(
                        id = obj.optInt("id", 0),
                        word = obj.getString("word"),
                        shortcut = if (shortcut.isBlank()) null else shortcut,
                        frequency = obj.optInt("frequency", 250),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                    personalDao.insertSync(item)
                    personalCount++
                }
            }

            // 3. Restore Dictionary Words
            if (root.has("dictionary_words")) {
                val dwArray = root.getJSONArray("dictionary_words")
                val dwDao = clipDb.dictionaryWordDao()
                for (i in 0 until dwArray.length()) {
                    val obj = dwArray.getJSONObject(i)
                    val entity = DictionaryWordEntity(
                        word = obj.getString("word"),
                        frequency = obj.optInt("frequency", 1),
                        source = obj.optString("source", "imported")
                    )
                    dwDao.insertWordSync(entity)
                    dictWordsCount++
                }
            }

            // 4. Restore AppDatabase Words & Bigrams
            try {
                val appDb = AppDatabase.getDatabase(context)
                if (root.has("words")) {
                    val wordsArray = root.getJSONArray("words")
                    for (i in 0 until wordsArray.length()) {
                        val obj = wordsArray.getJSONObject(i)
                        val w = WordEntity(
                            word = obj.getString("word"),
                            frequency = obj.optInt("frequency", 1)
                        )
                        appDb.wordDao().insertSync(w)
                        wordsCount++
                    }
                }
                if (root.has("bigrams")) {
                    val bigramsArray = root.getJSONArray("bigrams")
                    for (i in 0 until bigramsArray.length()) {
                        val obj = bigramsArray.getJSONObject(i)
                        val b = BigramEntity(
                            word1 = obj.getString("word1"),
                            word2 = obj.getString("word2"),
                            frequency = obj.optInt("frequency", 1)
                        )
                        appDb.wordDao().insertBigramSync(b)
                        bigramsCount++
                    }
                }
            } catch (e: Exception) {
                TheLogKeeper.getInstance(context).log("WARN", "BackupManager", "Error restoring AppDb: ${e.message}")
            }

            // 5. Restore SharedPreferences
            if (root.has("preferences")) {
                val prefsObj = root.getJSONObject("preferences")
                val keys = prefsObj.keys()
                while (keys.hasNext()) {
                    val prefName = keys.next()
                    val targetObj = prefsObj.getJSONObject(prefName)
                    val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val editor = sp.edit()
                    val targetKeys = targetObj.keys()
                    while (targetKeys.hasNext()) {
                        val k = targetKeys.next()
                        val v = targetObj.get(k)
                        when (v) {
                            is Boolean -> editor.putBoolean(k, v)
                            is Int -> editor.putInt(k, v)
                            is Long -> editor.putLong(k, v)
                            is Double -> editor.putFloat(k, v.toFloat())
                            is String -> editor.putString(k, v)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (j in 0 until v.length()) set.add(v.getString(j))
                                editor.putStringSet(k, set)
                            }
                        }
                        prefsCount++
                    }
                    editor.apply()
                }
            }

            val stats = BackupStats(
                clipboardCount = clipCount,
                personalDictCount = personalCount,
                dictWordsCount = dictWordsCount,
                wordsCount = wordsCount,
                bigramsCount = bigramsCount,
                prefsCount = prefsCount,
                timestamp = root.optLong("createdAt", System.currentTimeMillis())
            )

            TheLogKeeper.getInstance(context).log(
                "INFO",
                "BackupManager",
                "RESTORE_COMPLETE | clips=$clipCount | personal=$personalCount | dictWords=$dictWordsCount | prefs=$prefsCount"
            )

            Result.success(stats)
        } catch (e: Exception) {
            TheLogKeeper.getInstance(context).log("ERROR", "BackupManager", "RESTORE_FAILED | err=${e.message}")
            Result.failure(e)
        }
    }
}
