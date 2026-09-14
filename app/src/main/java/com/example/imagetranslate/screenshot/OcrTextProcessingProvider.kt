package com.example.imagetranslate.screenshot

import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.semantic.SemanticTextGrouper
import com.example.imagetranslate.semantic.SemanticTextGroup

internal sealed interface OcrTextProcessingUnit

internal data class MachineOcrTextProcessingUnit(
    val paragraph: MachineParagraph
) : OcrTextProcessingUnit

internal data class AiOcrTextProcessingUnit(
    val groups: List<SemanticTextGroup>
) : OcrTextProcessingUnit

internal data class OcrTextProcessingResult(
    val sourceItems: List<RecognizedText>,
    val units: List<OcrTextProcessingUnit>
)

internal interface OcrTextProcessingProvider {
    fun process(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): OcrTextProcessingResult
}

internal class AiOcrTextProcessingProvider : OcrTextProcessingProvider {
    override fun process(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): OcrTextProcessingResult {
        val groups = SemanticTextGrouper.group(
            recognized = recognized,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )
        return OcrTextProcessingResult(
            sourceItems = recognized,
            units = listOf(AiOcrTextProcessingUnit(groups))
        )
    }
}
