package com.example.imagetranslate.translate

internal object SemanticFallbackPolicy {
    fun allowsLocalFallback(
        source: SemanticTranslationSource,
        viewportWidth: Int,
        viewportHeight: Int
    ): Boolean {
        if (source.translationUnit == "PRESERVED") return true
        if (source.role != "BODY") return true
        val compactCharacters = source.sourceText.count { !it.isWhitespace() }
        val groupArea = (source.bounds.right - source.bounds.left).toLong() *
            (source.bounds.bottom - source.bounds.top)
        val viewportArea = viewportWidth.toLong().coerceAtLeast(1) *
            viewportHeight.toLong().coerceAtLeast(1)
        return source.regions.size < MINIMUM_LONG_BODY_REGIONS &&
            compactCharacters < MINIMUM_LONG_BODY_CHARACTERS &&
            groupArea.toDouble() / viewportArea < MINIMUM_LONG_BODY_AREA_RATIO
    }

    private const val MINIMUM_LONG_BODY_REGIONS = 4
    private const val MINIMUM_LONG_BODY_CHARACTERS = 60
    private const val MINIMUM_LONG_BODY_AREA_RATIO = 0.03
}
