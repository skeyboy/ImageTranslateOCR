package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.translate.MachineTranslationParagraphResult

internal class MachinePasteBackProvider {
    fun createRegions(
        paragraphs: List<MachineParagraph>,
        results: List<MachineTranslationParagraphResult>
    ): List<BackgroundImageRegion> {
        val paragraphsById = paragraphs.associateBy(MachineParagraph::paragraphId)
        return results.mapNotNull { result ->
            val paragraph = paragraphsById[result.paragraph.paragraphId] ?: return@mapNotNull null
            val translated = result.translatedText?.trim()
                ?.takeIf { it.isNotEmpty() && it != paragraph.sourceText.trim() }
                ?: return@mapNotNull null
            BackgroundImageRegion(
                source = paragraph.asRecognizedText(),
                translation = paragraph.displayTranslation(translated),
                groupId = paragraph.paragraphId,
                renderSlots = listOf(Rect(paragraph.bounds)),
                sourceCoverSlots = paragraph.sourceCoverSlots.map(::Rect),
                layoutRole = if (paragraph.kind == MachineParagraphKind.LIST_ITEM) {
                    "LIST_ITEM"
                } else {
                    null
                },
                preservePasteBackBoundary = paragraph.kind == MachineParagraphKind.LIST_ITEM
            )
        }
    }
}
