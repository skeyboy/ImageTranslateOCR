package com.example.experimentaltranslation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

class SentencePieceTokenizerTest {
    @Test
    fun `encodes best-scoring path and decodes space marker`() {
        val tokenizer = tokenizer(
            piece("<unk>", -10f, 2),
            piece("<s>", 0f, 3),
            piece("</s>", 0f, 3),
            piece("▁hello", 5f),
            piece("▁", 1f),
            piece("hello", 1f)
        )

        assertEquals(listOf(3L), tokenizer.encode("hello").toList())
        assertEquals("hello", tokenizer.decode(listOf(3, tokenizer.eosId)))
    }

    @Test
    fun `unknown character remains tokenizable after a partial match`() {
        val tokenizer = tokenizer(
            piece("<unk>", -10f, 2),
            piece("<s>", 0f, 3),
            piece("</s>", 0f, 3),
            piece("▁a", 5f)
        )

        val ids = tokenizer.encode("ab").toList()

        assertEquals(2, ids.size)
        assertEquals(6L, ids.first())
        assertEquals(0L, ids.last())
    }

    @Test
    fun `skips length-delimited model metadata after pieces`() {
        val model = File.createTempFile("sentencepiece-metadata", ".model")
        model.deleteOnExit()
        model.writeBytes(ByteArrayOutputStream().apply {
            val unknown = piece("<unk>", -10f, 2)
            write(tag(1, 2))
            write(varint(unknown.size.toLong()))
            write(unknown)
            write(tag(2, 2))
            write(varint(130))
            write(ByteArray(130) { 0x7f })
        }.toByteArray())

        val tokenizer = SentencePieceTokenizer.load(
            model,
            vocabularyFile(),
            Properties().apply {
                setProperty("test_unk_id", "0")
                setProperty("test_eos_id", "0")
                setProperty("test_pad_id", "0")
            },
            "test"
        )

        assertTrue(tokenizer.encode("x").all { it == 0L })
    }

    @Test
    fun `catalog keeps all model weights external`() {
        assertTrue(ExperimentalModelCatalog.marian.requiredFiles.all { !it.startsWith("assets/") })
        assertEquals(
            setOf("translategemma-4b.task"),
            ExperimentalModelCatalog.translateGemma.requiredFiles
        )
    }

    private fun tokenizer(vararg pieces: ByteArray): SentencePieceTokenizer {
        val model = File.createTempFile("sentencepiece", ".model")
        model.deleteOnExit()
        model.writeBytes(ByteArrayOutputStream().apply {
            pieces.forEach { bytes ->
                write(tag(1, 2))
                write(varint(bytes.size.toLong()))
                write(bytes)
            }
        }.toByteArray())
        return SentencePieceTokenizer.load(
            model,
            vocabularyFile(),
            Properties().apply {
                setProperty("test_unk_id", "0")
                setProperty("test_bos_id", "1")
                setProperty("test_eos_id", "2")
                setProperty("test_pad_id", "2")
            },
            "test"
        )
    }

    private fun vocabularyFile(): File = File.createTempFile("sentencepiece-vocab", ".json").apply {
        deleteOnExit()
        writeText(
            """{"<unk>":0,"<s>":1,"</s>":2,"▁hello":3,"▁":4,"hello":5,"▁a":6}"""
        )
    }

    private fun piece(text: String, score: Float, type: Int = 1): ByteArray =
        ByteArrayOutputStream().apply {
            val textBytes = text.toByteArray()
            write(tag(1, 2))
            write(varint(textBytes.size.toLong()))
            write(textBytes)
            write(tag(2, 5))
            val bits = score.toBits()
            write(byteArrayOf(bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), (bits ushr 24).toByte()))
            write(tag(3, 0))
            write(varint(type.toLong()))
        }.toByteArray()

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        while (true) {
            if (remaining and 0x7f.inv().toLong() == 0L) {
                write(remaining.toInt())
                break
            }
            write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }.toByteArray()
}
