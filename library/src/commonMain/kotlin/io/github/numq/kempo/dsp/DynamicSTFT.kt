package io.github.numq.kempo.dsp

import io.github.numq.kempo.InternalKempoApi
import io.github.numq.kempo.buffer.MultiChannelBuffer
import kotlin.math.max

/**
 * Dynamic Short-Time Fourier Transform (STFT) framing and overlap-add resynthesis engine.
 */
@InternalKempoApi
class DynamicSTFT(
    var channels: Int = 0, var blockSamples: Int = 0, var intervalSamples: Int = 0
) {
    val fft = WindowedFFT()

    var input = MultiChannelBuffer(channels, 1)
    var output = MultiChannelBuffer(channels, 1)

    var bands: Int = 0
        private set

    var spectrumRe = FloatArray(0)
    var spectrumIm = FloatArray(0)

    private var timeWorkBuffer = FloatArray(0)
    private var synthWorkBuffer = FloatArray(0)

    val kaiser = 0

    fun configure(nInChannels: Int, nOutChannels: Int, block: Int, interval: Int) {
        channels = max(nInChannels, nOutChannels)
        blockSamples = block
        intervalSamples = interval

        fft.setKaiser(blockSamples, intervalSamples, rotateToCenter = true)
        bands = fft.size / 2

        input = MultiChannelBuffer(channels, blockSamples * 4 + intervalSamples)
        output = MultiChannelBuffer(channels, blockSamples * 4 + intervalSamples)

        spectrumRe = FloatArray(channels * bands)
        spectrumIm = FloatArray(channels * bands)
        timeWorkBuffer = FloatArray(fft.size)
        synthWorkBuffer = FloatArray(fft.size)
    }

    fun setInterval(interval: Int, windowType: Int = 0) {
        intervalSamples = interval
        fft.setKaiser(blockSamples, intervalSamples, rotateToCenter = true)
    }

    fun reset(fillValue: Float = 0f) {
        input.reset(fillValue)
        output.reset(0f)
        spectrumRe.fill(0f)
        spectrumIm.fill(0f)
    }

    fun analysisLatency(): Int = blockSamples / 2
    fun synthesisLatency(): Int = blockSamples / 2
    fun defaultInterval(): Int = intervalSamples
    fun fftSamples(): Int = fft.size

    fun binToFreq(bin: Float): Float = (bin + 0.5f) / fft.size
    fun freqToBin(freq: Float): Float = freq * fft.size - 0.5f

    fun analyseSteps(): Int = channels
    fun synthesiseSteps(): Int = channels

    fun writeInput(channel: Int, length: Int, data: FloatArray, offset: Int = 0) {
        input.write(channel, 0, data, offset, length)
    }

    fun moveInput(length: Int) {
        input.head += length
    }

    fun readOutput(channel: Int, length: Int, out: FloatArray, offset: Int = 0, offsetFromHead: Int = 0) {
        output.read(channel, offsetFromHead, out, offset, length, clearAfterRead = (offsetFromHead == 0))
    }

    fun moveOutput(length: Int) {
        output.head += length
    }

    fun addOutput(channel: Int, length: Int, data: FloatArray, offset: Int = 0) {
        output.add(channel, 0, data, offset, length)
    }

    fun analyseStep(step: Int, timeOffset: Int = 0) {
        val c = step
        val inOffset = -blockSamples - timeOffset
        input.read(c, inOffset, timeWorkBuffer, 0, blockSamples)
        for (i in blockSamples until fft.size) timeWorkBuffer[i] = 0f

        val chBase = c * bands
        fft.fft(
            input = timeWorkBuffer,
            inOffset = 0,
            outRe = spectrumRe,
            outIm = spectrumIm,
            outOffset = chBase,
            withWindow = true,
            withScaling = false
        )
    }

    fun synthesiseStep(step: Int) {
        val c = step
        val chBase = c * bands

        output.clear(c, blockSamples, intervalSamples)

        fft.ifft(
            inRe = spectrumRe,
            inIm = spectrumIm,
            output = synthWorkBuffer,
            outOffset = 0,
            inOffset = chBase,
            withWindow = true,
            withScaling = true
        )

        output.add(c, 0, synthWorkBuffer, 0, blockSamples)
    }

    fun finishOutput(extraIntervals: Int) {}
}