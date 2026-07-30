package com.example.experimentaltranslation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

internal class MarianOnnxTranslationProvider(
    private val modelRoot: File
) : ExperimentalTranslationProvider {
    override val engine = ExperimentalTranslationEngine.MARIAN_INT8
    private val mutex = Mutex()
    private val environment = OrtEnvironment.getEnvironment()
    private var loadedDirection: String? = null
    private var resources: DirectionResources? = null

    override suspend fun translate(
        request: ExperimentalTranslationRequest
    ): ExperimentalTranslationResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            val active = resourcesFor(request)
            val encodedSource = active.sourceTokenizer.encode(request.text)
            check(encodedSource.isNotEmpty()) { "Marian input produced no tokens" }
            val sourceIds = encodedSource + active.sourceTokenizer.eosId.toLong()
            val translatedIds = runGreedy(active, sourceIds, request.maximumOutputTokens)
            val translated = active.targetTokenizer.decode(translatedIds)
            check(translated.isNotBlank()) { "Marian produced an empty translation" }
            ExperimentalTranslationResult(
                translated,
                engine,
                SystemClock.elapsedRealtime() - startedAt
            )
        }
    }

    private fun resourcesFor(request: ExperimentalTranslationRequest): DirectionResources {
        val direction = "${request.sourceLanguage.code}-${request.targetLanguage.code}"
        if (loadedDirection == direction) return checkNotNull(resources)
        resources?.close()
        val directory = File(modelRoot, direction)
        check(directory.isDirectory) { "Marian direction is not installed: $direction" }
        val properties = Properties().apply {
            File(directory, "tokenizer.properties").inputStream().use(::load)
        }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(DEFAULT_INTRA_OP_THREADS)
            setInterOpNumThreads(1)
        }
        val loaded = options.use {
            DirectionResources(
                encoder = environment.createSession(
                    File(directory, "encoder_model_quantized.onnx").absolutePath,
                    options
                ),
                decoder = environment.createSession(
                    File(directory, "decoder_model_quantized.onnx").absolutePath,
                    options
                ),
                sourceTokenizer = SentencePieceTokenizer.load(
                    File(directory, "source.spm"),
                    File(directory, "vocab.json"),
                    properties,
                    "source"
                ),
                targetTokenizer = SentencePieceTokenizer.load(
                    File(directory, "target.spm"),
                    File(directory, "vocab.json"),
                    properties,
                    "target"
                ),
                decoderStartId = properties.getProperty("decoder_start_id")?.toIntOrNull()
                    ?: properties.getProperty("target_pad_id")?.toIntOrNull()
                    ?: 0
            )
        }
        loadedDirection = direction
        resources = loaded
        return loaded
    }

    private fun runGreedy(
        resources: DirectionResources,
        sourceIds: LongArray,
        maximumOutputTokens: Int
    ): List<Int> {
        val sourceInput = arrayOf(sourceIds)
        val sourceMask = arrayOf(LongArray(sourceIds.size) { 1L })
        OnnxTensor.createTensor(environment, sourceInput).use { inputIds ->
            OnnxTensor.createTensor(environment, sourceMask).use { attentionMask ->
                val encoderInputs = resources.encoder.inputNames.associateWith { name ->
                    when {
                        name.contains("input_ids") -> inputIds
                        name.contains("attention_mask") -> attentionMask
                        else -> error("Unsupported Marian encoder input: $name")
                    }
                }
                resources.encoder.run(encoderInputs).use { encoderResult ->
                    val hiddenState = encoderResult[0] as OnnxTensor
                    val generated = mutableListOf(resources.decoderStartId)
                    repeat(maximumOutputTokens.coerceIn(1, MAXIMUM_OUTPUT_TOKENS)) {
                        val decoderIds = arrayOf(generated.map(Int::toLong).toLongArray())
                        val decoderMask = arrayOf(LongArray(generated.size) { 1L })
                        OnnxTensor.createTensor(environment, decoderIds).use { decoderInput ->
                            OnnxTensor.createTensor(environment, decoderMask).use { targetMask ->
                                val inputs = resources.decoder.inputNames.associateWith { name ->
                                    when {
                                        name == "input_ids" || name.endsWith("decoder_input_ids") -> decoderInput
                                        name.contains("encoder_hidden_states") -> hiddenState
                                        name.contains("encoder_attention_mask") -> attentionMask
                                        name.contains("attention_mask") -> targetMask
                                        else -> error("Unsupported Marian decoder input: $name")
                                    }
                                }
                                resources.decoder.run(inputs).use { decoderResult ->
                                    val logits = decoderResult[0] as OnnxTensor
                                    val nextId = argmaxLastToken(logits)
                                    if (nextId == resources.targetTokenizer.eosId) {
                                        return generated.drop(1)
                                    }
                                    generated += nextId
                                }
                            }
                        }
                    }
                    return generated.drop(1)
                }
            }
        }
    }

    private fun argmaxLastToken(logits: OnnxTensor): Int {
        val shape = logits.info.shape
        check(shape.size == 3 && shape[0] == 1L) { "Unexpected Marian logits shape" }
        val sequenceLength = shape[1].toInt()
        val vocabularySize = shape[2].toInt()
        val buffer = logits.floatBuffer
        val offset = (sequenceLength - 1) * vocabularySize
        var bestId = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (id in 0 until vocabularySize) {
            val value = buffer.get(offset + id)
            if (value > bestValue) {
                bestValue = value
                bestId = id
            }
        }
        return bestId
    }

    override fun close() {
        resources?.close()
        resources = null
        loadedDirection = null
    }

    private data class DirectionResources(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val sourceTokenizer: SentencePieceTokenizer,
        val targetTokenizer: SentencePieceTokenizer,
        val decoderStartId: Int
    ) : AutoCloseable {
        override fun close() {
            decoder.close()
            encoder.close()
        }
    }

    private companion object {
        const val DEFAULT_INTRA_OP_THREADS = 4
        const val MAXIMUM_OUTPUT_TOKENS = 256
    }
}
