package com.example.imagetranslate.screenshot

import com.example.imagetranslate.semantic.SemanticContentClassifier
import com.example.imagetranslate.semantic.SemanticTextGroup
import com.example.imagetranslate.semantic.SemanticTextRole

internal data class LiveTranslationGroupSelection(
    val selected: List<SemanticTextGroup>,
    val dropped: List<SemanticTextGroup>
)

internal object LiveTranslationGroupSelectionPolicy {
    fun select(
        groups: List<SemanticTextGroup>,
        maximumGroups: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ): LiveTranslationGroupSelection {
        if (maximumGroups <= 0 || groups.isEmpty()) {
            return LiveTranslationGroupSelection(emptyList(), groups)
        }
        val (preserved, actionable) = groups.partition { group ->
            SemanticContentClassifier.shouldPreserve(group.role.name, group.sourceText)
        }
        if (actionable.size <= maximumGroups) {
            return LiveTranslationGroupSelection(groups, emptyList())
        }

        val selectedIds = actionable
            .sortedWith(
                compareByDescending<SemanticTextGroup> { group ->
                    priority(group, viewportWidth, viewportHeight)
                }.thenBy(SemanticTextGroup::readingOrder)
            )
            .take(maximumGroups)
            .map(SemanticTextGroup::groupId)
            .toSet() + preserved.map(SemanticTextGroup::groupId)
        return LiveTranslationGroupSelection(
            selected = groups.filter { it.groupId in selectedIds },
            dropped = groups.filterNot { it.groupId in selectedIds }
        )
    }

    private fun priority(
        group: SemanticTextGroup,
        viewportWidth: Int,
        viewportHeight: Int
    ): Int {
        val width = (group.unionBounds.right - group.unionBounds.left).coerceAtLeast(1)
        val height = (group.unionBounds.bottom - group.unionBounds.top).coerceAtLeast(1)
        val widthScore = width * 1_000 / viewportWidth.coerceAtLeast(1)
        val heightScore = (height * 600 / viewportHeight.coerceAtLeast(1)).coerceAtMost(300)
        val characterScore = group.sourceText.count(Char::isLetterOrDigit).coerceAtMost(240) * 2
        val lineCount = group.members.sumOf { member ->
            maxOf(1, member.componentBounds.size, member.text.lineSequence().count())
        }
        val lineScore = lineCount.coerceAtMost(8) * 70
        val confidenceScore = (
            group.members.map { it.modelConfidence }.average().coerceIn(0.0, 1.0) * 100
            ).toInt()
        val roleScore = when (group.role) {
            SemanticTextRole.TITLE -> 260
            SemanticTextRole.BODY -> 220
            SemanticTextRole.LIST_ITEM -> 200
            SemanticTextRole.METADATA -> 40
            SemanticTextRole.TIMESTAMP -> 20
            SemanticTextRole.CONTROL,
            SemanticTextRole.CODE,
            SemanticTextRole.IDENTIFIER -> 0
        }
        val continuationScore = if (group.members.any {
                it.continuationAtTop || it.continuationAtBottom
            }
        ) {
            220
        } else {
            0
        }
        return roleScore + widthScore + heightScore + characterScore + lineScore +
            confidenceScore + continuationScore
    }
}
