package io.github.numq.kempo.benchmark

import io.github.numq.kempo.AudioTrack
import io.github.numq.kempo.PcmConverter

/**
 * Throughput benchmarks for [PcmConverter], measuring serialization and deserialization
 * speeds between raw interleaved 16-bit PCM bytes and normalized [AudioTrack] instances.
 */
class PcmBenchmark {
    var channels: Int = 2
    var sampleRate: Float = 44100f
    var frames: Int = 44100 // 1.0 second of stereo audio

    private lateinit var pcmBytes: ByteArray
    private lateinit var track: AudioTrack

    fun setup() {
        // 44100 frames * 2 channels * 2 bytes = 176,400 bytes
        pcmBytes = ByteArray(frames * channels * 2) { (it and 0x7F).toByte() }
        val channelData = Array(channels) { FloatArray(frames) { 0.5f } }
        track = AudioTrack(channelData, sampleRate)
    }

    fun fromPcm16(bh: DummyBlackhole) {
        val result = PcmConverter.fromPcm16(pcmBytes, channels, sampleRate, isLittleEndian = true)
        bh.consume(result)
    }

    fun toPcm16(bh: DummyBlackhole) {
        val bytes = PcmConverter.toPcm16(track, isLittleEndian = true)
        bh.consume(bytes)
    }
}