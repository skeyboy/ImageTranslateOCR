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
                translation = translated,
                groupId = paragraph.paragraphId,
                renderSlots = listOf(Rect(paragraph.bounds)),
                sourceCoverSlots = paragraph.sourceCoverSlots.map(::Rect)
            )
        }
    }
}
