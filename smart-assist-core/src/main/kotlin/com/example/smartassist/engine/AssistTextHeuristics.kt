package com.example.smartassist.engine

import com.example.smartassist.api.AssistRect
import com.example.smartassist.api.AssistScene
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistTextTrack
import com.example.smartassist.api.AssistTrackRole
import kotlin.math.abs

internal object AssistTextHeuristics {
    private val urlPattern = Regex(
        pattern = "(?i)(https?://|www\\.|[a-z0-9][a-z0-9.-]+\\.(com|org|net|io|dev|cn)(/|$))"
    )
    private val codeKeywordPattern = Regex(
        pattern = "\\b(class|fun|val|var|return|import|package|const|function|def|try|catch)\\b"
    )
    private val actionPattern = Regex(
        pattern = "(?i)^(ok|cancel|save|delete|edit|next|back|done|submit|login|sign in|add|remove|open|close|确认|取消|保存|删除|编辑|下一步|返回|完成|提交|登录|添加|移除|打开|关闭)$"
    )
    private val commercePattern = Regex(
        pattern = "(?i)([$€£¥￥]\\s?\\d|\\d\\s?元|add to cart|buy now|checkout|购物车|立即购买|结算)"
    )
    private val chatPattern = Regex(
        pattern = "(?i)(^\\d{1,2}:\\d{2}$|typing…?$|online$|已读$|正在输入|发送$)"
    )

    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    fun detectScript(text: String): AssistScript {
        var han = 0
        var latin = 0
        var digits = 0
        text.forEach { character ->
            when (Character.UnicodeScript.of(character.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.LATIN -> latin++
                else -> if (character.isDigit()) digits++
            }
        }
        return when {
            han > 0 && latin > 0 -> AssistScript.MIXED
            han > 0 -> AssistScript.HAN
            latin > 0 -> AssistScript.LATIN
            digits > 0 -> AssistScript.NUMERIC
            else -> AssistScript.UNKNOWN
        }
    }

    fun isUrl(text: String): Boolean = urlPattern.containsMatchIn(normalize(text))

    fun isCode(text: String): Boolean {
        val normalized = normalize(text)
        if (codeKeywordPattern.containsMatchIn(normalized)) return true
        val codeSignals = listOf("=>", "==", "!=", "();", "{}", "[]", "</", "/>" )
            .count(normalized::contains)
        return codeSignals > 0 ||
            (normalized.count { it in "{}();=" } >= 3 && normalized.any(Char::isLetter))
    }

    fun classifyRole(
        track: AssistTextTrack,
        medianHeight: Int,
        viewportHeight: Int
    ): AssistTrackRole {
        track.roleHint?.let { return it }
        val text = normalize(track.text)
        return when {
            isUrl(text) -> AssistTrackRole.URL
            isCode(text) -> AssistTrackRole.CODE
            actionPattern.matches(text) -> AssistTrackRole.ACTION
            track.bounds.top < viewportHeight / 4 &&
                track.bounds.height >= (medianHeight * 1.2f) && text.length <= 80 -> {
                AssistTrackRole.TITLE
            }
            text.length >= 28 -> AssistTrackRole.BODY
            text.endsWith(":") || text.endsWith("：") -> AssistTrackRole.LABEL
            text.length <= 24 -> AssistTrackRole.LABEL
            else -> AssistTrackRole.UNKNOWN
        }
    }

    fun classifyScene(tracks: List<AssistTextTrack>): AssistScene {
        if (tracks.isEmpty()) return AssistScene.UNKNOWN
        val texts = tracks.map { normalize(it.text) }
        val codeCount = texts.count(::isCode)
        if (codeCount >= 2 || codeCount.toFloat() / tracks.size >= 0.4f) return AssistScene.CODE
        if (texts.any(commercePattern::containsMatchIn)) return AssistScene.COMMERCE
        if (texts.count(chatPattern::containsMatchIn) >= 2) return AssistScene.CHAT

        val meaningfulCharacters = texts.sumOf { text -> text.count(Char::isLetterOrDigit) }
        val longLineCount = texts.count { it.length >= 28 }
        if (meaningfulCharacters >= 120 || longLineCount >= 2) return AssistScene.READING

        val shortLineRatio = texts.count { it.length <= 24 }.toFloat() / texts.size
        if (tracks.size >= 3 && shortLineRatio >= 0.65f) return AssistScene.SETTINGS
        return AssistScene.UNKNOWN
    }

    fun scriptCompatible(original: AssistScript, replacement: String): Boolean {
        val replacementScript = detectScript(replacement)
        if (original == AssistScript.UNKNOWN || original == AssistScript.MIXED) return true
        if (replacementScript == AssistScript.UNKNOWN) return false
        return original == replacementScript ||
            (original == AssistScript.LATIN && replacementScript == AssistScript.NUMERIC) ||
            (original == AssistScript.NUMERIC && replacementScript == AssistScript.LATIN)
    }

    fun editRatio(first: String, second: String): Float {
        val normalizedFirst = normalize(first)
        val normalizedSecond = normalize(second)
        val denominator = maxOf(normalizedFirst.length, normalizedSecond.length).coerceAtLeast(1)
        return editDistance(normalizedFirst, normalizedSecond).toFloat() / denominator
    }

    fun verticalOverlap(first: AssistRect, second: AssistRect): Int =
        (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)

    fun isLeftAligned(first: AssistRect, second: AssistRect): Boolean =
        abs(first.left - second.left) <= maxOf(24, maxOf(first.height, second.height))

    private fun editDistance(first: String, second: String): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        first.forEachIndexed { firstIndex, firstCharacter ->
            val current = IntArray(second.length + 1)
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondCharacter ->
                val substitution = previous[secondIndex] +
                    if (firstCharacter == secondCharacter) 0 else 1
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    substitution
                )
            }
            previous = current
        }
        return previous[second.length]
    }
}
