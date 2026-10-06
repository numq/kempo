package io.github.numq.kempo.benchmark

import io.github.numq.kempo.AudioTrack
import io.github.numq.kempo.KempoStretch
import kotlinx.benchmark.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Benchmarks for [KempoStretch] measuring real-time block latency and offline throughput.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
open class KempoStretchBenchmark {

    @Param("1", "2")
    var channels: Int = 2

    @Param("0.75", "1.0", "1.25")
    var timeRatio: Float = 1.0f

    var sampleRate: Float = 44100f
    var pitchSemitones: Float = 0.0f
    var formantSemitones: Float = 0.0f

    private lateinit var stretch: KempoStretch
    private var inBlockSize: Int = 0
    private var outBlockSize: Int = 0

    private lateinit var inBlockBuffer: Array<FloatArray>
    private lateinit var outBlockBuffer: Array<FloatArray>

    private var totalOfflineSamples: Int = 44100
    private lateinit var offlineInput: Array<FloatArray>
    private lateinit var offlineOutput: Array<FloatArray>

    @Setup
    fun setup() {
        stretch = KempoStretch()
        stretch.configure(
            nChannels = channels,
            blockSamples = (sampleRate * 0.12f).toInt(),
            intervalSamples = (sampleRate * 0.03f).toInt(),
            split = false
        )
        stretch.setTransposeSemitones(pitchSemitones)
        stretch.setFormantSemitones(formantSemitones)

        outBlockSize = stretch.defaultInterval
        inBlockSize = (outBlockSize / timeRatio).toInt().coerceAtLeast(1)

        inBlockBuffer = Array(channels) { c ->
            FloatArray(inBlockSize) { i ->
                sin(2.0 * PI * 440.0 * (i + c * 100) / sampleRate).toFloat()
            }
        }
        outBlockBuffer = Array(channels) { FloatArray(outBlockSize) }

        val outOfflineSamples = (totalOfflineSamples / timeRatio).toInt().coerceAtLeast(1)
        offlineInput = Array(channels) { c ->
            FloatArray(totalOfflineSamples) { i ->
                sin(2.0 * PI * 440.0 * (i + c * 100) / sampleRate).toFloat()
            }
        }
        offlineOutput = Array(channels) { FloatArray(outOfflineSamples) }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
    fun processStreamingBlock(bh: Blackhole) {
        stretch.process(inBlockBuffer, inBlockSize, outBlockBuffer, outBlockSize)
        bh.consume(outBlockBuffer)
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
    fun processExactOffline(bh: Blackhole) {
        stretch.exact(offlineInput, totalOfflineSamples, offlineOutput, offlineOutput[0].size)
        bh.consume(offlineOutput)
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
    fun processAudioTrackConvenience(bh: Blackhole) {
        val track = AudioTrack(offlineInput, sampleRate)
        val result = track.stretch(
            timeRatio = timeRatio, pitchSemitones = pitchSemitones, formantSemitones = formantSemitones
        )
        bh.consume(result)
    }
}