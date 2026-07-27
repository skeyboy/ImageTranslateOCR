package com.example.imagetranslate.screenshot

internal data class LiveTextQualityMetrics(
    val sourceLatinTokenCount: Int,
    val retainedLatinTokenCount: Int,
    val retainedLatinRatio: Float,
    val suspiciousJoinCount: Int
)

internal object LiveTextQualityPolicy {
    private val latinTokenRegex = Regex("[A-Za-z][A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)*")
    private val suspiciousJoinRegex = Regex("[a-z][A-Z]")
    private val allowedTechnicalTokens = setOf(
        "api", "cargo", "devops", "firefox", "http", "https", "ide", "rust", "url"
    )

    fun measure(pairs: List<Pair<String, String>>): LiveTextQualityMetrics {
        val sourceTokens = pairs.flatMap { (source, _) -> eligibleTokens(source) }
        val sourceVocabulary = sourceTokens.toSet()
        val retainedCount = pairs.sumOf { (_, translation) ->
            eligibleTokens(translation).count(sourceVocabulary::contains)
        }.coerceAtMost(sourceTokens.size)
        val suspiciousJoins = pairs.sumOf { (_, translation) ->
            suspiciousJoinRegex.findAll(translation).count()
        }
        return LiveTextQualityMetrics(
            sourceLatinTokenCount = sourceTokens.size,
            retainedLatinTokenCount = retainedCount,
            retainedLatinRatio = if (sourceTokens.isEmpty()) {
                0f
            } else {
                retainedCount.toFloat() / sourceTokens.size
            },
            suspiciousJoinCount = suspiciousJoins
        )
    }

    private fun eligibleTokens(text: String): List<String> = latinTokenRegex.findAll(text)
        .map { it.value.lowercase() }
        .filter { it.length > 1 && it !in allowedTechnicalTokens }
        .toList()
}
