# kempo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.numq.kempo/kempo.svg?label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.numq.kempo/kempo)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

**Kempo** is a high-performance, pure Kotlin Multiplatform (KMP) audio time-stretching and pitch-shifting library
powered by an advanced **phase-locked phase vocoder**.

Engineered from the ground up for professional digital signal processing (DSP), sub-millisecond streaming latency, and
pristine acoustic transparency, Kempo operates natively across **JVM, Android, iOS, and WebAssembly (Wasm/JS)**
without C/C++ native toolchains, dynamic libraries, or JNI runtime overhead.

---

## Table of Contents

- [Key Highlights](#key-highlights)
- [Architecture & DSP Pipeline](#architecture--dsp-pipeline)
- [Module Structure](#module-structure)
- [Supported Platforms](#supported-platforms)
- [Installation](#installation)
- [Getting Started](#getting-started)
    - [1. One-Liner Track Processing (`AudioTrack`)](#1-one-liner-track-processing-audiotrack)
    - [2. Low-Latency Real-Time Streaming](#2-low-latency-real-time-streaming)
    - [3. Exact Offline Buffer Rendering](#3-exact-offline-buffer-rendering)
    - [4. Interleaved PCM-16 Serialization](#4-interleaved-pcm-16-serialization)
- [Deep Dive & Parameter Tuning](#deep-dive--parameter-tuning)
    - [Pitch & Formant Decoupling](#pitch--formant-decoupling)
    - [Tonality Limits & Custom Frequency Maps](#tonality-limits--custom-frequency-maps)
    - [Latency & Memory Mechanics (Zero-GC Streaming)](#latency--memory-mechanics-zero-gc-streaming)
    - [Multichannel & 5.1 Surround Pipelines](#multichannel--51-surround-pipelines)
- [Benchmarks](#benchmarks)
- [Credits & Acknowledgements](#credits--acknowledgements)
- [License](#license)

---

## Key Highlights

- **⚡ Zero-Allocation Audio Streaming**: The real-time block-by-block streaming loop operates with **0 B heap
  allocations**, making it safe for high-priority real-time audio threads and glitch-free rendering loops.
- **🔒 Vertical Harmonic Phase-Locking**: Eliminates the transient smearing, phasing, and "underwater" hollow artifacts
  typical of classic phase vocoders by locking phase trajectories around prominent spectral peaks.
- **🎧 Multichannel Phase Coherence**: Maintains exact Inter-channel Phase Differences (IPD) across stereo, 5.1, and
  multichannel streams, completely preventing spatial collapse or stereo image drift.
- **🗣️ Independent Formant Scaling**: Decouples pitch shifting from vocal tract resonance, allowing natural vocal
  transposition without the "chipmunk" or "monster" effect, or intentional timbre modification.
- **🚀 Hand-Optimized DSP Foundation**:
    - **`FastFft`**: In-place mixed-radix FFT engine supporting radices 2, 3, 4, and composite factorization.
    - **`ModifiedRealFFT`**: Real-to-complex transform evaluated on half-bin shifted frequency points `(k + 0.5) / N`
      to eliminate DC/Nyquist edge singularities.
    - **`Windows`**: Parametric Kaiser windows with Weighted Overlap-Add (WOLA) normalization for perfect
      reconstruction.
- **🔁 Power-of-Two Ring Buffering (`MultiChannelBuffer`)**: Circular multichannel ring buffer backed by a single flat
  array, accelerated with unrolled vector accumulators and split-copy boundary handling.

---

## Architecture & DSP Pipeline

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                             Audio Ingestion                             │
│       AudioTrack (32-bit Float) / Raw Multi-Channel Float Arrays        │
│                                   │                                     │
│      PcmConverter.fromPcm16 ◄─────┴─────► PcmConverter.toPcm16          │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ Feed Blocks
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Dynamic STFT Analysis                            │
│   MultiChannelBuffer (Input Ring) ──► WindowedFFT (WOLA Kaiser Window)  │
│                                                    │                    │
│                                                    ▼                    │
│                                             ModifiedRealFFT             │
│                                      [Half-bin grid: (k + 0.5) / N]     │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ Spectral Frames
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                   Phase-Locked Vocoder Processing Core                  │
│   • Spectral Peak Detection      ──► Slew-Smoothed Energy Contours      │
│   • Vertical Phase-Locking       ──► Harmonic Alignment Across Bins     │
│   • Horizontal Phase Progression ──► Instantaneous Phase Accumulator    │
│   • Inter-Channel Coherence      ──► Locked Inter-channel Phase (IPD)   │
│   • Formant Filtering            ──► Cepstral-like Spectral Envelope    │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ Modified Spectrum
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       Resynthesis & Overlap-Add                         │
│   Inverse ModifiedRealFFT ──► WOLA Synthesis Window Normalization       │
│                                                    │                    │
│                                                    ▼                    │
│                                      MultiChannelBuffer (Output Ring)   │
└─────────────────────────────────────────────────────────────────────────┘
```

1. **Analysis Stage**: Audio frames are pushed into `MultiChannelBuffer`. Centered circular rotations align windowed
   frames before computing half-bin shifted real spectra via `ModifiedRealFFT`.
2. **Phase Transformation**: Peaks in the spectral envelope are identified via logarithmic smoothing. Phase updates are
   constrained across harmonic regions (vertical locking) and across channels (stereo coherence).
3. **Synthesis & Reconstruction**: Inverse transforms are normalized through dual-pass Kaiser WOLA windows and summed
   into the output accumulation ring.

---

## Module Structure

The codebase is partitioned into targeted modules separating mathematical DSP foundations from interactive UI tooling:

| Module       | Description                                                                                                                                                                  |
|:-------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:library`   | Core KMP library containing `KempoStretch`, `DynamicSTFT`, `FastFft`, `ModifiedRealFFT`, `MultiChannelBuffer`, `AudioTrack`, and `PcmConverter`. Published to Maven Central. |
| `:benchmark` | JMH and `kotlinx-benchmark` test harness measuring execution throughput, GC overhead, and latency profiles across JVM/KMP targets.                                           |
| `:example`   | Desktop Compose Studio application featuring real-time interactive waveform rendering, drag-and-drop WAV audio decoding, and live parameter control cards.                   |

---

## Supported Platforms

Kempo targets all major Kotlin Multiplatform platforms without external C/C++ runtime requirements:

- **JVM**: Java 11+, Server, Desktop (macOS, Linux, Windows)
- **Android**: API 24+ (ARM64, x86_64)
- **Apple Native**: iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`)
- **WebAssembly**: Wasm/JS (`wasmJs`) for browser and Node.js runtimes

---

## Installation

Add the dependency to your module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.numq.kempo:kempo:1.0.0")
        }
    }
}

```

Or for single-target JVM / Android projects:

```kotlin
dependencies {
    implementation("io.github.numq.kempo:kempo:1.0.0")
}

```

---

## Getting Started

### 1. One-Liner Track Processing (`AudioTrack`)

For batch audio workflows or high-level offline rendering:

```kotlin
import io.github.numq.kempo.AudioTrack

fun processAudio(inputTrack: AudioTrack): AudioTrack {
    // 25% faster (1.25x), transposed up by +3 semitones, preserving natural vocal timbre
    return inputTrack.stretch(
        timeRatio = 1.25f,
        pitchSemitones = 3.0f,
        formantSemitones = 0.0f
    )
}

```

### 2. Low-Latency Real-Time Streaming

For real-time audio threads (e.g., audio callbacks, synthesizers, DAW engines):

```kotlin
import io.github.numq.kempo.KempoStretch
import kotlin.math.max

class RealtimeStreamEngine(channels: Int = 2, sampleRate: Float = 44100f) {
    private val stretch = KempoStretch().apply {
        presetDefault(channels, sampleRate)
        setTransposeSemitones(semitones = 2.0f) // Transpose +2 semitones
        setFormantSemitones(semitones = 0.0f)   // Keep natural formants
    }

    private var timeRatio = 1.25f // 1.25x speed
    val outBlockSize: Int = stretch.defaultInterval
    val inBlockSize: Int get() = max(1, (outBlockSize / timeRatio).toInt())

    // Called inside the real-time audio callback: ZERO heap allocations
    fun processBlock(inputChannels: Array<FloatArray>, outputChannels: Array<FloatArray>) {
        stretch.process(
            inputs = inputChannels,
            inputSamples = inBlockSize,
            outputs = outputChannels,
            outputSamples = outBlockSize
        )
    }
}

```

### 3. Exact Offline Buffer Rendering

For deterministic whole-file rendering with automated pre-roll warming, latency compensation, and tail overlap-add
flushing:

```kotlin
import io.github.numq.kempo.KempoStretch
import kotlin.math.roundToInt

fun renderTrack(
    rawInput: Array<FloatArray>,
    sampleRate: Float,
    timeRatio: Float = 1.0f,
    pitchSemitones: Float = -3.0f
): Array<FloatArray> {
    val channels = rawInput.size
    val totalInSamples = rawInput[0].size
    val totalOutSamples = (totalInSamples / timeRatio).roundToInt().coerceAtLeast(1)
    val rawOutput = Array(channels) { FloatArray(totalOutSamples) }

    val stretch = KempoStretch()
    stretch.presetDefault(channels, sampleRate)
    stretch.setTransposeSemitones(pitchSemitones)

    val success = stretch.exact(
        inputs = rawInput,
        inputSamples = totalInSamples,
        outputs = rawOutput,
        outputSamples = totalOutSamples
    )

    return rawOutput
}

```

### 4. Interleaved PCM-16 Serialization

Convert raw platform audio bytes (e.g., from WAV files or audio recorders) into normalized `AudioTrack` representations:

```kotlin
import io.github.numq.kempo.PcmConverter

// 1. Decode raw interleaved PCM bytes into [-1.0f, 1.0f] float buffers
val track = PcmConverter.fromPcm16(
    bytes = wavByteArray,
    channels = 2,
    sampleRate = 44100f,
    isLittleEndian = true
)

// 2. Perform transformation
val processedTrack = track.stretch(timeRatio = 1.0f, pitchSemitones = 4.0f)

// 3. Serialize back to 16-bit PCM bytes
val outputBytes = PcmConverter.toPcm16(processedTrack, isLittleEndian = true)

```

---

## Deep Dive & Parameter Tuning

### Pitch & Formant Decoupling

Classical resampling couples pitch and duration ($f_{\text{new}} = f_{\text{old}} \cdot r$). Kempo allows adjusting
duration, musical pitch, and spectral timbre completely independently:

* **`setTransposeSemitones(semitones)`**: Transposes the perceived fundamental frequency and harmonics without modifying
  playback duration.
* **`setFormantSemitones(semitones, compensatePitch)`**: Modifies the spectral envelope peak positions. Passing
  `compensatePitch = true` automatically shifts the formant envelope inversely to pitch transposition, preserving
  original vocal identities during large pitch shifts.

### Tonality Limits & Custom Frequency Maps

For experimental transformations, speech conditioning, or microtonal scales:

* **`tonalityLimit`**: Frequencies above this threshold transition from harmonic multiplicative transposition to linear
  shifting, preserving high-frequency noise and transient clarity.
* **`setFreqMap(fn: (Float) -> Float)`**: Injects a custom transfer function mapping normalized frequency bins
  `[0.0, 0.5]` to arbitrary target frequencies.

### Latency & Memory Mechanics (Zero-GC Streaming)

* **Input/Output Latencies**: Retrieve algorithmic delays using `stretch.inputLatency()` and `stretch.outputLatency()`
  to maintain sample-accurate sync across mixer tracks.
* **Pre-Allocated Workspaces**: All scratch buffers, twiddle factors, and spectral mapping indices are allocated upfront
  during `configure()` / `presetDefault()`. Calling `process()` inside real-time loops performs zero memory allocation.

### Multichannel & 5.1 Surround Pipelines

Kempo is channel-layout agnostic and accepts arbitrary channel counts (Mono, Stereo, 5.1, 7.1) via flat channel buffers:

* **Channel Independence & LFE**: Channels are processed in parallel with dynamic inter-channel phase difference (IPD)
  locking around spectral peaks. Low-frequency effects (LFE) channels can be passed directly without pre-filtering.
* **Downmixing Order Contract**:
    * **Headphone/Stereo Output (Recommended: Downmix First)**: For rendering 5.1 content over stereo headphones,
      downmixing prior to stretching (`5.1 -> Stereo Downmix -> Kempo`) is strongly recommended. It reduces CPU workload
      by ~3x (processing 2 FFTs instead of 6) and preserves battery on mobile devices while folding dialogue linearly.
    * **Discrete Passthrough (Stretch First)**: If driving discrete multi-speaker surround setups or binaural HRTF
      spatializers requiring separate 5.1 feeds, stretching all 6 channels simultaneously preserves phase alignment
      across front, surround, and center channels without comb-filtering.

---

## Benchmarks

Measured using **JMH 1.37** on OpenJDK 17 (HotSpot 64-Bit Server VM, 17.0.14) with 3 warmup iterations and 5 measurement
iterations:

### DSP & Buffer Primitives (Throughput)

| Component            | Benchmark                      | Configuration         | Throughput                      |
|----------------------|--------------------------------|-----------------------|---------------------------------|
| `FastFft`            | Forward Complex FFT            | N = 256 / 1024 / 4096 | **427.1 / 100.6 / 21.4 ops/ms** |
| `FastFft`            | Inverse Complex FFT            | N = 256 / 1024 / 4096 | **454.3 / 84.9 / 21.0 ops/ms**  |
| `ModifiedRealFFT`    | Forward Real FFT               | N = 256 / 1024 / 4096 | **569.8 / 139.8 / 27.3 ops/ms** |
| `ModifiedRealFFT`    | Inverse Real FFT               | N = 256 / 1024 / 4096 | **589.8 / 135.8 / 29.4 ops/ms** |
| `WindowedFFT`        | Forward Windowed FFT           | N = 256 / 1024 / 4096 | **613.5 / 138.4 / 33.7 ops/ms** |
| `WindowedFFT`        | Inverse Windowed FFT           | N = 256 / 1024 / 4096 | **520.6 / 155.2 / 32.9 ops/ms** |
| `WindowedFFT`        | Full Cycle (WOLA + FFT + IFFT) | N = 256 / 1024 / 4096 | **303.9 / 75.3 / 15.4 ops/ms**  |
| `MultiChannelBuffer` | Block Write                    | 1024 samples, 2 Ch    | **13 976.6 ops/ms**             |
| `MultiChannelBuffer` | Block Read                     | 1024 samples, 2 Ch    | **10 664.4 ops/ms**             |
| `MultiChannelBuffer` | Block Add                      | 1024 samples, 2 Ch    | **1 938.9 ops/ms**              |
| `MultiChannelBuffer` | Block Clear                    | 1024 samples, 2 Ch    | **27 084.5 ops/ms**             |
| `MultiChannelBuffer` | Wrap-Around Ring Copy          | 1024 samples, 2 Ch    | **9 851.9 ops/ms**              |
| `PcmConverter`       | `fromPcm16` Deserialization    | 44 100 frames, Stereo | **3.70 ops/ms**                 |
| `PcmConverter`       | `toPcm16` Serialization        | 44 100 frames, Stereo | **3.11 ops/ms**                 |

### Vocoder Execution (Latency & Speedup)

| Component            | Benchmark Mode                   | Channels   | Ratio         | Avg Time                | Real-Time Factor (RTF) |
|----------------------|----------------------------------|------------|---------------|-------------------------|------------------------|
| `KempoStretch`       | Streaming Block Latency          | 1 (Mono)   | 1.0x          | **221.0 μs**            | —                      |
| `KempoStretch`       | Streaming Block Latency          | 1 (Mono)   | 0.75x / 1.25x | **271.4 μs / 266.2 μs** | —                      |
| `KempoStretch`       | Streaming Block Latency          | 2 (Stereo) | 1.0x          | **484.6 μs**            | —                      |
| `KempoStretch`       | Streaming Block Latency          | 2 (Stereo) | 0.75x / 1.25x | **566.7 μs / 501.7 μs** | —                      |
| `KempoStretch`       | Exact Offline (1.0 s @ 44.1 kHz) | 1 (Mono)   | 1.0x          | **7.77 ms**             | **128.7x RT**          |
| `KempoStretch`       | Exact Offline (1.0 s @ 44.1 kHz) | 2 (Stereo) | 1.0x          | **14.15 ms**            | **70.7x RT**           |
| `KempoStretch`       | Exact Offline (1.0 s @ 44.1 kHz) | 2 (Stereo) | 1.25x         | **13.55 ms**            | **73.8x RT**           |
| `KempoStretch`       | Exact Offline (1.0 s @ 44.1 kHz) | 2 (Stereo) | 0.75x         | **25.08 ms**            | **39.9x RT**           |
| `AudioTrack.stretch` | Convenience High-Level API       | 1 (Mono)   | 1.0x          | **8.63 ms**             | **115.9x RT**          |
| `AudioTrack.stretch` | Convenience High-Level API       | 2 (Stereo) | 1.0x          | **14.91 ms**            | **67.1x RT**           |

---

## Credits & Acknowledgements

Kempo's core DSP architecture, modified real FFT, and phase-locking vocoder algorithms are directly based on the
research and reference implementation of
**[signalsmith-stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch)** by **Geraint Luff**
([Signalsmith Audio](https://signalsmith-audio.co.uk/)).

Special thanks to Geraint Luff for designing and publishing the mathematical foundation of this phase vocoder.

---

## License

Apache-2.0 License - see [LICENSE](LICENSE) file for details.

---

<p align="center">
  <a href="https://numq.github.io/support">
    <img src="https://api.qrserver.com/v1/create-qr-code/?size=112x112&data=https://numq.github.io/support&bgcolor=1a1b26&color=7aa2f7" 
         width="112" 
         height="112" 
         style="border-radius: 4px;" 
         alt="Support QR code">
  </a>
  <br>
  <a href="https://numq.github.io/support" style="text-decoration: none;">
    <code><font color="#bb9af7">Support Development: numq.github.io/support</font></code>
  </a>
</p>