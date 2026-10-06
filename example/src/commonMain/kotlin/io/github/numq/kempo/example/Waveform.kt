package io.github.numq.kempo.example

import io.github.numq.kempo.AudioTrack
import io.github.numq.kempo.PcmConverter
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.max

object Waveform {
    /**
     * Decodes a standard WAV audio file into a normalized 32-bit floating-point [AudioTrack]
     * using [PcmConverter.fromPcm16].
     *
     * Safely closes all underlying file streams via `use` blocks to prevent file locks.
     */
    fun decode(file: File): AudioTrack {
        return AudioSystem.getAudioInputStream(file).use { audioInputStream ->
            val baseFormat = audioInputStream.format
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.sampleRate,
                16,
                baseFormat.channels,
                baseFormat.channels * 2,
                baseFormat.sampleRate,
                false // Little-endian
            )

            AudioSystem.getAudioInputStream(targetFormat, audioInputStream).use { pcmStream ->
                val bytes = pcmStream.readAllBytes()
                PcmConverter.fromPcm16(
                    bytes = bytes,
                    channels = targetFormat.channels,
                    sampleRate = targetFormat.sampleRate,
                    isLittleEndian = true
                )
            }
        }
    }

    /**
     * Asynchronously downsamples an [AudioTrack] into a normalized peak envelope suitable for visualization.
     *
     * @param track Source audio track.
     * @param pointsCount Resolution of the generated waveform points.
     * @return Array of normalized amplitude peak points in range [0.05f, 1.0f].
     */
    internal fun computeAmplitudes(track: AudioTrack, pointsCount: Int = 300): FloatArray {
        val totalSamples = track.numSamples
        if (totalSamples == 0) return FloatArray(0)

        val amplitudes = FloatArray(pointsCount)
        val chunkSize = max(1, totalSamples / pointsCount)
        val channels = track.channels
        val numChannels = track.numChannels

        for (point in 0 until pointsCount) {
            val start = point * chunkSize
            val end = minOf(start + chunkSize, totalSamples)
            var maxPeak = 0f

            for (i in start until end) {
                for (c in 0 until numChannels) {
                    val absVal = abs(channels[c][i])
                    if (absVal > maxPeak) maxPeak = absVal
                }
            }
            amplitudes[point] = maxPeak.coerceIn(0.05f, 1.0f)
        }
        return amplitudes
    }
}