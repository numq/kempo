package io.github.numq.kempo.benchmark

import io.github.numq.kempo.AudioTrack
import io.github.numq.kempo.KempoStretch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Comprehensive benchmarks for [KempoStretch] measuring real-time streaming block latency
 * and exact buffer-to-buffer offline processing throughput.
 */
class KempoStretchBenchmark {
    var channels: Int = 2
    var sampleRate: Float = 44100f
    var timeRatio: Float = 1.0f
    var pitchSemitones: Float = 0.0f
    var formantSemitones: Float = 0.0f
    var splitComputation: Boolean = false

    private lateinit var stretch: KempoStretch

    var inBlockSize: Int = 0
        private set
    var outBlockSize: Int = 0
        private set

    private lateinit var inBlockBuffer: Array<FloatArray>
    private lateinit var outBlockBuffer: Array<FloatArray>

    private var totalOfflineSamples: Int = 44100
    private lateinit var offlineInput: Array<FloatArray>
    private lateinit var offlineOutput: Array<FloatArray>

    fun setup(offlineDurationSeconds: Float = 1.0f) {
        stretch = KempoStretch()
        stretch.configure(
            nChannels = channels,
            blockSamples = (sampleRate * 0.12f).toInt(),
            intervalSamples = (sampleRate * 0.03f).toInt(),
            split = splitComputation
        )
        stretch.setTransposeSemitones(pitchSemitones)
        stretch.setFormantSemitones(formantSemitones)

        outBlockSize = stretch.stft.defaultInterval()
        inBlockSize = (outBlockSize / timeRatio).toInt().coerceAtLeast(1)

        inBlockBuffer = Array(channels) { c ->
            FloatArray(inBlockSize) { i ->
                sin(2.0 * PI * 440.0 * (i + c * 100) / sampleRate).toFloat()
            }
        }
        outBlockBuffer = Array(channels) { FloatArray(outBlockSize) }

        totalOfflineSamples = (sampleRate * offlineDurationSeconds).toInt()
        val outOfflineSamples = (totalOfflineSamples * timeRatio).toInt().coerceAtLeast(1)

        offlineInput = Array(channels) { c ->
            FloatArray(totalOfflineSamples) { i ->
                sin(2.0 * PI * 440.0 * (i + c * 100) / sampleRate).toFloat()
            }
        }
        offlineOutput = Array(channels) { FloatArray(outOfflineSamples) }
    }

    fun processStreamingBlock(bh: DummyBlackhole) {
        stretch.process(inBlockBuffer, inBlockSize, outBlockBuffer, outBlockSize)
        bh.consume(outBlockBuffer)
    }

    fun processExactOffline(bh: DummyBlackhole) {
        val s = KempoStretch()
        s.configure(
            nChannels = channels,
            blockSamples = (sampleRate * 0.12f).toInt(),
            intervalSamples = (sampleRate * 0.03f).toInt(),
            split = splitComputation
        )
        s.setTransposeSemitones(pitchSemitones)
        s.setFormantSemitones(formantSemitones)
        s.exact(offlineInput, totalOfflineSamples, offlineOutput, offlineOutput[0].size)
        bh.consume(offlineOutput)
    }

    fun processAudioTrackConvenience(bh: DummyBlackhole) {
        val track = AudioTrack(offlineInput, sampleRate)
        val result = track.stretch(
            timeRatio = timeRatio, pitchSemitones = pitchSemitones, formantSemitones = formantSemitones
        )
        bh.consume(result)
    }
}