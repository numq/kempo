package io.github.numq.kempo.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.numq.kempo.AudioTrack
import io.github.numq.kempo.KempoStretch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Thread-safe audio playback controller managing real-time streaming through [KempoStretch].
 *
 * Coordinates playback position, parameter updates from the UI, and background audio thread streaming.
 */
class PlaybackStateMachine {
    /**
     * Current playback state exposed to the Compose UI.
     */
    var state: PlaybackState by mutableStateOf(PlaybackState.Idle)
        private set

    /**
     * Convenience property indicating whether streaming is actively running.
     */
    val isPlaying: Boolean
        get() = state is PlaybackState.Playing

    /**
     * Current track progress normalized between 0.0f and 1.0f.
     */
    var progress: Float by mutableStateOf(0f)
        private set

    private val currentParams = AtomicReference(StretchParams())
    private val isThreadActive = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var audioLine: SourceDataLine? = null

    @Volatile
    private var playbackSampleIndex = 0

    /**
     * Notifies the machine that a new file is being decoded.
     */
    fun onLoading(filename: String) {
        stopPlayback()
        playbackSampleIndex = 0
        progress = 0f
        state = PlaybackState.Loading(filename)
    }

    /**
     * Updates the active track and transitions to [PlaybackState.Ready].
     */
    fun onTrackLoaded(track: AudioTrack?, params: StretchParams) {
        stopPlayback()
        playbackSampleIndex = 0
        progress = 0f
        currentParams.set(params)
        state = if (track != null) {
            PlaybackState.Ready(track, params)
        } else {
            PlaybackState.Idle
        }
    }

    /**
     * Seeks playback position to the given normalized fraction [0.0f, 1.0f].
     */
    fun seekTo(fraction: Float) {
        val currentTrack = when (val s = state) {
            is PlaybackState.Ready -> s.track
            is PlaybackState.Playing -> s.track
            is PlaybackState.Paused -> s.track
            else -> return
        }
        val clamped = fraction.coerceIn(0f, 1f)
        playbackSampleIndex = (clamped * currentTrack.numSamples).toInt()
        progress = clamped
    }

    /**
     * Toggles between playing and paused states.
     */
    fun togglePlay() {
        when (val current = state) {
            is PlaybackState.Ready -> {
                startPlayback(current.track)
                state = PlaybackState.Playing(current.track, currentParams.get())
            }

            is PlaybackState.Playing -> {
                stopPlayback()
                state = PlaybackState.Paused(current.track, currentParams.get())
            }

            is PlaybackState.Paused -> {
                startPlayback(current.track)
                state = PlaybackState.Playing(current.track, currentParams.get())
            }

            else -> Unit
        }
    }

    /**
     * Atomically updates DSP stretch parameters applied by the streaming loop.
     */
    fun updateParams(params: StretchParams) {
        currentParams.set(params)
        state = when (val current = state) {
            is PlaybackState.Ready -> current.copy(params = params)
            is PlaybackState.Playing -> current.copy(params = params)
            is PlaybackState.Paused -> current.copy(params = params)
            else -> current
        }
    }

    private fun startPlayback(track: AudioTrack) {
        stopPlayback()

        val format = AudioFormat(track.sampleRate, 16, track.numChannels, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        val bufferSizeBytes = (track.sampleRate * track.numChannels * 2 * 0.12).toInt()
        line.open(format, bufferSizeBytes)
        line.start()
        audioLine = line

        isThreadActive.set(true)
        workerThread = thread(name = "KempoAudioThread", isDaemon = true) {
            runStreamingLoop(track, line)
        }
    }

    private fun stopPlayback() {
        isThreadActive.set(false)
        audioLine?.apply {
            try {
                stop()
                flush()
                close()
            } catch (_: Exception) {
            }
        }
        audioLine = null

        workerThread?.interrupt()
        workerThread?.join(200)
        workerThread = null
    }

    private fun runStreamingLoop(track: AudioTrack, line: SourceDataLine) {
        val stretch = KempoStretch()
        stretch.presetDefault(track.numChannels, track.sampleRate)

        val outBlockSize = stretch.stft.defaultInterval()
        // Pre-allocate maximum buffer size considering maximum speed ratio (3.0x) to guarantee zero allocations
        val maxInBlockSize = (outBlockSize * 3.5f).toInt() + 1
        val inBlockBuffer = Array(track.numChannels) { FloatArray(maxInBlockSize) }
        val outBlockBuffer = Array(track.numChannels) { FloatArray(outBlockSize) }
        val byteBuffer = ByteBuffer.allocate(outBlockSize * track.numChannels * 2).order(ByteOrder.LITTLE_ENDIAN)

        var lastPitch = Float.NaN
        var lastFormant = Float.NaN
        var lastProgressUpdateTime = 0L

        try {
            while (isThreadActive.get()) {
                val params = currentParams.get()

                if (params.pitchSemitones != lastPitch) {
                    stretch.setTransposeSemitones(params.pitchSemitones)
                    lastPitch = params.pitchSemitones
                }
                if (params.formantSemitones != lastFormant) {
                    stretch.setFormantSemitones(params.formantSemitones)
                    lastFormant = params.formantSemitones
                }

                // timeRatio: 0.5x means playing half as fast (takes 0.5 * outBlockSize samples per output frame)
                val rate = params.timeRatio.coerceIn(0.25f, 3.0f)
                val inBlockSize = max(1, (outBlockSize * rate).toInt())

                val total = track.numSamples
                for (i in 0 until inBlockSize) {
                    val idx = (playbackSampleIndex + i) % total
                    for (c in 0 until track.numChannels) {
                        inBlockBuffer[c][i] = track.channels[c][idx]
                    }
                }
                playbackSampleIndex = (playbackSampleIndex + inBlockSize) % total

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdateTime > 33) {
                    progress = playbackSampleIndex.toFloat() / total
                    lastProgressUpdateTime = now
                }

                stretch.process(inBlockBuffer, inBlockSize, outBlockBuffer, outBlockSize)

                byteBuffer.clear()
                for (i in 0 until outBlockSize) {
                    for (c in 0 until track.numChannels) {
                        val s = outBlockBuffer[c][i].coerceIn(-1.0f, 1.0f)
                        byteBuffer.putShort((s * 32767.0f).toInt().toShort())
                    }
                }

                if (!isThreadActive.get()) break
                line.write(byteBuffer.array(), 0, byteBuffer.position())
            }
        } catch (_: Exception) {
            // Stream stopped or thread interrupted cleanly
        }
    }

    /**
     * Releases system audio line resources and resets playback to idle.
     */
    fun release() {
        stopPlayback()
        state = PlaybackState.Idle
    }
}