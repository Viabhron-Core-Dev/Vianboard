package com.example.keyboard.heliboard

/**
 * Encapsulates information about a single suggested word from HeliBoard's suggestion engine.
 */
data class SuggestedWordInfo(
    val word: String,
    val prevWordsContext: String = "",
    val score: Int = 0,
    val kind: Kind = Kind.CORRECTION,
    val sourceDict: String = "main",
    val indexInSuggestions: Int = 0
) {
    enum class Kind {
        TYPED,          // The literal characters typed by user
        CORRECTION,     // Strong auto-correct candidate
        SUGGESTION,     // Prefix completion or prediction
        PREDICTION,     // Next-word prediction from n-gram context
        SHORTCUT,       // Personal dictionary shortcut expansion
        USER_TYPED      // Word learned from user history
    }

    val isAutoCorrectionCandidate: Boolean
        get() = kind == Kind.CORRECTION || (kind == Kind.PREDICTION && score > 200)

    val isShortcut: Boolean
        get() = kind == Kind.SHORTCUT
}
