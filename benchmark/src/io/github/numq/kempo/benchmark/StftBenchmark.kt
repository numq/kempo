package io.github.numq.kempo.benchmark

import io.github.numq.kempo.dsp.DynamicSTFT
import kotlin.math.sin

/**
 * Micro-benchmarks for [DynamicSTFT] framing, forward analysis, and overlap-add synthesis.
 */
class StftBenchmark {
    var channels: Int = 2
    var sampleRate: Float = 44100f

    private lateinit var stft: DynamicSTFT
    private lateinit var testData: FloatArray

    fun setup() {
        stft = DynamicSTFT()
        val blockSamples = (sampleRate * 0.12f).toInt()
        val intervalSamples = (sampleRate * 0.03f).toInt()
        stft.configure(channels, channels, blockSamples, intervalSamples)

        testData = FloatArray(blockSamples) { i -> sin(i * 0.1).toFloat() }
        for (c in 0 until channels) {
            stft.writeInput(c, blockSamples, testData, 0)
        }
        stft.moveInput(blockSamples)
    }

    fun benchmarkAnalyse(bh: DummyBlackhole) {
        for (c in 0 until channels) {
            stft.analyseStep(c)
        }
        bh.consume(stft.spectrumRe)
        bh.consume(stft.spectrumIm)
    }

    fun benchmarkSynthesise(bh: DummyBlackhole) {
        for (c in 0 until channels) {
            stft.synthesiseStep(c)
        }
        bh.consume(stft.output)
    }

    fun benchmarkFullCycle(bh: DummyBlackhole) {
        for (c in 0 until channels) {
            stft.analyseStep(c)
            stft.synthesiseStep(c)
        }
        bh.consume(stft.output)
    }
}