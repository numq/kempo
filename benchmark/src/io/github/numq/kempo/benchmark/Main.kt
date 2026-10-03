package io.github.numq.kempo.benchmark

import java.util.*
import kotlin.system.measureNanoTime

fun main() {
    println("================================================================================")
    println("                        KEMPO DSP & STRETCH BENCHMARKS                          ")
    println("================================================================================")

    val blackhole = DummyBlackhole()

    runFftBenchmarks(blackhole)
    runBufferBenchmarks(blackhole)
    runStftBenchmarks(blackhole)
    runStreamingBenchmarks(blackhole)
    runOfflineBenchmarks(blackhole)
    runPcmBenchmarks(blackhole)
}

private fun runFftBenchmarks(bh: DummyBlackhole) {
    println("\n[1/6] Running FastFft & WindowedFFT Benchmarks (Throughput: ops/ms)")
    println("--------------------------------------------------------------------------------")
    printf("%-28s | %-10s | %-16s\n", "Benchmark", "Size", "Score (ops/ms)")
    println("--------------------------------------------------------------------------------")

    val sizes = intArrayOf(256, 1024, 4096)
    for (size in sizes) {
        val bench = FftBenchmark().apply {
            fftSize = size
            setup()
        }

        repeat(3) {
            val warmEnd = System.nanoTime() + 200_000_000L
            while (System.nanoTime() < warmEnd) {
                bench.fastFftForward(bh)
                bench.fastFftInverse(bh)
                bench.modifiedRealFftForward(bh)
                bench.modifiedRealFftInverse(bh)
                bench.windowedFftForward(bh)
                bench.windowedFftInverse(bh)
                bench.windowedFftFullCycle(bh)
            }
        }

        measureThroughput("fastFftForward", size) { bench.fastFftForward(bh) }
        measureThroughput("fastFftInverse", size) { bench.fastFftInverse(bh) }
        measureThroughput("modifiedRealFftForward", size) { bench.modifiedRealFftForward(bh) }
        measureThroughput("modifiedRealFftInverse", size) { bench.modifiedRealFftInverse(bh) }
        measureThroughput("windowedFftForward", size) { bench.windowedFftForward(bh) }
        measureThroughput("windowedFftInverse", size) { bench.windowedFftInverse(bh) }
        measureThroughput("windowedFftFullCycle", size) { bench.windowedFftFullCycle(bh) }
    }
}

private fun runBufferBenchmarks(bh: DummyBlackhole) {
    println("\n[2/6] Running MultiChannelBuffer Benchmarks (Throughput: ops/ms)")
    println("--------------------------------------------------------------------------------")
    printf("%-30s | %-10s | %-16s\n", "Benchmark", "Block", "Score (ops/ms)")
    println("--------------------------------------------------------------------------------")

    val bench = BufferBenchmark().apply {
        setup()
    }

    repeat(3) {
        val warmEnd = System.nanoTime() + 150_000_000L
        while (System.nanoTime() < warmEnd) {
            bench.benchmarkBufferWrite(bh)
            bench.benchmarkBufferRead(bh)
            bench.benchmarkBufferAdd(bh)
            bench.benchmarkBufferClear(bh)
            bench.benchmarkBufferWrapAround(bh)
        }
    }

    measureThroughput("MultiChannelBuffer.write", 1024) { bench.benchmarkBufferWrite(bh) }
    measureThroughput("MultiChannelBuffer.read", 1024) { bench.benchmarkBufferRead(bh) }
    measureThroughput("MultiChannelBuffer.add", 1024) { bench.benchmarkBufferAdd(bh) }
    measureThroughput("MultiChannelBuffer.clear", 1024) { bench.benchmarkBufferClear(bh) }
    measureThroughput("MultiChannelBuffer.wrapAround", 1024) { bench.benchmarkBufferWrapAround(bh) }
}

private fun runStftBenchmarks(bh: DummyBlackhole) {
    println("\n[3/6] Running DynamicSTFT Framing Benchmarks (Throughput: ops/ms)")
    println("--------------------------------------------------------------------------------")
    printf("%-30s | %-10s | %-16s\n", "Benchmark", "Channels", "Score (ops/ms)")
    println("--------------------------------------------------------------------------------")

    val channelConfigs = intArrayOf(1, 2)
    for (ch in channelConfigs) {
        val bench = StftBenchmark().apply {
            channels = ch
            sampleRate = 44100f
            setup()
        }

        repeat(3) {
            val warmEnd = System.nanoTime() + 150_000_000L
            while (System.nanoTime() < warmEnd) {
                bench.benchmarkAnalyse(bh)
                bench.benchmarkSynthesise(bh)
                bench.benchmarkFullCycle(bh)
            }
        }

        measureThroughput("DynamicSTFT.analyse", ch) { bench.benchmarkAnalyse(bh) }
        measureThroughput("DynamicSTFT.synthesise", ch) { bench.benchmarkSynthesise(bh) }
        measureThroughput("DynamicSTFT.fullCycle", ch) { bench.benchmarkFullCycle(bh) }
    }
}

private fun runStreamingBenchmarks(bh: DummyBlackhole) {
    println("\n[4/6] Running KempoStretch Real-Time Streaming Latency (Microseconds)")
    println("----------------------------------------------------------------------------------------")
    printf(
        "%-22s | %-8s | %-8s | %-8s | %-8s | %-14s\n", "Benchmark", "Ch", "Ratio", "Pitch", "Formant", "Block Latency"
    )
    println("----------------------------------------------------------------------------------------")

    val channelConfigs = intArrayOf(1, 2, 6) // Mono, Stereo, 5.1 Surround
    val ratios = floatArrayOf(0.50f, 0.75f, 1.0f, 1.25f, 1.50f, 2.0f)
    val pitchConfigs = floatArrayOf(0.0f, 3.0f)

    for (ch in channelConfigs) {
        for (pitch in pitchConfigs) {
            for (ratio in ratios) {
                val bench = KempoStretchBenchmark().apply {
                    channels = ch
                    sampleRate = 44100f
                    timeRatio = ratio
                    pitchSemitones = pitch
                    formantSemitones = if (pitch != 0f) 2.0f else 0.0f
                    setup()
                }

                repeat(2) {
                    val warmEnd = System.nanoTime() + 150_000_000L
                    while (System.nanoTime() < warmEnd) {
                        bench.processStreamingBlock(bh)
                    }
                }

                val iterations = 1000
                val totalTimeNs = measureNanoTime {
                    for (i in 0 until iterations) {
                        bench.processStreamingBlock(bh)
                    }
                }
                val avgMicros = (totalTimeNs / iterations) / 1_000.0
                printf(
                    "%-22s | %-8d | %-8.2fx | %-6.0fst | %-6.0fst | %10.2f μs\n",
                    "processStreamingBlock",
                    ch,
                    ratio,
                    pitch,
                    bench.formantSemitones,
                    avgMicros
                )
            }
        }
    }
}

private fun runOfflineBenchmarks(bh: DummyBlackhole) {
    println("\n[5/6] Running Exact Offline Rendering Benchmarks (Throughput & Speedup)")
    println("----------------------------------------------------------------------------------------")
    printf(
        "%-24s | %-8s | %-10s | %-12s | %-12s | %-12s\n",
        "Benchmark",
        "Duration",
        "Ch",
        "Params",
        "Time (ms)",
        "Speedup (RTF)"
    )
    println("----------------------------------------------------------------------------------------")

    val durations = floatArrayOf(1.0f, 5.0f)
    val configs = listOf(
        Triple(1.0f, 0.0f, "1.0x (Identity)"),
        Triple(1.25f, 3.0f, "1.25x (+3 st)"),
        Triple(0.80f, -4.0f, "0.80x (-4 st)")
    )

    for (dur in durations) {
        for ((ratio, pitch, label) in configs) {
            val bench = KempoStretchBenchmark().apply {
                channels = 2
                sampleRate = 44100f
                timeRatio = ratio
                pitchSemitones = pitch
                setup(offlineDurationSeconds = dur)
            }

            repeat(3) { bench.processExactOffline(bh) }

            val iters = if (dur > 2.0f) 5 else 10
            val totalNs = measureNanoTime {
                for (i in 0 until iters) {
                    bench.processExactOffline(bh)
                }
            }
            val avgMs = (totalNs / iters) / 1_000_000.0
            val rtf = (dur * 1000.0) / avgMs

            printf(
                "%-24s | %4.1f sec   | %-10d | %-12s | %9.2f ms | %8.1fx RT\n", "exact", dur, 2, label, avgMs, rtf
            )
        }
    }

    println("\n--- High-Level AudioTrack.stretch() API ---")
    val trackBench = KempoStretchBenchmark().apply {
        channels = 2
        sampleRate = 44100f
        timeRatio = 1.0f
        pitchSemitones = 3.0f
        setup(offlineDurationSeconds = 1.0f)
    }
    repeat(3) { trackBench.processAudioTrackConvenience(bh) }
    val trackNs = measureNanoTime {
        for (i in 0 until 10) {
            trackBench.processAudioTrackConvenience(bh)
        }
    }
    val avgTrackMs = (trackNs / 10) / 1_000_000.0
    val trackRtf = 1000.0 / avgTrackMs
    printf("AudioTrack.stretch (Stereo 1.0s, +3 st): %.2f ms (%.1fx Realtime)\n", avgTrackMs, trackRtf)
    println("================================================================================")
}

private fun runPcmBenchmarks(bh: DummyBlackhole) {
    println("\n[6/6] Running PcmConverter Serialization Benchmarks (Throughput: ops/ms)")
    println("--------------------------------------------------------------------------------")
    printf("%-30s | %-10s | %-16s\n", "Benchmark", "Frames", "Score (ops/ms)")
    println("--------------------------------------------------------------------------------")

    val bench = PcmBenchmark().apply {
        setup()
    }

    repeat(3) {
        val warmEnd = System.nanoTime() + 150_000_000L
        while (System.nanoTime() < warmEnd) {
            bench.fromPcm16(bh)
            bench.toPcm16(bh)
        }
    }

    measureThroughput("PcmConverter.fromPcm16", bench.frames) { bench.fromPcm16(bh) }
    measureThroughput("PcmConverter.toPcm16", bench.frames) { bench.toPcm16(bh) }
    println("================================================================================")
}

private fun measureThroughput(name: String, size: Int, block: () -> Unit) {
    val durationNs = 1_000_000_000L
    var ops = 0L
    val start = System.nanoTime()
    val deadline = start + durationNs
    while (System.nanoTime() < deadline) {
        block()
        ops++
    }
    val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
    val opsPerMs = ops / elapsedMs
    printf("%-30s | %-10d | %10.2f ops/ms\n", name, size, opsPerMs)
}

private fun printf(format: String, vararg args: Any) {
    print(String.format(Locale.US, format, *args))
}