package io.github.numq.kempo

import kotlin.math.roundToInt

/**
 * Platform-independent utility to convert raw interleaved PCM byte arrays into normalized [AudioTrack] instances
 * and serialize [AudioTrack] back to interleaved PCM bytes.
 */
object PcmConverter {
    /**
     * Converts interleaved 16-bit signed PCM byte data into a normalized floating-point [AudioTrack].
     *
     * @param bytes Raw interleaved 16-bit PCM bytes.
     * @param channels Number of audio channels (e.g. 1 for mono, 2 for stereo).
     * @param sampleRate Sampling rate in Hz (e.g. 44100.0f, 48000.0f).
     * @param isLittleEndian True if audio data is little-endian (standard for WAV and desktop/Android PCM).
     * @return A normalized [AudioTrack] with audio samples in the range `[-1.0f, 1.0f]`.
     */
    fun fromPcm16(
        bytes: ByteArray, channels: Int, sampleRate: Float, isLittleEndian: Boolean = true
    ): AudioTrack {
        val bytesPerSample = 2
        val frameSize = channels * bytesPerSample
        val totalFrames = bytes.size / frameSize
        val channelData = Array(channels) { FloatArray(totalFrames) }

        var byteIndex = 0
        for (i in 0 until totalFrames) {
            for (c in 0 until channels) {
                val b0 = bytes[byteIndex++].toInt() and 0xFF
                val b1 = bytes[byteIndex++].toInt() and 0xFF
                val rawShort = if (isLittleEndian) {
                    (b1 shl 8) or b0
                } else {
                    (b0 shl 8) or b1
                }.toShort()

                channelData[c][i] = rawShort / 32768.0f
            }
        }

        return AudioTrack(channelData, sampleRate)
    }

    /**
     * Converts a normalized [AudioTrack] back into an interleaved 16-bit signed PCM byte array.
     *
     * @param track The audio track to convert.
     * @param isLittleEndian True for little-endian output, false for big-endian.
     * @return Raw interleaved 16-bit PCM byte array.
     */
    fun toPcm16(
        track: AudioTrack, isLittleEndian: Boolean = true
    ): ByteArray {
        val channels = track.numChannels
        val totalFrames = track.numSamples
        val bytes = ByteArray(totalFrames * channels * 2)

        var byteIndex = 0
        for (i in 0 until totalFrames) {
            for (c in 0 until channels) {
                val sample = track.channels[c][i].coerceIn(-1.0f, 1.0f)
                val rawShort = (sample * 32768.0f).roundToInt().coerceIn(-32768, 32767)

                val b0 = (rawShort and 0xFF).toByte()
                val b1 = ((rawShort ushr 8) and 0xFF).toByte()

                if (isLittleEndian) {
                    bytes[byteIndex++] = b0
                    bytes[byteIndex++] = b1
                } else {
                    bytes[byteIndex++] = b1
                    bytes[byteIndex++] = b0
                }
            }
        }

        return bytes
    }
}