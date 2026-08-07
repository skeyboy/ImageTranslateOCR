package com.example.imagetranslate.translate

internal data class OcrTextCorrection(
    val code: String,
    val original: String,
    val replacement: String
)

internal data class NormalizedOcrTranslationText(
    val text: String,
    val corrections: List<OcrTextCorrection>
)

internal object OcrTranslationTextNormalizer {
    fun normalize(rawText: String): NormalizedOcrTranslationText {
        var corrected = rawText
        val corrections = mutableListOf<OcrTextCorrection>()
        CURRENCY_PATTERNS.forEach { pattern ->
            corrected = pattern.regex.replace(corrected) { match ->
                val replacement = pattern.replacement + match.groupValues[1]
                corrections += OcrTextCorrection(
                    code = "CURRENCY_SYMBOL_RECOVERY",
                    original = match.value,
                    replacement = replacement
                )
                replacement
            }
        }
        return NormalizedOcrTranslationText(corrected, corrections)
    }

    private data class CurrencyPattern(val regex: Regex, val replacement: String)

    private val CURRENCY_PATTERNS = listOf(
        CurrencyPattern(
            Regex("(?i)\\bUSS(\\d+(?:[.,]\\d+)?)(?=\\s*(?:million|billion)\\b)"),
            "US\$"
        ),
        CurrencyPattern(
            Regex("(?i)\\bCS(\\d+(?:[.,]\\d+)?)(?=\\s*(?:million|billion)\\b)"),
            "C\$"
        )
    )
}
