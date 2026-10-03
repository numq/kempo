# kempo

High-performance, pure Kotlin Multiplatform (KMP) audio time-stretching and pitch-shifting library based on an advanced
phase-locked phase vocoder.

Kempo runs natively across **JVM, Android, iOS (arm64, simulatorArm64), and WebAssembly (Wasm-JS)** without external
C/C++ dependencies or JNI bindings.

---

## Features

- **Pristine Audio Quality**: Advanced phase vocoder with vertical phase-locking across harmonics, eliminating phasing,
  smearing, and transient degradation.
- **Stereo & Multichannel Phase Coherence**: Locked inter-channel phase differences (IPD) ensure rock-solid stereo
  imaging without spatial collapse.
- **Formant-Preserved Pitch Shifting**: Decoupled pitch transpose and formant shifting to preserve natural vocal and
  instrumental timbres.
- **Zero Allocations on Audio Thread**: Real-time streaming API designed for zero GC allocations during block
  processing.
- **Optimized DSP Primitives**: Custom mixed-radix FFT (radix-2, 3, 4, and composite), half-bin shifted modified real
  FFT, and WOLA Kaiser windowing.
- **Flexible Processing Modes**:
    - **Streaming Mode**: Ultra-low-latency real-time block-by-block streaming with optional compute-splitting across
      frames.
    - **Exact Offline Mode**: Deterministic buffer-to-buffer rendering with automated pre-roll, latency compensation,
      and tail overlap-add flushing.
    - **High-Level AudioTrack API**: One-liner stretching directly on uncompressed audio tracks.
- **Built-in PCM Serialization**: Fast conversion between raw interleaved 16-bit PCM bytes and normalized 32-bit
  floating-point audio tracks.

---

## Platform Support

| Platform         | Targets                                                   |
|:-----------------|:----------------------------------------------------------|
| **JVM**          | Java 11+, Server, Desktop Compose (macOS, Windows, Linux) |
| **Android**      | API 21+ (ARM64, x86_64)                                   |
| **Apple Native** | iOS (`iosArm64`, `iosSimulatorArm64`)                     |
| **WebAssembly**  | Wasm-JS (`wasmJs`), Node.js, Browser                      |

---

## Architecture Overview

Kempo is structured into three layers:

1. **DSP Foundation (`io.github.numq.kempo.dsp`)**:
    - `FastFft`: In-place mixed-radix complex FFT supporting composite factorizations without runtime allocations.
    - `ModifiedRealFFT`: Real-to-complex transform evaluated on half-bin shifted frequency grids `(k + 0.5) / N`.
    - `Windows`: Parametric Kaiser window generation with Weighted Overlap-Add (WOLA) normalization.
2. **Buffering (`io.github.numq.kempo.buffer`)**:
    - `MultiChannelBuffer`: High-throughput power-of-two circular ring buffer optimized with bulk memory copies.
3. **Core Engine (`io.github.numq.kempo`)**:
    - `DynamicSTFT`: Framing and overlap-add resynthesis engine.
    - `KempoStretch`: Phase vocoder controlling time ratio, pitch transposition, spectral peak picking, and formant
      transformation.
    - `AudioTrack` & `PcmConverter`: High-level audio representations and 16-bit PCM converters.

---

## Quick Start

### Installation

#### Amper (`module.yaml`)

```yaml
dependencies:
  - io.github.numq.kempo:kempo:1.0.0

```

#### Gradle (`build.gradle.kts`)

```kotlin
dependencies {
    implementation("io.github.numq.kempo:kempo:1.0.0")
}

```

---

### High-Level One-Liner API

For simple offline processing on uncompressed audio buffers:

```kotlin
import io.github.numq.kempo.AudioTrack

fun processTrack(track: AudioTrack): AudioTrack {
    // 25% faster (1.25x), transposed up by 3 semitones, preserving natural formant timbre
    return track.stretch(
        timeRatio = 1.25f,
        pitchSemitones = 3.0f,
        formantSemitones = 0.0f
    )
}

```

---

### Real-Time Streaming Example

For low-latency audio callbacks (e.g., audio output loops, game audio, synth engines):

```kotlin
import io.github.numq.kempo.KempoStretch
import kotlin.math.max

class AudioStreamProcessor(channels: Int = 2, sampleRate: Float = 44100f) {
    private val stretch = KempoStretch().apply {
        // presetDefault: balanced quality (120 ms block, 30 ms interval)
        presetDefault(channels, sampleRate)
        setTransposeSemitones(semitones = 3.0f) // Transpose +3 semitones
        setFormantSemitones(semitones = 0.0f)   // Keep natural vocal timbre
    }

    private var timeRatio = 1.25f // 25% faster
    val outBlockSize: Int = stretch.stft.defaultInterval()
    val inBlockSize: Int get() = max(1, (outBlockSize / timeRatio).toInt())

    // Called repeatedly on the high-priority audio thread (zero allocations)
    fun processBlock(audioInputBlock: Array<FloatArray>, audioOutputBlock: Array<FloatArray>) {
        stretch.process(
            inputs = audioInputBlock,
            inputSamples = inBlockSize,
            outputs = audioOutputBlock,
            outputSamples = outBlockSize
        )
    }
}

```

---

### One-Shot Offline Processing Example

For exact file-to-file rendering or batch audio processing:

```kotlin
import io.github.numq.kempo.KempoStretch
import kotlin.math.roundToInt

fun processFullTrack(
    pcmInput: Array<FloatArray>,
    sampleRate: Float,
    timeRatio: Float = 1.0f,
    pitchSemitones: Float = -2.0f
): Array<FloatArray> {
    val channels = pcmInput.size
    val totalInputSamples = pcmInput[0].size
    val totalOutputSamples = (totalInputSamples * timeRatio).roundToInt().coerceAtLeast(1)
    val pcmOutput = Array(channels) { FloatArray(totalOutputSamples) }

    val stretch = KempoStretch()
    stretch.presetDefault(channels, sampleRate)
    stretch.setTransposeSemitones(semitones = pitchSemitones)

    // Processes the entire buffer with exact latency alignment and tail overlap-add flushing
    val success = stretch.exact(
        inputs = pcmInput,
        inputSamples = totalInputSamples,
        outputs = pcmOutput,
        outputSamples = totalOutputSamples
    )

    return pcmOutput
}

```

---

### PCM Conversion Utilities

Convert between platform byte streams (WAV, audio recorders) and `AudioTrack`:

```kotlin
import io.github.numq.kempo.PcmConverter

// Decode raw 16-bit PCM bytes into normalized [-1.0f, 1.0f] float channels
val track = PcmConverter.fromPcm16(
    bytes = wavByteArray,
    channels = 2,
    sampleRate = 44100f,
    isLittleEndian = true
)

// Process audio
val shifted = track.stretch(timeRatio = 1.0f, pitchSemitones = 2.0f)

// Re-encode back to 16-bit PCM bytes
val outputBytes = PcmConverter.toPcm16(shifted, isLittleEndian = true)

```

---

## API Reference

### Presets

* `presetDefault(channels: Int, sampleRate: Float, split: Boolean = false)`: Optimal balance for music and speech (120
  ms analysis block, 30 ms hop).
* `presetCheaper(channels: Int, sampleRate: Float, split: Boolean = true)`: Reduced CPU footprint for low-power mobile
  or game engines (100 ms analysis block, 40 ms hop).

### Parameter Control

* `setTransposeFactor(multiplier: Float, tonalityLimit: Float = 0f)`: Set frequency transposition ratio directly.
* `setTransposeSemitones(semitones: Float, tonalityLimit: Float = 0f)`: Pitch transposition in semitones (e.g., `+12.0f`
  for one octave up).
* `setFormantFactor(multiplier: Float, compensatePitch: Boolean = false)`: Adjust spectral envelope independently of
  pitch.
* `setFormantSemitones(semitones: Float, compensatePitch: Boolean = false)`: Formant shift in semitones.
* `setFreqMap(fn: (Float) -> Float)`: Provide a custom frequency mapping function for experimental or non-linear pitch
  transformations.

---

## Benchmarks

Measured on JVM (HotSpot 21, Apple Silicon / x86_64 equivalent):

| Component            | Benchmark Type                 | Size / Config                          | Throughput / Latency     |
|----------------------|--------------------------------|----------------------------------------|--------------------------|
| `FastFft`            | Forward Complex FFT            | N = 1024                               | **124.48 ops/ms**        |
| `FastFft`            | Inverse Complex FFT            | N = 1024                               | **122.44 ops/ms**        |
| `ModifiedRealFFT`    | Forward Real FFT               | N = 1024                               | **173.55 ops/ms**        |
| `ModifiedRealFFT`    | Inverse Real FFT               | N = 1024                               | **189.14 ops/ms**        |
| `WindowedFFT`        | Full Cycle (WOLA + FFT + IFFT) | N = 1024                               | **84.71 ops/ms**         |
| `MultiChannelBuffer` | Block Write / Read             | 1024 samples                           | **8 783 – 9 571 ops/ms** |
| `MultiChannelBuffer` | Wrap-Around Ring Boundary      | 1024 samples                           | **7 124.37 ops/ms**      |
| `PcmConverter`       | PCM-16 to AudioTrack           | 44 100 frames (Stereo 1.0 s)           | **5.65 ops/ms**          |
| `PcmConverter`       | AudioTrack to PCM-16           | 44 100 frames (Stereo 1.0 s)           | **3.18 ops/ms**          |
| `KempoStretch`       | Streaming Block Latency        | Stereo, 1.0x, 44.1 kHz                 | **406.63 μs**            |
| `KempoStretch`       | Offline Exact Stretch          | Stereo, 1.0 s, 44.1 kHz, 1.25x (+3 st) | **25.48 ms (39.3x RT)**  |
| `AudioTrack.stretch` | Convenience High-Level API     | Stereo, 1.0 s, 44.1 kHz, +3 st         | **16.62 ms (60.2x RT)**  |

---

## Running the Example Studio App

The repository includes a Compose Desktop Studio application with real-time waveform visualization, drag-and-drop WAV
support, and live parameter sliders:

```bash
./amper run :example

```

*(or run the `example` module directly from your IDE)*

---

## License

Apache-2.0 License - see [LICENSE](LICENSE) file for details.