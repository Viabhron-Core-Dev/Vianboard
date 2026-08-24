package com.example.keyboard.desktop

import android.content.Context
import androidx.core.content.edit

data class DesktopShortcutItem(
    val id: String,
    val name: String,
    val label: String,
    val sublabel: String,
    val actionCode: String
)

object DesktopShortcutsManager {
    private const val PREFS_NAME = "DesktopShortcutsPrefs"
    private const val PREF_SHORTCUT_KEYS = "enabled_desktop_shortcuts"

    const val MAX_CUSTOM_SHORTCUTS = 6

    val ALL_AVAILABLE_SHORTCUTS = listOf(
        DesktopShortcutItem("SELECT_ALL", "Select All", "ALL", "Ctrl+A", "DSK_SELALL"),
        DesktopShortcutItem("COPY", "Copy", "COPY", "Ctrl+C", "DSK_COPY"),
        DesktopShortcutItem("PASTE", "Paste", "PASTE", "Ctrl+V", "DSK_PASTE"),
        DesktopShortcutItem("CUT", "Cut", "CUT", "Ctrl+X", "DSK_CUT"),
        DesktopShortcutItem("UNDO", "Undo", "UNDO", "Ctrl+Z", "DSK_UNDO"),
        DesktopShortcutItem("REDO", "Redo", "REDO", "Ctrl+Y", "DSK_REDO"),
        DesktopShortcutItem("HOME", "Home (Line Start)", "HOME", "Home", "DSK_HOME"),
        DesktopShortcutItem("END", "End (Line End)", "END", "End", "DSK_END"),
        DesktopShortcutItem("PGUP", "Page Up", "PG UP", "PgUp", "DSK_PGUP"),
        DesktopShortcutItem("PGDN", "Page Down", "PG DN", "PgDn", "DSK_PGDN"),
        DesktopShortcutItem("TAB", "Tab Key", "TAB", "Tab", "DSK_TAB"),
        DesktopShortcutItem("DEL", "Forward Delete", "DEL", "FwdDel", "DSK_DEL"),
        DesktopShortcutItem("ESC", "Escape", "ESC", "Esc", "DSK_ESC"),
        DesktopShortcutItem("SELWORD", "Select Word", "SEL WRD", "Word", "DSK_SELWORD"),
        DesktopShortcutItem("FIND", "Find / Replace", "FIND", "Ctrl+H", "DSK_FINDREPLACE"),
        DesktopShortcutItem("SAVE", "Save Document", "SAVE", "Ctrl+S", "DSK_SAVE")
    )

    // Default is only arrows, so custom list is empty by default
    val DEFAULT_SHORTCUTS = emptyList<String>()

    fun getEnabledShortcuts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_SHORTCUT_KEYS, null) ?: return DEFAULT_SHORTCUTS
        return saved.split(",").filter { it.isNotEmpty() }
    }

    fun saveEnabledShortcuts(context: Context, shortcuts: List<String>) {
        val limited = shortcuts.take(MAX_CUSTOM_SHORTCUTS)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_SHORTCUT_KEYS, limited.joinToString(",")) }
    }

    fun getShortcutItem(id: String): DesktopShortcutItem? {
        return ALL_AVAILABLE_SHORTCUTS.find { it.id == id }
    }
}
