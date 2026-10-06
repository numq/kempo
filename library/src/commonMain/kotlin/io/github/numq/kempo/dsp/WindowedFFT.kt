package io.github.numq.kempo.dsp

import io.github.numq.kempo.InternalKempoApi

/**
 * Windowed STFT frame processor with circular centering rotation and WOLA normalization.
 */
@InternalKempoApi
class WindowedFFT(initialSize: Int = 0, initialRotate: Int = 0) {
    val mrfft = ModifiedRealFFT()

    var window = FloatArray(0)
        private set

    private var timeBuffer = FloatArray(0)

    var offsetSamples: Int = 0
        private set

    val size: Int get() = mrfft.size

    init {
        if (initialSize > 0) {
            setSize(initialSize, initialRotate)
        }
    }

    fun setSize(newSize: Int, rotateSamples: Int = 0) {
        setSizeWindow(newSize, rotateSamples)
    }

    fun setSizeWindow(newSize: Int, rotateSamples: Int = 0): FloatArray {
        mrfft.setSize(newSize)
        if (window.size != newSize) {
            window = FloatArray(newSize) { 1f }
            timeBuffer = FloatArray(newSize)
        } else {
            window.fill(1f)
        }
        offsetSamples = rotateSamples
        if (offsetSamples < 0) offsetSamples += newSize
        return window
    }

    fun setKaiser(windowSize: Int, interval: Int, rotateToCenter: Boolean = true) {
        val fftSize = fastSizeAbove(windowSize)
        val rot = if (rotateToCenter) windowSize / 2 else 0
        setSizeWindow(fftSize, rot)

        val kaiser = Windows.Kaiser.withBandwidth(windowSize.toDouble() / interval, heuristicOptimal = true)
        kaiser.fill(window, windowSize)
        Windows.forcePerfectReconstruction(window, windowSize, interval)

        for (i in windowSize until fftSize) {
            window[i] = 0f
        }
    }

    fun fft(
        input: FloatArray,
        inOffset: Int,
        outRe: FloatArray,
        outIm: FloatArray,
        outOffset: Int = 0,
        withWindow: Boolean = true,
        withScaling: Boolean = false
    ) {
        val fftSize = size
        val norm = if (withScaling) 1f / fftSize else 1f

        for (i in 0 until offsetSamples) {
            val w = if (withWindow) window[i] else 1f
            timeBuffer[i + fftSize - offsetSamples] = -input[inOffset + i] * norm * w
        }
        for (i in offsetSamples until fftSize) {
            val w = if (withWindow) window[i] else 1f
            timeBuffer[i - offsetSamples] = input[inOffset + i] * norm * w
        }

        mrfft.fft(timeBuffer, 0, outRe, outIm, outOffset)
    }

    fun ifft(
        inRe: FloatArray,
        inIm: FloatArray,
        output: FloatArray,
        outOffset: Int,
        inOffset: Int = 0,
        withWindow: Boolean = true,
        withScaling: Boolean = true
    ) {
        mrfft.ifft(inRe, inIm, timeBuffer, 0, inOffset)
        val fftSize = mrfft.size
        val norm = if (withScaling) 1f / fftSize else 1f

        for (i in 0 until offsetSamples) {
            val w = if (withWindow) window[i] else 1f
            output[outOffset + i] = -timeBuffer[i + fftSize - offsetSamples] * norm * w
        }
        for (i in offsetSamples until fftSize) {
            val w = if (withWindow) window[i] else 1f
            output[outOffset + i] = timeBuffer[i - offsetSamples] * norm * w
        }
    }

    companion object {
        fun fastSizeAbove(size: Int): Int = ModifiedRealFFT.fastSizeAbove(size)
    }
}