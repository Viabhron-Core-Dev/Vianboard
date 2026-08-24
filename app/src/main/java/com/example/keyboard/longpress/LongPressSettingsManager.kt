package com.example.keyboard.longpress

import android.content.Context
import androidx.core.content.edit

data class PopupAction(
    val id: String,
    val name: String,
    val symbol: String
)

object LongPressSettingsManager {
    private const val PREFS_NAME = "LongPressPrefs"
    
    private const val PREF_LONG_PRESS_DELAY = "long_press_delay"
    private const val PREF_COMMA_KEYS = "comma_popup_keys"
    private const val PREF_SYMBOLS_KEYS = "symbols_popup_keys"
    private const val PREF_URL_DOMAINS = "url_domains"
    private const val PREF_SHOW_HINTS = "show_key_hints"

    const val DEFAULT_LONG_PRESS_DELAY = 400 // ms

    val ALL_COMMA_ACTIONS = listOf(
        PopupAction("MODE_EMOJI", "Emoji", "🙂"),
        PopupAction("SETTINGS", "Settings", "⚙️"),
        PopupAction("CLIPBOARD", "Clipboard", "📋"),
        PopupAction("PROMPT_LIST", "Prompt List", "⚡"),
        PopupAction("ONE_HAND", "One-Handed Mode", "🗗"),
        PopupAction("MODE_NUMPAD", "Numpad", "1234"),
        PopupAction("DSK_SELALL", "Select All", "SelAll")
    )

    val ALL_SYMBOLS_ACTIONS = listOf(
        PopupAction("MODE_NUMPAD", "Numpad", "1234"),
        PopupAction("MODE_EMOJI", "Emoji", "🙂"),
        PopupAction("MODE_NAVIGATION", "Navigation", "⇦"),
        PopupAction("MODE_SYMBOLS_SHIFT", "Alt Symbols", "=\\<"),
        PopupAction("MODE_DESKTOP", "Desktop Mode", "PC"),
        PopupAction("PROMPT_LIST", "Prompt List", "⚡"),
        PopupAction("CLIPBOARD", "Clipboard", "📋"),
        PopupAction("SETTINGS", "Settings", "⚙️")
    )

    val ALL_URL_DOMAINS = listOf(
        PopupAction(".com", ".com", ".com"),
        PopupAction(".org", ".org", ".org"),
        PopupAction(".net", ".net", ".net"),
        PopupAction(".io", ".io", ".io"),
        PopupAction(".co", ".co", ".co"),
        PopupAction(".gov", ".gov", ".gov"),
        PopupAction(".edu", ".edu", ".edu"),
        PopupAction(".app", ".app", ".app"),
        PopupAction(".dev", ".dev", ".dev"),
        PopupAction(".xyz", ".xyz", ".xyz")
    )

    val DEFAULT_COMMA = listOf("MODE_EMOJI", "SETTINGS", "CLIPBOARD", "PROMPT_LIST")
    val DEFAULT_SYMBOLS = listOf("MODE_NUMPAD", "MODE_EMOJI", "MODE_NAVIGATION", "MODE_SYMBOLS_SHIFT", "MODE_DESKTOP", "PROMPT_LIST")
    val DEFAULT_URL_DOMAINS = listOf(".com", ".org", ".net", ".io", ".co", ".gov", ".edu")

    fun getLongPressDelay(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREF_LONG_PRESS_DELAY, DEFAULT_LONG_PRESS_DELAY)
    }

    fun saveLongPressDelay(context: Context, delay: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt(PREF_LONG_PRESS_DELAY, delay) }
    }

    fun getShowHints(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SHOW_HINTS, true)
    }

    fun saveShowHints(context: Context, show: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(PREF_SHOW_HINTS, show) }
    }

    fun getCommaKeys(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_COMMA_KEYS, null)
        return saved?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_COMMA
    }

    fun saveCommaKeys(context: Context, keys: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_COMMA_KEYS, keys.joinToString(",")) }
    }

    fun getSymbolsKeys(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_SYMBOLS_KEYS, null)
        return saved?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_SYMBOLS
    }

    fun saveSymbolsKeys(context: Context, keys: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_SYMBOLS_KEYS, keys.joinToString(",")) }
    }

    fun getUrlDomains(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_URL_DOMAINS, null)
        return saved?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_URL_DOMAINS
    }

    fun saveUrlDomains(context: Context, domains: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_URL_DOMAINS, domains.joinToString(",")) }
    }
}
