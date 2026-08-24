package com.example.imagetranslate.semantic

internal object SemanticContentClassifier {
    fun isStandaloneTemporalValue(text: String): Boolean =
        STANDALONE_TEMPORAL.matches(normalize(text))

    fun isStandaloneMetadata(text: String): Boolean {
        val normalized = normalize(text)
        return isStandaloneTemporalValue(normalized) ||
            SENDER_METADATA.matches(normalized) || looksLikeAuthorDateMetadata(normalized) ||
            isDiscussionThreadMetadata(normalized)
    }

    fun isDiscussionThreadMetadata(text: String): Boolean {
        val normalized = normalize(text).lowercase()
        val hasAge = "minute ago" in normalized || "minutes ago" in normalized
        val hasNavigation = "parent" in normalized && "context" in normalized
        val hasSubject = "on:" in normalized || "on：" in normalized
        return hasAge && hasNavigation && hasSubject
    }

    fun shouldPreserve(role: String, text: String): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        if (visible.isEmpty()) return true
        if (looksLikeCode(visible) || looksLikeBrandGroup(visible)) return true

        return when (role) {
            "CODE", "IDENTIFIER", "CONTROL" -> true
            "TIMESTAMP" -> isStandaloneTemporalValue(text)
            "METADATA" -> isStandaloneMetadata(text) && !isDiscussionThreadMetadata(text)
            else -> isStandaloneNumericIdentifier(visible)
        }
    }

    private fun isStandaloneNumericIdentifier(visible: String): Boolean {
        if (visible.none(Char::isDigit) || visible.any(::isHanCharacter)) return false
        val latinRuns = LATIN_RUN.findAll(visible).map(MatchResult::value).toList()
        return latinRuns.all { run -> run.length == 1 }
    }

    private fun looksLikeCode(visible: String): Boolean = visible.contains("//") ||
        visible.contains('_') || visible.contains('@') ||
        visible.matches(Regex("[A-Za-z]+://.*"))

    private fun looksLikeBrandGroup(visible: String): Boolean =
        visible.any { it in 'A'..'Z' || it in 'a'..'z' } &&
            visible.any { it in charArrayOf('×', '©', '®', '™') }

    private fun looksLikeAuthorDateMetadata(text: String): Boolean {
        val date = AUTHOR_DATE_SUFFIX.find(text) ?: return false
        if (date.range.last != text.lastIndex) return false
        val author = text.substring(0, date.range.first).trim()
            .removePrefix("By ")
            .removePrefix("by ")
            .trim()
        val tokens = author.split(' ').filter(String::isNotBlank)
        if (tokens.isEmpty() || tokens.size > MAXIMUM_AUTHOR_TOKENS) return false
        return tokens.all { token ->
            val letters = token.filter(Char::isLetter)
            letters.isNotEmpty() && (
                letters.lowercase() in LOWERCASE_NAME_PARTICLES ||
                    letters.first().isUpperCase()
                )
        }
    }

    private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private val MONTH =
        "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|" +
            "jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|" +
            "dec(?:ember)?)"
    private val CLOCK = "(?:[01]?\\d|2[0-3]):[0-5]\\d(?:[:][0-5]\\d)?(?:\\s*[AP]M)?"
    private val ISO_DATE =
        "\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}(?:[ T]$CLOCK)?"
    private val TEXT_DATE =
        "(?:\\d{1,2}\\s+$MONTH\\s+\\d{4}|" +
            "$MONTH\\s+\\d{1,2}(?:st|nd|rd|th)?[,]?\\s+\\d{4})"
    private val STANDALONE_TEMPORAL = Regex(
        "^(?:$CLOCK|$ISO_DATE|$TEXT_DATE)$",
        RegexOption.IGNORE_CASE
    )
    private val SENDER_METADATA = Regex("^\\s*[~-]\\s*[\\p{L}][\\p{L} ._-]{0,30}$")
    private val AUTHOR_DATE_SUFFIX = Regex(
        "(?:\\d{1,2}\\s+$MONTH\\s+\\d{4}|" +
            "$MONTH\\s+\\d{1,2}(?:st|nd|rd|th)?[,]?\\s+\\d{4})$",
        RegexOption.IGNORE_CASE
    )
    private val LATIN_RUN = Regex("[A-Za-z]+")
    private val WHITESPACE = Regex("\\s+")
    private val LOWERCASE_NAME_PARTICLES = setOf(
        "and", "bin", "da", "de", "del", "la", "van", "von"
    )
    private const val MAXIMUM_AUTHOR_TOKENS = 8
}
