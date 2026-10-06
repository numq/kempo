package io.github.numq.kempo.benchmark

import io.github.numq.kempo.InternalKempoApi
import io.github.numq.kempo.dsp.DynamicSTFT
import kotlinx.benchmark.*
import kotlin.math.sin

/**
 * Micro-benchmarks for [DynamicSTFT] framing, analysis, and overlap-add synthesis.
 */
@OptIn(InternalKempoApi::class)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
open class StftBenchmark {

    @Param("1", "2")
    var channels: Int = 2

    var sampleRate: Float = 44100f

    private lateinit var stft: DynamicSTFT
    private lateinit var testData: FloatArray

    @Setup
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

    @Benchmark
    fun benchmarkAnalyse(bh: Blackhole) {
        for (c in 0 until channels) {
            stft.analyseStep(c)
        }
        bh.consume(stft.spectrumRe)
        bh.consume(stft.spectrumIm)
    }

    @Benchmark
    fun benchmarkSynthesise(bh: Blackhole) {
        for (c in 0 until channels) {
            stft.synthesiseStep(c)
        }
        bh.consume(stft.output)
    }

    @Benchmark
    fun benchmarkFullCycle(bh: Blackhole) {
        for (c in 0 until channels) {
            stft.analyseStep(c)
            stft.synthesiseStep(c)
        }
        bh.consume(stft.output)
    }
}