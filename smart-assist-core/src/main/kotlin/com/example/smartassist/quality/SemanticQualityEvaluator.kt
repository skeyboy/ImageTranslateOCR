package com.example.smartassist.quality

import kotlin.math.max

data class SemanticGoldenSample(
    val id: String,
    val expectedOcr: String,
    val actualOcr: String,
    val acceptedTranslations: List<String>,
    val actualTranslation: String,
    val expectedBounds: QualityBounds? = null,
    val actualBounds: QualityBounds? = null
) {
    init {
        require(id.isNotBlank())
        require(expectedOcr.isNotBlank())
        require(acceptedTranslations.isNotEmpty())
        require(acceptedTranslations.none(String::isBlank))
    }
}

data class QualityBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(right > left && bottom > top)
    }
}

data class SemanticSampleScore(
    val id: String,
    val characterErrorRate: Float,
    val wordErrorRate: Float,
    val translationSimilarity: Float,
    val boundsIntersectionOverUnion: Float?
)

data class SemanticQualityReport(
    val sampleCount: Int,
    val meanCharacterErrorRate: Float,
    val meanWordErrorRate: Float,
    val meanTranslationSimilarity: Float,
    val exactTranslationRate: Float,
    val meanBoundsIntersectionOverUnion: Float?,
    val samples: List<SemanticSampleScore>
)

object SemanticQualityEvaluator {
    fun evaluate(samples: List<SemanticGoldenSample>): SemanticQualityReport {
        require(samples.isNotEmpty()) { "Semantic quality requires at least one sample" }
        require(samples.map(SemanticGoldenSample::id).distinct().size == samples.size) {
            "Semantic sample ids must be unique"
        }
        val scores = samples.map(::score)
        val boundedScores = scores.mapNotNull(SemanticSampleScore::boundsIntersectionOverUnion)
        return SemanticQualityReport(
            sampleCount = scores.size,
            meanCharacterErrorRate = scores.map(SemanticSampleScore::characterErrorRate).averageFloat(),
            meanWordErrorRate = scores.map(SemanticSampleScore::wordErrorRate).averageFloat(),
            meanTranslationSimilarity = scores.map(SemanticSampleScore::translationSimilarity)
                .averageFloat(),
            exactTranslationRate = samples.count { sample ->
                val actual = normalize(sample.actualTranslation)
                sample.acceptedTranslations.any { normalize(it) == actual }
            }.toFloat() / samples.size,
            meanBoundsIntersectionOverUnion = boundedScores.takeIf(List<Float>::isNotEmpty)
                ?.averageFloat(),
            samples = scores
        )
    }

    fun score(sample: SemanticGoldenSample): SemanticSampleScore {
        val expectedOcr = normalize(sample.expectedOcr)
        val actualOcr = normalize(sample.actualOcr)
        val characterErrorRate = editDistance(expectedOcr.toList(), actualOcr.toList()).toFloat() /
            expectedOcr.length.coerceAtLeast(1)
        val expectedWords = semanticTokens(expectedOcr)
        val actualWords = semanticTokens(actualOcr)
        val wordErrorRate = editDistance(expectedWords, actualWords).toFloat() /
            expectedWords.size.coerceAtLeast(1)
        val normalizedActualTranslation = normalize(sample.actualTranslation)
        val translationSimilarity = sample.acceptedTranslations.maxOf { expected ->
            similarity(normalize(expected), normalizedActualTranslation)
        }
        return SemanticSampleScore(
            id = sample.id,
            characterErrorRate = characterErrorRate,
            wordErrorRate = wordErrorRate,
            translationSimilarity = translationSimilarity,
            boundsIntersectionOverUnion = boundsIntersectionOverUnion(
                sample.expectedBounds,
                sample.actualBounds
            )
        )
    }

    private fun semanticTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens += latin.toString()
                latin.clear()
            }
        }
        text.lowercase().forEach { character ->
            when {
                Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN -> {
                    flushLatin()
                    tokens += character.toString()
                }
                character.isLetterOrDigit() -> latin.append(character)
                else -> flushLatin()
            }
        }
        flushLatin()
        return tokens
    }

    private fun similarity(expected: String, actual: String): Float {
        val denominator = max(expected.length, actual.length).coerceAtLeast(1)
        return (1f - editDistance(expected.toList(), actual.toList()).toFloat() / denominator)
            .coerceIn(0f, 1f)
    }

    private fun boundsIntersectionOverUnion(
        expected: QualityBounds?,
        actual: QualityBounds?
    ): Float? {
        if (expected == null || actual == null) return null
        val width = (minOf(expected.right, actual.right) - maxOf(expected.left, actual.left))
            .coerceAtLeast(0)
        val height = (minOf(expected.bottom, actual.bottom) - maxOf(expected.top, actual.top))
            .coerceAtLeast(0)
        val intersection = width.toLong() * height
        val expectedArea = (expected.right - expected.left).toLong() *
            (expected.bottom - expected.top)
        val actualArea = (actual.right - actual.left).toLong() * (actual.bottom - actual.top)
        val union = expectedArea + actualArea - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union
    }

    private fun normalize(text: String): String = text.trim()
        .replace(WHITESPACE, " ")
        .replace(SPACE_BEFORE_PUNCTUATION, "$1")

    private fun <T> editDistance(first: List<T>, second: List<T>): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.size
        if (second.isEmpty()) return first.size
        var previous = IntArray(second.size + 1) { it }
        first.forEachIndexed { firstIndex, firstValue ->
            val current = IntArray(second.size + 1)
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondValue ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (firstValue == secondValue) 0 else 1
                )
            }
            previous = current
        }
        return previous[second.size]
    }

    private fun List<Float>.averageFloat(): Float = sum() / size.coerceAtLeast(1)

    private val WHITESPACE = Regex("\\s+")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,.;:!?，。；：！？])")
}
