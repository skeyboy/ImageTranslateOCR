package com.example.imagetranslate.translate

import com.google.mlkit.nl.translate.TranslateLanguage

internal data class RegionsFirstTranslationAcceptance(
    val accepted: Boolean,
    val reason: String? = null
)

internal object RegionsFirstTranslationAcceptancePolicy {
    fun evaluate(
        sources: List<SemanticTranslationSource>,
        result: SemanticGroupTranslationResult
    ): RegionsFirstTranslationAcceptance {
        val regionsById = sources.flatMap(SemanticTranslationSource::regions)
            .associateBy(SemanticTranslationRegion::regionId)
        val regions = result.memberRegionIds.mapNotNull(regionsById::get)
            .sortedBy(SemanticTranslationRegion::readingOrder)
        if (regions.isEmpty() || regions.size != result.memberRegionIds.size) {
            return rejected("OCR_REGION_LINEAGE_MISMATCH")
        }
        if (regions.size > 1 && result.groupingConfidence < MINIMUM_GROUPING_CONFIDENCE) {
            return rejected("GROUPING_CONFIDENCE_TOO_LOW")
        }
        val translated = result.translatedText?.trim().orEmpty()
        if (translated.isEmpty()) return rejected("EMPTY_TRANSLATION")
        val sourceText = regions.joinToString("\n") { it.text }.trim()
        if (result.status == TranslationResultStatus.PRESERVED) {
            return if (translated == sourceText) accepted() else rejected("PRESERVED_TEXT_CHANGED")
        }
        val targetLanguage = regions.mapNotNull(SemanticTranslationRegion::targetLanguage)
            .distinct().singleOrNull() ?: return rejected("TARGET_LANGUAGE_AMBIGUOUS")
        val responseTarget = result.targetLanguage
            ?.substringBefore('-')
            ?.lowercase()
        if (responseTarget != null && responseTarget != targetLanguage.substringBefore('-').lowercase()) {
            return rejected("TARGET_LANGUAGE_MISMATCH")
        }
        val visibleCount = translated.count { !it.isWhitespace() }
        val meaningfulCount = translated.count(Char::isLetterOrDigit)
        if (visibleCount == 0 || meaningfulCount.toFloat() / visibleCount < MINIMUM_MEANINGFUL_RATIO) {
            return rejected("INSUFFICIENT_MEANINGFUL_TEXT")
        }
        val hanCount = translated.count(::isHanCharacter)
        val latinCount = translated.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when (targetLanguage) {
            TranslateLanguage.CHINESE -> if (hanCount > 0) {
                accepted()
            } else {
                rejected("MISSING_CHINESE_OUTPUT")
            }
            TranslateLanguage.ENGLISH -> if (
                latinCount > 0 && hanCount.toFloat() / visibleCount <= MAXIMUM_HAN_RATIO_IN_ENGLISH
            ) {
                accepted()
            } else {
                rejected("INVALID_ENGLISH_OUTPUT")
            }
            else -> accepted()
        }
    }

    private fun accepted() = RegionsFirstTranslationAcceptance(accepted = true)

    private fun rejected(reason: String) = RegionsFirstTranslationAcceptance(
        accepted = false,
        reason = reason
    )

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private const val MINIMUM_GROUPING_CONFIDENCE = 0.90f
    private const val MINIMUM_MEANINGFUL_RATIO = 0.60f
    private const val MAXIMUM_HAN_RATIO_IN_ENGLISH = 0.10f
}
