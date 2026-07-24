package com.example.imagetranslate.screenshot

internal enum class ScreenshotResultInteraction {
    IGNORE,
    TRANSLATE,
    TRANSLATE_AND_VIEW,
    TRANSLATE_AND_SAVE
}

internal object ScreenshotNotificationInteractionPolicy {
    val contentInteraction = ScreenshotResultInteraction.IGNORE
    val dismissInteraction = ScreenshotResultInteraction.IGNORE
    val actionInteractions = listOf(
        ScreenshotResultInteraction.TRANSLATE,
        ScreenshotResultInteraction.TRANSLATE_AND_VIEW,
        ScreenshotResultInteraction.TRANSLATE_AND_SAVE
    )

    val allInteractions: Set<ScreenshotResultInteraction>
        get() = buildSet {
            add(contentInteraction)
            add(dismissInteraction)
            addAll(actionInteractions)
        }
}
