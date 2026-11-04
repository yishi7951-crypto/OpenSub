package com.opensub.app.platform

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.opensub.app.core.SAMPLE_RATE

/** A voice's fingerprint: CAM++ trained on VoxCeleb (3D-Speaker), 192 numbers for any stretch of speech, through sherpa-onnx. */
class SherpaVoiceprint : AutoCloseable {
    private val extractor: SpeakerEmbeddingExtractor

    init {
        SherpaNative.load()
        extractor = SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder().setModel(AppPaths.speakerModel.absolutePath)
            .setNumThreads(1).setDebug(false).setProvider("cpu").build())
    }

    /** The fingerprint of [samples] at 16 kHz, or null when they are too short to have one. */
    fun of(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally { stream.release() }
    }

    override fun close() = extractor.release()
}
