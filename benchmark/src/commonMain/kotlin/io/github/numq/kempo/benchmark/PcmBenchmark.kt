package io.github.numq.kempo.benchmark

import io.github.numq.kempo.AudioTrack
import io.github.numq.kempo.PcmConverter
import kotlinx.benchmark.*

/**
 * Throughput benchmarks for [PcmConverter] measuring byte serialization/deserialization.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
open class PcmBenchmark {

    var channels: Int = 2
    var sampleRate: Float = 44100f
    var frames: Int = 44100

    private lateinit var pcmBytes: ByteArray
    private lateinit var track: AudioTrack

    @Setup
    fun setup() {
        pcmBytes = ByteArray(frames * channels * 2) { (it and 0x7F).toByte() }
        val channelData = Array(channels) { FloatArray(frames) { 0.5f } }
        track = AudioTrack(channelData, sampleRate)
    }

    @Benchmark
    fun fromPcm16(bh: Blackhole) {
        val result = PcmConverter.fromPcm16(pcmBytes, channels, sampleRate, isLittleEndian = true)
        bh.consume(result)
    }

    @Benchmark
    fun toPcm16(bh: Blackhole) {
        val bytes = PcmConverter.toPcm16(track, isLittleEndian = true)
        bh.consume(bytes)
    }
}