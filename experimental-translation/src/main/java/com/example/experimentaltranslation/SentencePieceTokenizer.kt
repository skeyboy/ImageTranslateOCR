package com.example.experimentaltranslation

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.Normalizer
import java.util.Properties

internal class SentencePieceTokenizer private constructor(
    private val pieces: List<Piece>,
    private val pieceVocabularyIds: IntArray,
    private val vocabularyPieces: List<String?>,
    private val unknownPieceId: Int,
    private val unknownVocabularyId: Int,
    val bosId: Int,
    val eosId: Int,
    val padId: Int
) {
    private data class Piece(val text: String, val score: Float, val type: Int)
    private data class Path(val score: Float, val previous: Int, val pieceId: Int)

    private val piecesByFirstCodePoint = pieces.indices
        .filter { pieces[it].text.isNotEmpty() && pieces[it].type != TYPE_UNKNOWN }
        .groupBy { pieces[it].text.codePointAt(0) }

    fun encode(text: String): LongArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return longArrayOf()
        val offsets = codePointOffsets(normalized)
        val best = arrayOfNulls<Path>(offsets.size)
        best[0] = Path(0f, -1, -1)
        for (position in 0 until offsets.lastIndex) {
            val current = best[position] ?: continue
            val charOffset = offsets[position]
            val firstCodePoint = normalized.codePointAt(charOffset)
            for (pieceId in piecesByFirstCodePoint[firstCodePoint].orEmpty()) {
                val piece = pieces[pieceId]
                if (!normalized.startsWith(piece.text, charOffset)) continue
                val nextCharOffset = charOffset + piece.text.length
                val nextPosition = offsets.binarySearch(nextCharOffset)
                if (nextPosition <= position) continue
                val candidateScore = current.score + piece.score
                if (best[nextPosition] == null || candidateScore > best[nextPosition]!!.score) {
                    best[nextPosition] = Path(candidateScore, position, pieceId)
                }
            }
            val nextPosition = position + 1
            val candidateScore = current.score + UNKNOWN_SCORE
            if (best[nextPosition] == null || candidateScore > best[nextPosition]!!.score) {
                best[nextPosition] = Path(candidateScore, position, unknownPieceId)
            }
        }
        val output = ArrayDeque<Long>()
        var cursor = offsets.lastIndex
        while (cursor > 0) {
            val path = best[cursor] ?: error("Unable to tokenize input at code point $cursor")
            output.addFirst(
                if (path.pieceId == unknownPieceId) unknownVocabularyId.toLong()
                else pieceVocabularyIds[path.pieceId].toLong()
            )
            cursor = path.previous
        }
        return output.toLongArray()
    }

    fun decode(ids: List<Int>): String {
        val joined = buildString {
            ids.forEach { id ->
                if (id == bosId || id == eosId || id == padId || id !in vocabularyPieces.indices) {
                    return@forEach
                }
                vocabularyPieces[id]?.let(::append)
            }
        }
        return joined.replace(SPACE_MARKER, " ").trim()
    }

    private fun normalize(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (normalized.isEmpty()) "" else "$SPACE_MARKER${normalized.replace(" ", SPACE_MARKER)}"
    }

    private fun codePointOffsets(text: String): IntArray {
        val offsets = ArrayList<Int>()
        var offset = 0
        offsets += offset
        while (offset < text.length) {
            offset += Character.charCount(text.codePointAt(offset))
            offsets += offset
        }
        return offsets.toIntArray()
    }

    companion object {
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_UNUSED = 5
        private const val UNKNOWN_SCORE = -1000f
        private const val SPACE_MARKER = "▁"

        fun load(
            modelFile: File,
            vocabularyFile: File,
            properties: Properties,
            prefix: String
        ): SentencePieceTokenizer {
            val pieces = parsePieces(modelFile.readBytes())
            check(pieces.isNotEmpty()) { "SentencePiece model contains no pieces" }
            val vocabularyType = object : TypeToken<Map<String, Int>>() {}.type
            val vocabulary: Map<String, Int> = vocabularyFile.reader().use { reader ->
                Gson().fromJson(reader, vocabularyType)
            }
            val unknownPieceId = pieces.indexOfFirst { it.type == TYPE_UNKNOWN }
            check(unknownPieceId >= 0) { "SentencePiece model has no unknown piece" }
            val unknownVocabularyId = properties.intProperty("${prefix}_unk_id", 1)
            val pieceVocabularyIds = IntArray(pieces.size) { pieceId ->
                vocabulary[pieces[pieceId].text] ?: unknownVocabularyId
            }
            val vocabularyPieces = MutableList<String?>(
                (vocabulary.values.maxOrNull() ?: 0) + 1
            ) { null }
            vocabulary.forEach { (piece, id) ->
                if (id in vocabularyPieces.indices) vocabularyPieces[id] = piece
            }
            return SentencePieceTokenizer(
                pieces = pieces,
                pieceVocabularyIds = pieceVocabularyIds,
                vocabularyPieces = vocabularyPieces,
                unknownPieceId = unknownPieceId,
                unknownVocabularyId = unknownVocabularyId,
                bosId = properties.intProperty("${prefix}_bos_id", 0),
                eosId = properties.intProperty("${prefix}_eos_id", 0),
                padId = properties.intProperty("${prefix}_pad_id", pieces.lastIndex)
            )
        }

        private fun parsePieces(bytes: ByteArray): List<Piece> {
            val reader = ProtoReader(bytes)
            val pieces = mutableListOf<Piece>()
            while (!reader.exhausted) {
                val tag = reader.readVarint().toInt()
                val field = tag ushr 3
                val wireType = tag and 7
                if (field == 1 && wireType == 2) {
                    pieces += parsePiece(reader.readBytes())
                } else {
                    reader.skip(wireType)
                }
            }
            return pieces
        }

        private fun parsePiece(bytes: ByteArray): Piece {
            val reader = ProtoReader(bytes)
            var text = ""
            var score = 0f
            var type = 1
            while (!reader.exhausted) {
                val tag = reader.readVarint().toInt()
                when (tag) {
                    10 -> text = reader.readBytes().toString(Charsets.UTF_8)
                    21 -> score = Float.fromBits(reader.readFixed32())
                    24 -> type = reader.readVarint().toInt()
                    else -> reader.skip(tag and 7)
                }
            }
            return Piece(text, score, type)
        }

        private fun Properties.intProperty(key: String, fallback: Int): Int =
            getProperty(key)?.trim()?.toIntOrNull() ?: fallback
    }
}

private class ProtoReader(private val bytes: ByteArray) {
    private var position = 0
    val exhausted: Boolean get() = position >= bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            check(position < bytes.size) { "Truncated protobuf varint" }
            val value = bytes[position++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result
            shift += 7
        }
        error("Invalid protobuf varint")
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        check(length >= 0 && position + length <= bytes.size) { "Invalid protobuf length" }
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun readFixed32(): Int {
        check(position + 4 <= bytes.size) { "Truncated protobuf fixed32" }
        val result = (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return result
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> position += 8
            2 -> {
                val length = readVarint().toInt()
                position += length
            }
            5 -> position += 4
            else -> error("Unsupported protobuf wire type $wireType")
        }
        check(position <= bytes.size) { "Truncated protobuf field" }
    }
}
