package com.example.imagetranslate.translate

internal object TranslationScriptLanguagePolicy {
    private val webLiteral = Regex(
        "(?i)(?:(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})"
    )
    private val latinWord = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

    fun sourceLanguage(text: String): String? {
        val naturalText = webLiteral.replace(text, " ")
        val hanCount = naturalText.count(::isHanCharacter)
        val latinWordCount = latinWord.findAll(naturalText).count()
        return when {
            hanCount > 0 && latinWordCount > hanCount -> "en"
            hanCount > 0 -> "zh"
            latinWordCount > 0 || webLiteral.containsMatchIn(text) -> "en"
            else -> null
        }
    }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
}
