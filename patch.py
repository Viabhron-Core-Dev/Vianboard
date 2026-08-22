with open("app/src/main/java/com/example/keyboard/ViaboardService.kt", "r") as f:
    content = f.read()

old_func = """    private fun decideAutocorrect(typed: String, aggressiveness: Float): AutocorrectDecision {
        if (typed.isEmpty()) return AutocorrectDecision.None
        if (dictionaryEngine.wordExists(typed.lowercase())) return AutocorrectDecision.None

        val isProperNoun = typed[0].isUpperCase()
        val isIncognito = isIncognitoActive()"""

new_func = """    private fun decideAutocorrect(typed: String, aggressiveness: Float): AutocorrectDecision {
        if (typed.isEmpty()) return AutocorrectDecision.None
        if (dictionaryEngine.wordExists(typed.lowercase())) return AutocorrectDecision.None

        var isProperNoun = typed[0].isUpperCase()
        if (isProperNoun) {
            val ic = currentInputConnection
            if (ic != null) {
                val beforeCursor = ic.getTextBeforeCursor(wordLengthBeforeCursor + 30, 0)?.toString() ?: ""
                if (beforeCursor.length >= wordLengthBeforeCursor) {
                    val beforeWord = beforeCursor.dropLast(wordLengthBeforeCursor)
                    val trailingWhitespace = beforeWord.takeLastWhile { it.isWhitespace() }
                    val trimmed = beforeWord.trimEnd()
                    
                    if (beforeWord.isBlank() || trailingWhitespace.contains('\n')) {
                        isProperNoun = false
                    } else if (trailingWhitespace.isNotEmpty() && (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?"))) {
                        val beforePunctuation = trimmed.dropLast(1).trimEnd()
                        val lastWordBeforePunctuation = beforePunctuation.takeLastWhile { !it.isWhitespace() }
                        if (lastWordBeforePunctuation.length > 2) {
                            isProperNoun = false
                        }
                    }
                } else {
                    isProperNoun = false
                }
            }
        }
        val isIncognito = isIncognitoActive()"""

if old_func in content:
    content = content.replace(old_func, new_func)
    with open("app/src/main/java/com/example/keyboard/ViaboardService.kt", "w") as f:
        f.write(content)
    print("Patched")
else:
    print("Failed")
