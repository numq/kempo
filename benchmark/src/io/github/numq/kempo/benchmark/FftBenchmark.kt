package io.github.numq.kempo.benchmark

import io.github.numq.kempo.dsp.FastFft
import io.github.numq.kempo.dsp.ModifiedRealFFT
import io.github.numq.kempo.dsp.WindowedFFT
import kotlin.math.sin

/**
 * Micro-benchmarks for Fourier Transform primitives:
 * - [FastFft]: In-place mixed-radix complex FFT / IFFT.
 * - [ModifiedRealFFT]: Half-bin shifted real-to-complex transform.
 * - [WindowedFFT]: WOLA windowing, rotation, and forward/inverse transforms.
 */
class FftBenchmark {
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

    fun fastFftForward(bh: DummyBlackhole) {
        fastFft.fft(complexInRe, complexInIm, complexOutRe, complexOutIm)
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    fun fastFftInverse(bh: DummyBlackhole) {
        fastFft.ifft(complexInRe, complexInIm, complexOutRe, complexOutIm)
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    fun modifiedRealFftForward(bh: DummyBlackhole) {
        modifiedRealFFT.fft(realInput, inOffset = 0, outRe = complexOutRe, outIm = complexOutIm, outOffset = 0)
        bh.consume(complexOutRe)
        bh.consume(complexOutIm)
    }

    fun modifiedRealFftInverse(bh: DummyBlackhole) {
        modifiedRealFFT.ifft(complexOutRe, complexOutIm, realOutput, outOffset = 0, inOffset = 0)
        bh.consume(realOutput)
    }

    fun windowedFftForward(bh: DummyBlackhole) {
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

    fun windowedFftInverse(bh: DummyBlackhole) {
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

    fun windowedFftFullCycle(bh: DummyBlackhole) {
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