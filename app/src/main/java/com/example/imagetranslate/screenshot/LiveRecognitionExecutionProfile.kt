package com.example.imagetranslate.screenshot

internal enum class LiveDifferentialContextProfile(
    val continuationHeightRatio: Float,
    val maximumRecognitionAreaRatio: Float
) {
    BALANCED(continuationHeightRatio = 0.07f, maximumRecognitionAreaRatio = 0.72f),
    ACCURACY(continuationHeightRatio = 0.12f, maximumRecognitionAreaRatio = 0.6f)
}

internal enum class LivePatchRenderingMode {
    SEQUENTIAL,
    PARALLEL
}

internal data class LiveRecognitionExecutionProfile(
    val contextProfile: LiveDifferentialContextProfile,
    val renderingMode: LivePatchRenderingMode
) {
    companion object {
        val CURRENT = LiveRecognitionExecutionProfile(
            contextProfile = LiveDifferentialContextProfile.ACCURACY,
            renderingMode = LivePatchRenderingMode.PARALLEL
        )

        val CANDIDATE = LiveRecognitionExecutionProfile(
            contextProfile = LiveDifferentialContextProfile.ACCURACY,
            renderingMode = LivePatchRenderingMode.PARALLEL
        )
    }
}
