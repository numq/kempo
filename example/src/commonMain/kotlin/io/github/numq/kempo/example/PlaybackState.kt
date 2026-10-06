package io.github.numq.kempo.example

import io.github.numq.kempo.AudioTrack

/**
 * Finite state machine states representing the audio player status.
 */
sealed interface PlaybackState {
    /**
     * No audio file loaded; awaiting user input.
     */
    data object Idle : PlaybackState

    /**
     * Decoding and preparing audio file from disk.
     */
    data class Loading(val filename: String) : PlaybackState

    /**
     * Audio track is loaded, decoded, and ready for playback.
     */
    data class Ready(val track: AudioTrack, val params: StretchParams) : PlaybackState

    /**
     * Live real-time streaming playback is currently active.
     */
    data class Playing(val track: AudioTrack, val params: StretchParams) : PlaybackState

    /**
     * Playback paused; audio position is maintained.
     */
    data class Paused(val track: AudioTrack, val params: StretchParams) : PlaybackState
}