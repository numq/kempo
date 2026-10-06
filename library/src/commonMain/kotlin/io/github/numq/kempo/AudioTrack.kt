package io.github.numq.kempo

/**
 * Represents multichannel uncompressed floating-point audio data.
 *
 * @property channels Array of audio channels where each channel is a normalized [FloatArray] (typically in range `[-1.0f, 1.0f]`).
 * @property sampleRate Sampling rate in Hz (e.g., 44100.0f, 48000.0f).
 */
data class AudioTrack(val channels: Array<FloatArray>, val sampleRate: Float) {
    init {
        require(sampleRate > 0f) { "Sample rate must be positive: $sampleRate" }
        if (channels.isNotEmpty()) {
            val firstLen = channels[0].size
            for (c in 1 until channels.size) {
                require(channels[c].size == firstLen) {
                    "All channels must have the same length. Channel 0: $firstLen, channel $c:${channels[c].size}"
                }
            }
        }
    }

    /**
     * Total number of interleaved audio channels.
     */
    val numChannels: Int get() = channels.size

    /**
     * Number of audio sample frames per channel.
     */
    val numSamples: Int get() = if (channels.isNotEmpty()) channels[0].size else 0

    /**
     * Total audio track duration in seconds.
     */
    val durationSeconds: Double
        get() = if (sampleRate > 0f) numSamples.toDouble() / sampleRate else 0.0

    /**
     * Performs one-shot offline time-stretching and pitch-shifting on this track.
     *
     * @param timeRatio Playback speed multiplier (e.g., 0.5f is half-speed / 2x duration, 2.0f is double-speed).
     * @param pitchSemitones Pitch shift in semitones (e.g., +12.0f transposes one octave up).
     * @param formantSemitones Formant shift in semitones for vocal timbre control.
     * @return A new [AudioTrack] containing the processed audio.
     */
    fun stretch(
        timeRatio: Float = 1.0f, pitchSemitones: Float = 0.0f, formantSemitones: Float = 0.0f
    ): AudioTrack {
        require(timeRatio > 0f) { "timeRatio must be strictly positive, was: $timeRatio" }
        val engine = KempoStretch()
        return engine.stretch(
            track = this, timeRatio = timeRatio, pitchSemitones = pitchSemitones, formantSemitones = formantSemitones
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrack) return false
        if (sampleRate != other.sampleRate) return false
        if (numChannels != other.numChannels) return false
        if (numSamples != other.numSamples) return false
        if (!channels.contentDeepEquals(other.channels)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sampleRate.hashCode()
        result = 31 * result + channels.contentDeepHashCode()
        result = 31 * result + numChannels
        result = 31 * result + numSamples
        return result
    }
}