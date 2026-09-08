package com.example.imagetranslate.translate

import com.example.imagetranslate.semantic.SemanticTextGroup
import com.example.imagetranslate.semantic.SemanticTextRole
import com.example.imagetranslate.semantic.SemanticContentClassifier

internal fun SemanticTextGroup.toSemanticTranslationSource(): SemanticTranslationSource {
    val atomicMembers = members.flatMapIndexed { memberIndex, member ->
        member.toAtomicTranslationMembers(memberIndex)
    }
    val normalizedMembers = atomicMembers.map { member ->
        OcrTranslationTextNormalizer.normalize(member.text)
    }
    val regionIds = atomicMembers.indices.map { index -> "$groupId-region-$index" }
    val regions = atomicMembers.mapIndexed { index, member ->
        val normalized = normalizedMembers[index]
        SemanticTranslationRegion(
            regionId = regionIds[index],
            groupId = groupId,
            sourceRevision = 1L,
            text = normalized.text,
            sourceLanguage = languageFor(normalized.text),
            targetLanguage = targetLanguageFor(normalized.text),
            readingOrder = readingOrder * 1_000 + index,
            blockId = member.sourceBlockId,
            lineIndex = member.sourceLineIndex,
            confidence = member.modelConfidence.coerceIn(0f, 1f),
            bounds = member.bounds.toTranslationBounds(),
            componentBounds = member.componentBounds.map { it.toTranslationBounds() },
            rawText = member.text,
            corrections = normalized.corrections,
            estimatedTextHeightPx = member.estimatedTextHeightPx,
            typographyConfidence = member.typographyConfidence
        )
    }
    val normalizedSourceText = normalizedMembers.joinToString("\n") { it.text }
    return SemanticTranslationSource(
        groupId = groupId,
        role = role.name,
        translationUnit = if (
            SemanticContentClassifier.shouldPreserve(role.name, normalizedSourceText)
        ) {
            "PRESERVED"
        } else {
            "GROUP"
        },
        sourceText = normalizedSourceText,
        memberRegionIds = regionIds,
        readingOrder = readingOrder,
        groupingConfidence = groupingConfidence.coerceIn(0f, 1f),
        groupingEvidence = evidence.map { it.name }.sorted(),
        bounds = unionBounds.toTranslationBounds(),
        regions = regions,
        sourceLineCount = atomicMembers.sumOf { member ->
            maxOf(1, member.componentBounds.size, member.text.lineSequence().count())
        },
        renderSlots = renderSlots.map { it.toTranslationBounds() },
        layoutShape = if (renderSlots.size > 1) "FLOW_SLOTS" else "RECT"
    )
}

private data class AtomicTranslationMember(
    val text: String,
    val bounds: android.graphics.Rect,
    val componentBounds: List<android.graphics.Rect>,
    val sourceBlockId: String?,
    val sourceLineIndex: Int?,
    val modelConfidence: Float,
    val estimatedTextHeightPx: Float?,
    val typographyConfidence: Float
)

private fun com.example.imagetranslate.ocr.RecognizedText.toAtomicTranslationMembers(
    memberIndex: Int
): List<AtomicTranslationMember> {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val components = componentBounds
        .filter { it.right > it.left && it.bottom > it.top }
        .sortedWith(compareBy({ it.top }, { it.left }))
    if (lines.size > 1 && lines.size == components.size) {
        return lines.mapIndexed { lineOffset, line ->
            AtomicTranslationMember(
                text = line,
                bounds = copyRect(components[lineOffset]),
                componentBounds = emptyList(),
                sourceBlockId = sourceBlockId,
                sourceLineIndex = sourceLineIndex?.plus(lineOffset) ?: lineOffset,
                modelConfidence = modelConfidence,
                estimatedTextHeightPx = componentTextHeightsPx.getOrNull(lineOffset)
                    ?: estimatedTextHeightPx,
                typographyConfidence = typographyConfidence
            )
        }
    }
    return listOf(
        AtomicTranslationMember(
            text = text.trim(),
            bounds = copyRect(bounds),
            componentBounds = components.map(::copyRect),
            sourceBlockId = sourceBlockId,
            sourceLineIndex = sourceLineIndex ?: memberIndex,
            modelConfidence = modelConfidence,
            estimatedTextHeightPx = estimatedTextHeightPx,
            typographyConfidence = typographyConfidence
        )
    )
}

private fun copyRect(source: android.graphics.Rect) = android.graphics.Rect().apply {
    left = source.left
    top = source.top
    right = source.right
    bottom = source.bottom
}

private fun android.graphics.Rect.toTranslationBounds() = TranslationBounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom
)

private fun languageFor(text: String): String? =
    TranslationScriptLanguagePolicy.sourceLanguage(text)

private fun targetLanguageFor(text: String): String? = when (languageFor(text)) {
    "zh" -> "en"
    "en" -> "zh"
    else -> null
}
