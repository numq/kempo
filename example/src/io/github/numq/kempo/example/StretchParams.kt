package io.github.numq.kempo.example

/**
 * DSP parameters controlling time-stretching and spectral transformations.
 *
 * @property timeRatio Playback speed multiplier (0.25x to 3.0x).
 * @property pitchSemitones Pitch shift in semitones (-12 to +12).
 * @property formantSemitones Formant envelope shift in semitones (-12 to +12).
 */
data class StretchParams(
    val timeRatio: Float = 1.0f, val pitchSemitones: Float = 0.0f, val formantSemitones: Float = 0.0f
)