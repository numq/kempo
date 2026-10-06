package io.github.numq.kempo.benchmark

import io.github.numq.kempo.InternalKempoApi
import io.github.numq.kempo.dsp.FastFft
import io.github.numq.kempo.dsp.ModifiedRealFFT
import io.github.numq.kempo.dsp.WindowedFFT
import kotlinx.benchmark.*
import kotlin.math.sin

/**
 * Micro-benchmarks for Fourier Transform primitives measuring execution throughput.
 */
@OptIn(InternalKempoApi::class)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
open class FftBenchmark {

    @Param("256", "1024", "4096")
    var fftSize: Int = 1024

    private lateinit var fastFft: FastFft
    private lateinit var modifiedRealFFT: ModifiedRealFFT
    private lateinit var windowedFFT: WindowedFFT

    private lateinit var complexInRe: FloatArray
    private lateinit var complexInIm: FloatArray
    private lateinit var complexOutRe: FloatArray
    private lateinit var complexOutIm: FloatArray

    private lateinit var realInput: FloatArray
    private lateinit var realOutput: FloatArray

    @Setup
    fun setup() {
        fastFft = FastFft(fftSize)
        modifiedRealFFT = ModifiedRealFFT(fftSize)
        windowedFFT = WindowedFFT(fftSize, initialRotate = fftSize / 2)

        complexInRe = FloatArray(fftSize) { i -> sin(i.toDouble()).toFloat() }
        complexInIm = FloatArray(fftSize) { 0f }
        complexOutRe = FloatArray(fftSize)
        complexOutIm = FloatArray(fftSize)

        realInput = FloatArray(fftSize) { i -> sin(i.toDouble() * 0.1).toFloat() }
        realOutput = FloatArray(fftSize)
    }

    @Benchmark
    fun fastFftForward(bh: Blackhole) {
        fastFft.fft(complexInRe, complexInIm, complexOutRe, complexOutIm)
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    @Benchmark
    fun fastFftInverse(bh: Blackhole) {
        fastFft.ifft(complexInRe, complexInIm, complexOutRe, complexOutIm)
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    @Benchmark
    fun modifiedRealFftForward(bh: Blackhole) {
        modifiedRealFFT.fft(realInput, inOffset = 0, outRe = complexOutRe, outIm = complexOutIm, outOffset = 0)
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    @Benchmark
    fun modifiedRealFftInverse(bh: Blackhole) {
        modifiedRealFFT.ifft(complexOutRe, complexOutIm, realOutput, outOffset = 0, inOffset = 0)
        bh.consume(realOutput)
    }

    @Benchmark
    fun windowedFftForward(bh: Blackhole) {
        windowedFFT.fft(
            input = realInput,
            inOffset = 0,
            outRe = complexOutRe,
            outIm = complexOutIm,
            outOffset = 0,
            withWindow = true,
            withScaling = false
        )
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    @Benchmark
    fun windowedFftInverse(bh: Blackhole) {
        windowedFFT.ifft(
            inRe = complexOutRe,
            inIm = complexOutIm,
            output = realOutput,
            outOffset = 0,
            inOffset = 0,
            withWindow = true,
            withScaling = true
        )
        bh.consume(realOutput)
    }

    @Benchmark
    fun windowedFftFullCycle(bh: Blackhole) {
        windowedFFT.fft(
            input = realInput,
            inOffset = 0,
            outRe = complexOutRe,
            outIm = complexOutIm,
            outOffset = 0,
            withWindow = true,
            withScaling = false
        )
        windowedFFT.ifft(
            inRe = complexOutRe,
            inIm = complexOutIm,
            output = realOutput,
            outOffset = 0,
            inOffset = 0,
            withWindow = true,
            withScaling = true
        )
        bh.consume(realOutput)
    }
}