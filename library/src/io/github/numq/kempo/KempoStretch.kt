package io.github.numq.kempo

import io.github.numq.kempo.buffer.MultiChannelBuffer
import io.github.numq.kempo.dsp.DynamicSTFT
import kotlin.math.*
import kotlin.random.Random

/**
 * High-performance, multichannel phase-locked vocoder for real-time and offline time-stretching and pitch-shifting.
 *
 * Implements horizontal phase accumulation, vertical harmonic phase-locking around spectral peaks,
 * and inter-channel phase coherence to prevent transient smearing and stereo image collapse.
 */
@OptIn(InternalKempoApi::class)
class KempoStretch(seed: Long = 1337L) {
    companion object {
        const val NOISE_FLOOR = 1e-15f
        const val MAX_CLEAN_STRETCH = 2.0f
        const val SPLIT_MAIN_PREDICTION = 8
        const val SMOOTH_ENERGY_STEPS = 3
    }

    private var random = Random(seed)

    val stft = DynamicSTFT()

    private var stashedInput = MultiChannelBuffer(0, 1)
    private var stashedOutput = MultiChannelBuffer(0, 1)

    private var splitComputation = false
    private var channels = 0
    private var bands = 0

    private var bandInputRe = FloatArray(0)
    private var bandInputIm = FloatArray(0)
    private var bandPrevInputRe = FloatArray(0)
    private var bandPrevInputIm = FloatArray(0)
    private var bandOutputRe = FloatArray(0)
    private var bandOutputIm = FloatArray(0)
    private var bandInputEnergy = FloatArray(0)

    private var predEnergy = FloatArray(0)
    private var predInputRe = FloatArray(0)
    private var predInputIm = FloatArray(0)

    private class Peak(val input: Float, val output: Float)

    private val peaks = ArrayList<Peak>()
    private var energy = FloatArray(0)
    private var smoothedEnergy = FloatArray(0)

    private var mapInputBin = FloatArray(0)
    private var mapFreqGrad = FloatArray(0)

    private var formantMetric = FloatArray(0)
    private var formantBaseFreq = 0f
    private var freqEstimate = 0f
    private var freqEstimateWeighted = 0f
    private var freqEstimateWeight = 0f

    private var freqMultiplier = 1f
    private var freqTonalityLimit = 0.5f
    private var customFreqMap: ((Float) -> Float)? = null

    private var formantCompensation = false
    private var formantMultiplier = 1f
    private var invFormantMultiplier = 1f

    private var tmpProcessBuffer = FloatArray(0)
    private var tmpPreRollBuffer = FloatArray(0)
    private var preRollOutput = Array(0) { FloatArray(0) }
    private var zeroBlockBuffer = FloatArray(0)

    private var prevInputOffset = -1
    private var didSeek = false
    private var seekTimeFactor = 1f
    private var silenceCounter = 0
    private var silenceFirst = true
    private var smoothEnergyState = 0f
    private var processSpectrumSteps = 0

    private class BlockProcessState {
        var samplesSinceLast = Int.MAX_VALUE
        var steps = 0
        var step = 0
        var newSpectrum = false
        var reanalysePrev = false
        var mappedFrequencies = false
        var processFormants = false
        var timeFactor = 1f

        fun reset() {
            samplesSinceLast = Int.MAX_VALUE
            steps = 0
            step = 0
            newSpectrum = false
            reanalysePrev = false
            mappedFrequencies = false
            processFormants = false
            timeFactor = 1f
        }
    }

    private val blockProcess = BlockProcessState()
    private val fracBuf = FloatArray(2)
    private val singleSampleBuf = FloatArray(1)

    fun inputLatency(): Int = stft.analysisLatency()
    fun outputLatency(): Int = stft.synthesisLatency() + if (splitComputation) stft.defaultInterval() else 0

    fun reset() {
        stft.reset(0f)
        stashedInput.copyFrom(stft.input)
        stashedOutput.copyFrom(stft.output)

        prevInputOffset = -1
        val total = channels * bands
        for (i in 0 until total) {
            bandInputRe[i] = 0f
            bandInputIm[i] = 0f
            bandPrevInputRe[i] = 0f
            bandPrevInputIm[i] = 0f
            bandOutputRe[i] = 0f
            bandOutputIm[i] = 0f
            bandInputEnergy[i] = 0f
        }

        silenceCounter = 2 * stft.blockSamples
        silenceFirst = false
        didSeek = false
        blockProcess.reset()
        freqEstimateWeighted = 0f
        freqEstimateWeight = 0f
    }

    fun presetDefault(nChannels: Int, sampleRate: Float, split: Boolean = false) {
        configure(nChannels, (sampleRate * 0.12f).toInt(), (sampleRate * 0.03f).toInt(), split)
    }

    fun presetCheaper(nChannels: Int, sampleRate: Float, split: Boolean = true) {
        configure(nChannels, (sampleRate * 0.10f).toInt(), (sampleRate * 0.04f).toInt(), split)
    }

    fun configure(nChannels: Int, blockSamples: Int, intervalSamples: Int, split: Boolean = false) {
        splitComputation = split
        channels = nChannels
        stft.configure(channels, channels, blockSamples, intervalSamples + 1)
        stft.setInterval(intervalSamples, stft.kaiser)
        stft.reset(0.1f)

        stashedInput = MultiChannelBuffer(channels, stft.input.capacity)
        stashedOutput = MultiChannelBuffer(channels, stft.output.capacity)
        stashedInput.copyFrom(stft.input)
        stashedOutput.copyFrom(stft.output)

        bands = stft.bands
        val totalBands = bands * channels

        bandInputRe = FloatArray(totalBands)
        bandInputIm = FloatArray(totalBands)
        bandPrevInputRe = FloatArray(totalBands)
        bandPrevInputIm = FloatArray(totalBands)
        bandOutputRe = FloatArray(totalBands)
        bandOutputIm = FloatArray(totalBands)
        bandInputEnergy = FloatArray(totalBands)

        predEnergy = FloatArray(totalBands)
        predInputRe = FloatArray(totalBands)
        predInputIm = FloatArray(totalBands)

        energy = FloatArray(bands)
        smoothedEnergy = FloatArray(bands)
        mapInputBin = FloatArray(bands)
        mapFreqGrad = FloatArray(bands)

        blockProcess.reset()
        formantMetric = FloatArray(bands + 2)

        tmpProcessBuffer = FloatArray(blockSamples + intervalSamples)
        val outLat = outputLatency()
        tmpPreRollBuffer = FloatArray(outLat * channels)
        preRollOutput = Array(channels) { FloatArray(outLat) }
    }

    fun setTransposeFactor(multiplier: Float, tonalityLimit: Float = 0f) {
        freqMultiplier = multiplier
        freqTonalityLimit = if (tonalityLimit > 0f) tonalityLimit / sqrt(multiplier) else 1f
        customFreqMap = null
    }

    fun setTransposeSemitones(semitones: Float, tonalityLimit: Float = 0f) {
        setTransposeFactor(2.0f.pow(semitones / 12f), tonalityLimit)
    }

    fun setFreqMap(fn: (Float) -> Float) {
        customFreqMap = fn
    }

    fun setFormantFactor(multiplier: Float, compensatePitch: Boolean = false) {
        formantMultiplier = multiplier
        invFormantMultiplier = 1f / multiplier
        formantCompensation = compensatePitch
    }

    fun setFormantSemitones(semitones: Float, compensatePitch: Boolean = false) {
        setFormantFactor(2.0f.pow(semitones / 12f), compensatePitch)
    }

    fun setFormantBase(baseFreq: Float = 0f) {
        formantBaseFreq = baseFreq
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun norm(r: Float, i: Float): Float = r * r + i * i

    private fun getFractionalScalar(arr: FloatArray, chBase: Int, inputIndex: Float): Float {
        if (inputIndex <= 0f) return arr[chBase]
        if (inputIndex >= bands - 1) return arr[chBase + bands - 1]
        val low = inputIndex.toInt()
        val frac = inputIndex - low
        val v0 = arr[chBase + low]
        val v1 = arr[chBase + low + 1]
        return v0 + (v1 - v0) * frac
    }

    private fun getFractionalComplex(
        reArr: FloatArray, imArr: FloatArray, chBase: Int, inputIndex: Float, out: FloatArray
    ) {
        if (inputIndex <= 0f) {
            out[0] = reArr[chBase]
            out[1] = imArr[chBase]
            return
        }
        if (inputIndex >= bands - 1) {
            out[0] = reArr[chBase + bands - 1]
            out[1] = imArr[chBase + bands - 1]
            return
        }
        val low = inputIndex.toInt()
        val frac = inputIndex - low
        val r0 = reArr[chBase + low]
        val i0 = imArr[chBase + low]
        val r1 = reArr[chBase + low + 1]
        val i1 = imArr[chBase + low + 1]
        out[0] = r0 + (r1 - r0) * frac
        out[1] = i0 + (i1 - i0) * frac
    }

    private fun mapFreq(freq: Float): Float {
        customFreqMap?.let { return it(freq) }
        return if (freq > freqTonalityLimit) {
            freq + (freqMultiplier - 1f) * freqTonalityLimit
        } else {
            freq * freqMultiplier
        }
    }

    private fun invMapFormant(freq: Float): Float {
        return if (freq * invFormantMultiplier > freqTonalityLimit) {
            freq + (1f - formantMultiplier) * freqTonalityLimit
        } else {
            freq * invFormantMultiplier
        }
    }

    private fun updateProcessSpectrumSteps() {
        processSpectrumSteps = 0
        if (blockProcess.newSpectrum) processSpectrumSteps += channels
        if (blockProcess.mappedFrequencies) {
            processSpectrumSteps += SMOOTH_ENERGY_STEPS
            processSpectrumSteps += 1
        }
        processSpectrumSteps += 1
        processSpectrumSteps += channels
        processSpectrumSteps += SPLIT_MAIN_PREDICTION
        if (blockProcess.newSpectrum) processSpectrumSteps += 1
        if (blockProcess.processFormants) processSpectrumSteps += 3
    }

    private fun smoothEnergy(step: Int, smoothingBins: Float) {
        val smoothingSlew = 1f / (1f + smoothingBins * 0.5f)
        if (step == 0) {
            energy.fill(0f)
            for (c in 0 until channels) {
                val chBase = c * bands
                for (b in 0 until bands) {
                    val idx = chBase + b
                    val e = norm(bandInputRe[idx], bandInputIm[idx])
                    bandInputEnergy[idx] = e
                    energy[b] += e
                }
            }
            energy.copyInto(smoothedEnergy)
            smoothEnergyState = 0f
            return
        }

        var e = smoothEnergyState
        for (b in bands - 1 downTo 0) {
            e += (smoothedEnergy[b] - e) * smoothingSlew
            smoothedEnergy[b] = e
        }
        for (b in 0 until bands) {
            e += (smoothedEnergy[b] - e) * smoothingSlew
            smoothedEnergy[b] = e
        }
        smoothEnergyState = e
    }

    private fun findPeaks() {
        peaks.clear()
        var start = 0
        while (start < bands) {
            if (energy[start] > smoothedEnergy[start]) {
                var end = start
                var bandSum = 0f
                var energySum = 0f
                while (end < bands && energy[end] > smoothedEnergy[end]) {
                    val e = energy[end]
                    bandSum += end * e
                    energySum += e
                    end++
                }
                if (energySum > NOISE_FLOOR) {
                    val avgBand = bandSum / energySum
                    val avgFreq = stft.binToFreq(avgBand)
                    val targetBand = stft.freqToBin(mapFreq(avgFreq)).coerceIn(0f, (bands - 1).toFloat())
                    peaks.add(Peak(avgBand, targetBand))
                }
                start = end
            }
            start++
        }
    }

    private fun updateOutputMap() {
        if (peaks.isEmpty()) {
            for (b in 0 until bands) {
                mapInputBin[b] = b.toFloat()
                mapFreqGrad[b] = 1f
            }
            return
        }

        val bottomOffset = peaks[0].input - peaks[0].output
        val firstOutCeil = min(bands, max(0, ceil(peaks[0].output).toInt()))
        for (b in 0 until firstOutCeil) {
            mapInputBin[b] = (b + bottomOffset).coerceIn(0f, (bands - 1).toFloat())
            mapFreqGrad[b] = 1f
        }

        for (p in 1 until peaks.size) {
            val prev = peaks[p - 1]
            val next = peaks[p]
            val diffOut = next.output - prev.output
            if (diffOut <= 1e-6f) continue

            val rangeScale = 1f / diffOut
            val outOffset = prev.input - prev.output
            val outScale = next.input - next.output - prev.input + prev.output
            val gradScale = outScale * rangeScale
            val startBin = max(0, ceil(prev.output).toInt())
            val endBin = min(bands, ceil(next.output).toInt())

            for (b in startBin until endBin) {
                val r = (b - prev.output) * rangeScale
                val h = r * r * (3f - 2f * r)
                mapInputBin[b] = (b + outOffset + h * outScale).coerceIn(0f, (bands - 1).toFloat())
                val gradH = 6f * r * (1f - r)
                mapFreqGrad[b] = max(0f, 1f + gradH * gradScale)
            }
        }

        val topOffset = peaks.last().input - peaks.last().output
        val lastOutFloor = max(0, min(bands, peaks.last().output.toInt()))
        for (b in lastOutFloor until bands) {
            mapInputBin[b] = (b + topOffset).coerceIn(0f, (bands - 1).toFloat())
            mapFreqGrad[b] = 1f
        }
    }

    private fun estimateFrequency(): Float {
        var p0 = 0
        var p1 = 0
        var p2 = 0
        for (b in 1 until bands - 1) {
            val e = formantMetric[b]
            if (e < formantMetric[b - 1] || e <= formantMetric[b + 1]) continue
            if (e > formantMetric[p0]) {
                if (e > formantMetric[p1]) {
                    if (e > formantMetric[p2]) {
                        p0 = p1; p1 = p2; p2 = b
                    } else {
                        p0 = p1; p1 = b
                    }
                } else {
                    p0 = b
                }
            }
        }

        var peakEstimate = p2
        if (formantMetric[p1] > formantMetric[p2] * 0.1f) {
            val diff = abs(peakEstimate - p1)
            if (diff > peakEstimate / 8 && diff < peakEstimate * 7 / 8) peakEstimate %= diff
            if (formantMetric[p0] > formantMetric[p2] * 0.01f) {
                val diff0 = abs(peakEstimate - p0)
                if (diff0 > peakEstimate / 8 && diff0 < peakEstimate * 7 / 8) peakEstimate %= diff0
            }
        }
        val weight = formantMetric[p2]
        freqEstimateWeighted += (peakEstimate * weight - freqEstimateWeighted) * 0.25f
        freqEstimateWeight += (weight - freqEstimateWeight) * 0.25f
        return freqEstimateWeighted / (freqEstimateWeight + 1e-30f)
    }

    private fun updateFormants(step: Int) {
        when (step) {
            0 -> {
                formantMetric.fill(0f)
                for (c in 0 until channels) {
                    val chBase = c * bands
                    for (b in 0 until bands) {
                        formantMetric[b] += bandInputEnergy[chBase + b]
                    }
                }
                freqEstimate = if (formantBaseFreq <= 0f) estimateFrequency() else stft.freqToBin(formantBaseFreq)
            }

            1 -> {
                var decay = 1f - 1f / (freqEstimate * 0.5f + 1f)
                var e = 0f
                repeat(2) {
                    for (b in bands - 1 downTo 0) {
                        e = max(formantMetric[b], e * decay)
                        formantMetric[b] = e
                    }
                    for (b in 0 until bands) {
                        e = max(formantMetric[b], e * decay)
                        formantMetric[b] = e
                    }
                }
                decay = 1f / decay
                repeat(2) {
                    for (b in bands - 1 downTo 0) {
                        e = min(formantMetric[b], e * decay)
                        formantMetric[b] = e
                    }
                    for (b in 0 until bands) {
                        e = min(formantMetric[b], e * decay)
                        formantMetric[b] = e
                    }
                }
            }

            2 -> {
                for (b in 0 until bands) {
                    val inF = stft.binToFreq(b.toFloat())
                    val outF = invMapFormant(if (formantCompensation) mapFreq(inF) else inF)
                    val targetBand = stft.freqToBin(outF).coerceIn(0f, (bands - 1).toFloat())

                    val floorB = targetBand.toInt()
                    val fracB = targetBand - floorB
                    val lowE = formantMetric[floorB]
                    val highE = formantMetric[floorB + 1]
                    val targetE = lowE + (highE - lowE) * fracB

                    val inputE = formantMetric[b]
                    val energyRatio = targetE / (inputE + 1e-30f)

                    for (c in 0 until channels) {
                        bandInputEnergy[c * bands + b] *= energyRatio
                    }
                }
            }
        }
    }

    private fun rotateBands(c: Int) {
        val chBase = c * bands
        val interval2Pi = stft.defaultInterval() * 2.0 * PI
        val phase = (stft.binToFreq(0f) * interval2Pi).toFloat()
        val freqStep = stft.binToFreq(1f) - stft.binToFreq(0f)
        val phaseStep = (freqStep * interval2Pi).toFloat()

        val rotStepR = cos(phaseStep.toDouble()).toFloat()
        val rotStepI = sin(phaseStep.toDouble()).toFloat()
        var rotR = cos(phase.toDouble()).toFloat()
        var rotI = sin(phase.toDouble()).toFloat()

        for (b in 0 until bands) {
            val idx = chBase + b
            val outR = bandOutputRe[idx]
            val outI = bandOutputIm[idx]
            bandOutputRe[idx] = outR * rotR - outI * rotI
            bandOutputIm[idx] = outR * rotI + outI * rotR

            val prevR = bandPrevInputRe[idx]
            val prevI = bandPrevInputIm[idx]
            bandPrevInputRe[idx] = prevR * rotR - prevI * rotI
            bandPrevInputIm[idx] = prevR * rotI + prevI * rotR

            val nextRotR = rotR * rotStepR - rotI * rotStepI
            val nextRotI = rotR * rotStepI + rotI * rotStepR
            rotR = nextRotR
            rotI = nextRotI
        }
    }

    private fun preliminaryPrediction(c: Int) {
        val chBase = c * bands
        for (b in 0 until bands) {
            val inBin = mapInputBin[b]
            val prevE = predEnergy[chBase + b]

            val curE = getFractionalScalar(bandInputEnergy, chBase, inBin) * max(0f, mapFreqGrad[b])
            predEnergy[chBase + b] = curE

            getFractionalComplex(bandInputRe, bandInputIm, chBase, inBin, fracBuf)
            val pInR = fracBuf[0]
            val pInI = fracBuf[1]
            predInputRe[chBase + b] = pInR
            predInputIm[chBase + b] = pInI

            getFractionalComplex(bandPrevInputRe, bandPrevInputIm, chBase, inBin, fracBuf)
            val prevInR = fracBuf[0]
            val prevInI = fracBuf[1]

            val twistR = prevInR * pInR + prevInI * pInI
            val twistI = prevInR * pInI - prevInI * pInR

            val outIdx = chBase + b
            val oR = bandOutputRe[outIdx]
            val oI = bandOutputIm[outIdx]
            val phR = oR * twistR - oI * twistI
            val phI = oR * twistI + oI * twistR

            val scale = 1f / (max(prevE, curE) + NOISE_FLOOR)
            bandOutputRe[outIdx] = if (scale.isNaN()) 0f else phR * scale
            bandOutputIm[outIdx] = if (scale.isNaN()) 0f else phI * scale
        }
    }

    private fun verticalPhaseLock(
        chunk: Int,
        timeFactor: Float,
        randomTimeFactor: Boolean,
        randScale: Float,
        randRange: Float,
        longVerticalStep: Int
    ) {
        val startB = (bands * chunk) / SPLIT_MAIN_PREDICTION
        val endB = (bands * (chunk + 1)) / SPLIT_MAIN_PREDICTION

        for (b in startB until endB) {
            var maxCh = 0
            var maxE = predEnergy[b]
            for (c in 1 until channels) {
                val e = predEnergy[c * bands + b]
                if (e > maxE) {
                    maxCh = c
                    maxE = e
                }
            }

            val maxBase = maxCh * bands
            val predR = predInputRe[maxBase + b]
            val predI = predInputIm[maxBase + b]
            val mapBin = mapInputBin[b]

            var phaseR = 0f
            var phaseI = 0f

            if (b > 0) {
                val tf = if (randomTimeFactor) randScale + random.nextFloat() * randRange else timeFactor
                getFractionalComplex(bandInputRe, bandInputIm, maxBase, mapBin - tf, fracBuf)
                val dInR = fracBuf[0]
                val dInI = fracBuf[1]
                val twR = dInR * predR + dInI * predI
                val twI = dInR * predI - dInI * predR

                val downOutR = bandOutputRe[maxBase + b - 1]
                val downOutI = bandOutputIm[maxBase + b - 1]
                phaseR += downOutR * twR - downOutI * twI
                phaseI += downOutR * twI + downOutI * twR

                if (b >= longVerticalStep) {
                    getFractionalComplex(bandInputRe, bandInputIm, maxBase, mapBin - longVerticalStep * tf, fracBuf)
                    val lInR = fracBuf[0]
                    val lInI = fracBuf[1]
                    val lTwR = lInR * predR + lInI * predI
                    val lTwI = lInR * predI - lInI * predR

                    val lDownOutR = bandOutputRe[maxBase + b - longVerticalStep]
                    val lDownOutI = bandOutputIm[maxBase + b - longVerticalStep]
                    phaseR += lDownOutR * lTwR - lDownOutI * lTwI
                    phaseI += lDownOutR * lTwI + lDownOutI * lTwR
                }
            }

            if (b < bands - 1) {
                val upMapBin = mapInputBin[b + 1]
                val upPredR = predInputRe[maxBase + b + 1]
                val upPredI = predInputIm[maxBase + b + 1]

                val tf = if (randomTimeFactor) randScale + random.nextFloat() * randRange else timeFactor
                getFractionalComplex(bandInputRe, bandInputIm, maxBase, upMapBin - tf, fracBuf)
                val dInR = fracBuf[0]
                val dInI = fracBuf[1]
                val twR = dInR * upPredR + dInI * upPredI
                val twI = dInR * upPredI - dInI * upPredR

                val upOutR = bandOutputRe[maxBase + b + 1]
                val upOutI = bandOutputIm[maxBase + b + 1]
                phaseR += twR * upOutR + twI * upOutI
                phaseI += twR * upOutI - twI * upOutR

                if (b < bands - longVerticalStep) {
                    val lUpMapBin = mapInputBin[b + longVerticalStep]
                    val lUpPredR = predInputRe[maxBase + b + longVerticalStep]
                    val lUpPredI = predInputIm[maxBase + b + longVerticalStep]

                    getFractionalComplex(bandInputRe, bandInputIm, maxBase, lUpMapBin - longVerticalStep * tf, fracBuf)
                    val ldInR = fracBuf[0]
                    val ldInI = fracBuf[1]
                    val lTwR = ldInR * lUpPredR + ldInI * lUpPredI
                    val lTwI = ldInR * lUpPredI - ldInI * lUpPredR

                    val lUpOutR = bandOutputRe[maxBase + b + longVerticalStep]
                    val lUpOutI = bandOutputIm[maxBase + b + longVerticalStep]
                    phaseR += lTwR * lUpOutR + lTwI * lUpOutI
                    phaseI += lTwR * lUpOutI - lTwI * lUpOutR
                }
            }

            var pNorm = norm(phaseR, phaseI)
            var usePhaseR = phaseR
            var usePhaseI = phaseI
            if (pNorm <= NOISE_FLOOR || pNorm.isNaN()) {
                usePhaseR = predR; usePhaseI = predI
                pNorm = norm(predR, predI) + NOISE_FLOOR
            }
            val scale = sqrt(predEnergy[maxBase + b] / pNorm)
            var outR = usePhaseR * scale
            var outI = usePhaseI * scale

            if (predEnergy[maxBase + b] <= NOISE_FLOOR || scale.isNaN()) {
                outR = 0f
                outI = 0f
            }

            bandOutputRe[maxBase + b] = outR
            bandOutputIm[maxBase + b] = outI

            for (c in 0 until channels) {
                if (c != maxCh) {
                    val chB = c * bands + b
                    val cPredR = predInputRe[chB]
                    val cPredI = predInputIm[chB]
                    val chTwistR = predR * cPredR + predI * cPredI
                    val chTwistI = predR * cPredI - predI * cPredR

                    val chPhaseR = outR * chTwistR - outI * chTwistI
                    val chPhaseI = outR * chTwistI + outI * chTwistR

                    var chNorm = norm(chPhaseR, chPhaseI)
                    var chUseR = chPhaseR
                    var chUseI = chPhaseI
                    if (chNorm <= NOISE_FLOOR || chNorm.isNaN()) {
                        chUseR = cPredR; chUseI = cPredI
                        chNorm = norm(cPredR, cPredI) + NOISE_FLOOR
                    }
                    val chScale = sqrt(predEnergy[chB] / chNorm)
                    var chOutR = chUseR * chScale
                    var chOutI = chUseI * chScale

                    if (predEnergy[chB] <= NOISE_FLOOR || chScale.isNaN()) {
                        chOutR = 0f
                        chOutI = 0f
                    }

                    bandOutputRe[chB] = chOutR
                    bandOutputIm[chB] = chOutI
                }
            }
        }
    }

    private fun processSpectrum(stepIdx: Int) {
        var step = stepIdx
        var timeFactor = blockProcess.timeFactor
        val smoothingBins = stft.fftSamples().toFloat() / stft.defaultInterval()
        val longVerticalStep = round(smoothingBins).toInt()

        timeFactor = max(timeFactor, 1f / MAX_CLEAN_STRETCH)
        val randomTimeFactor = (timeFactor > MAX_CLEAN_STRETCH)

        if (blockProcess.newSpectrum) {
            if (step < channels) {
                rotateBands(step)
                return
            }
            step -= channels
        }

        if (blockProcess.mappedFrequencies) {
            if (step < SMOOTH_ENERGY_STEPS) {
                smoothEnergy(step, smoothingBins)
                return
            }
            step -= SMOOTH_ENERGY_STEPS
            if (step-- == 0) {
                findPeaks()
                return
            }
        }

        if (step-- == 0) {
            if (blockProcess.mappedFrequencies) {
                updateOutputMap()
            } else {
                for (c in 0 until channels) {
                    val chBase = c * bands
                    for (b in 0 until bands) {
                        val idx = chBase + b
                        bandInputEnergy[idx] = norm(bandInputRe[idx], bandInputIm[idx])
                    }
                }
                for (b in 0 until bands) {
                    mapInputBin[b] = b.toFloat()
                    mapFreqGrad[b] = 1f
                }
            }
            return
        }

        if (blockProcess.processFormants) {
            if (step < 3) {
                updateFormants(step)
                return
            }
            step -= 3
        }

        if (step < channels) {
            preliminaryPrediction(step)
            return
        }
        step -= channels

        if (step < SPLIT_MAIN_PREDICTION) {
            val randScale = (MAX_CLEAN_STRETCH * 2f - timeFactor)
            val randRange = timeFactor - randScale
            verticalPhaseLock(step, timeFactor, randomTimeFactor, randScale, randRange, longVerticalStep)
            return
        }
        step -= SPLIT_MAIN_PREDICTION

        if (blockProcess.newSpectrum) {
            if (step-- == 0) {
                bandInputRe.copyInto(bandPrevInputRe)
                bandInputIm.copyInto(bandPrevInputIm)
            }
        }
    }

    private fun executeBlockProcessStep() {
        var step = blockProcess.step++
        if (blockProcess.newSpectrum) {
            if (blockProcess.reanalysePrev) {
                if (step < stft.analyseSteps()) {
                    stashedInput.swap(stft.input)
                    stft.analyseStep(step, stft.defaultInterval())
                    stashedInput.swap(stft.input)
                    return
                }
                step -= stft.analyseSteps()
                if (step < 1) {
                    for (c in 0 until channels) {
                        val chBase = c * bands
                        for (b in 0 until bands) {
                            bandPrevInputRe[chBase + b] = stft.spectrumRe[chBase + b]
                            bandPrevInputIm[chBase + b] = stft.spectrumIm[chBase + b]
                        }
                    }
                    return
                }
                step -= 1
            }

            if (step < stft.analyseSteps()) {
                stashedInput.swap(stft.input)
                stft.analyseStep(step)
                stashedInput.swap(stft.input)
                return
            }
            step -= stft.analyseSteps()
            if (step < 1) {
                for (c in 0 until channels) {
                    val chBase = c * bands
                    for (b in 0 until bands) {
                        bandInputRe[chBase + b] = stft.spectrumRe[chBase + b]
                        bandInputIm[chBase + b] = stft.spectrumIm[chBase + b]
                    }
                }
                return
            }
            step -= 1
        }

        if (step < processSpectrumSteps) {
            processSpectrum(step)
            return
        }
        step -= processSpectrumSteps

        if (step < 1) {
            for (c in 0 until channels) {
                val chBase = c * bands
                for (b in 0 until bands) {
                    stft.spectrumRe[chBase + b] = bandOutputRe[chBase + b]
                    stft.spectrumIm[chBase + b] = bandOutputIm[chBase + b]
                }
            }
            return
        }
        step -= 1

        if (step < stft.synthesiseSteps()) {
            stft.synthesiseStep(step)
            return
        }
    }

    fun process(
        inputs: Array<FloatArray>,
        inputSamples: Int,
        outputs: Array<FloatArray>,
        outputSamples: Int,
        inputOffset: Int = 0,
        outputOffset: Int = 0
    ) {
        var prevCopiedInput = 0
        fun copyInput(toIndex: Int) {
            val maxLen = stft.blockSamples + stft.defaultInterval()
            val length = min(maxLen, toIndex - prevCopiedInput)
            if (length <= 0) return
            val offset = toIndex - length
            for (c in 0 until channels) {
                stft.input.write(c, 0, inputs[c], inputOffset + offset, length)
            }
            stft.moveInput(length)
            prevCopiedInput = toIndex
        }

        var totalEnergy = 0f
        for (c in 0 until channels) {
            val inCh = inputs[c]
            for (i in 0 until inputSamples) {
                val s = inCh[inputOffset + i]
                totalEnergy += s * s
            }
        }

        if (totalEnergy < NOISE_FLOOR) {
            if (silenceCounter >= 2 * stft.blockSamples) {
                if (silenceFirst) {
                    silenceFirst = false
                    blockProcess.reset()
                    bandInputRe.fill(0f); bandInputIm.fill(0f)
                    bandPrevInputRe.fill(0f); bandPrevInputIm.fill(0f)
                    bandOutputRe.fill(0f); bandOutputIm.fill(0f)
                    bandInputEnergy.fill(0f)
                }

                if (inputSamples > 0) {
                    for (outIdx in 0 until outputSamples) {
                        val inIdx = outIdx % inputSamples
                        for (c in 0 until channels) {
                            outputs[c][outputOffset + outIdx] = inputs[c][inputOffset + inIdx]
                        }
                    }
                } else {
                    for (c in 0 until channels) {
                        outputs[c].fill(0f, outputOffset, outputOffset + outputSamples)
                    }
                }
                copyInput(inputSamples)
                return
            } else {
                silenceCounter += inputSamples
            }
        } else {
            silenceCounter = 0
            silenceFirst = true
        }

        var outputIndex = 0
        while (outputIndex < outputSamples) {
            val newBlock = blockProcess.samplesSinceLast >= stft.defaultInterval()
            if (newBlock) {
                blockProcess.step = 0
                blockProcess.steps = 0
                blockProcess.samplesSinceLast = 0

                val inOffset = round(outputIndex * (inputSamples.toFloat() / outputSamples)).toInt()
                val inInterval = inOffset - prevInputOffset
                prevInputOffset = inOffset

                copyInput(inOffset)
                stashedInput.copyFrom(stft.input)
                if (splitComputation) {
                    stashedOutput.copyFrom(stft.output)
                    stft.moveOutput(stft.defaultInterval())
                }

                blockProcess.newSpectrum = didSeek || (inInterval > 0)
                blockProcess.mappedFrequencies = customFreqMap != null || freqMultiplier != 1f
                if (blockProcess.newSpectrum) {
                    blockProcess.reanalysePrev = didSeek || abs(inInterval - stft.defaultInterval()) > 1
                    if (blockProcess.reanalysePrev) blockProcess.steps += stft.analyseSteps() + 1
                    blockProcess.steps += stft.analyseSteps() + 1
                }

                blockProcess.processFormants =
                    formantMultiplier != 1f || (formantCompensation && blockProcess.mappedFrequencies)
                blockProcess.timeFactor =
                    if (didSeek) seekTimeFactor else stft.defaultInterval().toFloat() / max(1, inInterval)
                didSeek = false

                updateProcessSpectrumSteps()
                blockProcess.steps += processSpectrumSteps
                blockProcess.steps += stft.synthesiseSteps() + 1
            }

            if (!splitComputation) {
                while (blockProcess.step < blockProcess.steps) {
                    executeBlockProcessStep()
                }
                val chunkSize = min(
                    outputSamples - outputIndex, stft.defaultInterval() - blockProcess.samplesSinceLast
                )
                for (c in 0 until channels) {
                    stft.readOutput(c, chunkSize, outputs[c], outputOffset + outputIndex)
                }
                stft.moveOutput(chunkSize)
                blockProcess.samplesSinceLast += chunkSize
                outputIndex += chunkSize
            } else {
                val ratio = (blockProcess.samplesSinceLast + 1).toFloat() / stft.defaultInterval()
                val processToStep = min(blockProcess.steps, ((blockProcess.steps + 0.999f) * ratio).toInt())
                while (blockProcess.step < processToStep) {
                    executeBlockProcessStep()
                }
                blockProcess.samplesSinceLast++
                stashedOutput.swap(stft.output)
                for (c in 0 until channels) {
                    stft.readOutput(c, 1, singleSampleBuf, 0)
                    outputs[c][outputOffset + outputIndex] = singleSampleBuf[0]
                }
                stft.moveOutput(1)
                stashedOutput.swap(stft.output)
                outputIndex++
            }
        }

        copyInput(inputSamples)
        prevInputOffset -= inputSamples
    }

    fun exact(
        inputs: Array<FloatArray>, inputSamples: Int, outputs: Array<FloatArray>, outputSamples: Int
    ): Boolean {
        val playbackRate = inputSamples.toFloat() / outputSamples.toFloat()
        val seekLen = outputSeekLength(playbackRate)
        if (inputSamples < seekLen) {
            for (c in 0 until channels) {
                outputs[c].fill(0f, 0, outputSamples)
            }
            return false
        }

        outputSeek(inputs, seekLen)

        val outputIndex = max(0, outputSamples - (seekLen / playbackRate).toInt())
        val remainingInput = inputSamples - seekLen

        process(
            inputs = inputs,
            inputSamples = remainingInput,
            outputs = outputs,
            outputSamples = outputIndex,
            inputOffset = seekLen,
            outputOffset = 0
        )

        val remainingOutput = outputSamples - outputIndex
        flush(outputs, remainingOutput, outputOffset = outputIndex, playbackRate = playbackRate)

        return true
    }

    fun stretch(
        track: AudioTrack, timeRatio: Float = 1.0f, pitchSemitones: Float = 0.0f, formantSemitones: Float = 0.0f
    ): AudioTrack {
        val inSamples = track.numSamples
        val outSamples = (inSamples / timeRatio).roundToInt().coerceAtLeast(1)
        val outChannels = Array(track.numChannels) { FloatArray(outSamples) }

        presetDefault(track.numChannels, track.sampleRate)
        setTransposeSemitones(pitchSemitones)
        setFormantSemitones(formantSemitones)

        exact(track.channels, inSamples, outChannels, outSamples)
        return AudioTrack(outChannels, track.sampleRate)
    }

    fun stretch(
        inputs: Array<FloatArray>,
        sampleRate: Float,
        timeRatio: Float = 1.0f,
        pitchSemitones: Float = 0.0f,
        formantSemitones: Float = 0.0f
    ): Array<FloatArray> {
        val inSamples = if (inputs.isNotEmpty()) inputs[0].size else 0
        val outSamples = (inSamples / timeRatio).roundToInt().coerceAtLeast(1)
        val outChannels = Array(inputs.size) { FloatArray(outSamples) }

        presetDefault(inputs.size, sampleRate)
        setTransposeSemitones(pitchSemitones)
        setFormantSemitones(formantSemitones)

        exact(inputs, inSamples, outChannels, outSamples)
        return outChannels
    }

    fun seek(inputs: Array<FloatArray>, inputSamples: Int, playbackRate: Double, inputOffset: Int = 0) {
        val neededSize = stft.blockSamples + stft.defaultInterval()
        if (tmpProcessBuffer.size < neededSize) {
            tmpProcessBuffer = FloatArray(neededSize)
        }

        val startIndex = max(0, inputSamples - neededSize)
        val padStart = (neededSize + startIndex) - inputSamples

        var totalEnergy = 0f
        for (c in 0 until channels) {
            val inputChannel = inputs[c]
            for (i in startIndex until inputSamples) {
                val s = inputChannel[inputOffset + i]
                totalEnergy += s * s
                tmpProcessBuffer[i - startIndex + padStart] = s
            }
            stft.writeInput(c, neededSize, tmpProcessBuffer, 0)
        }
        stft.moveInput(neededSize)

        if (totalEnergy >= NOISE_FLOOR) {
            silenceCounter = 0
            silenceFirst = true
        }
        didSeek = true
        seekTimeFactor = if (playbackRate * stft.defaultInterval() > 1.0) {
            (1.0 / playbackRate).toFloat()
        } else {
            stft.defaultInterval().toFloat()
        }
    }

    fun seekLength(): Int = stft.blockSamples + stft.defaultInterval()

    fun outputSeekLength(playbackRate: Float): Int {
        return inputLatency() + (playbackRate * outputLatency()).toInt()
    }

    fun outputSeek(inputs: Array<FloatArray>, inputLength: Int, inputOffset: Int = 0) {
        reset()
        val surplusInput = max(inputLength - inputLatency(), 0)
        val playbackRate = surplusInput.toDouble() / outputLatency().toDouble()

        val seekSamples = inputLength - surplusInput
        seek(inputs, seekSamples, playbackRate, inputOffset = inputOffset)

        val outLat = outputLatency()
        val neededPreRollSize = outLat * channels
        if (tmpPreRollBuffer.size < neededPreRollSize) {
            tmpPreRollBuffer = FloatArray(neededPreRollSize)
        }

        if (preRollOutput.size != channels || (preRollOutput.isNotEmpty() && preRollOutput[0].size != outLat)) {
            preRollOutput = Array(channels) { FloatArray(outLat) }
        }
        val preRoll = preRollOutput

        process(
            inputs = inputs,
            inputSamples = surplusInput,
            outputs = preRoll,
            outputSamples = outLat,
            inputOffset = inputOffset + seekSamples,
            outputOffset = 0
        )

        for (c in 0 until channels) {
            val chData = preRoll[c]
            val half = outLat / 2
            for (i in 0 until half) {
                val temp = -chData[i]
                chData[i] = -chData[outLat - 1 - i]
                chData[outLat - 1 - i] = temp
            }
            if (outLat % 2 != 0) {
                chData[half] = -chData[half]
            }
            stft.addOutput(c, outLat, chData, 0)
        }
    }

    fun flush(
        outputs: Array<FloatArray>, outputSamples: Int, outputOffset: Int = 0, playbackRate: Float = 0f
    ) {
        val interval = stft.defaultInterval()
        val outputBlock = max(0, outputSamples - interval)

        if (outputBlock > 0) {
            val zeroInputSamples = (outputBlock * playbackRate).toInt()
            val safeZeroInputSamples = max(1, zeroInputSamples)

            if (zeroBlockBuffer.size < safeZeroInputSamples) {
                zeroBlockBuffer = FloatArray(safeZeroInputSamples)
            }
            val zeroInputs = Array(channels) { zeroBlockBuffer }

            process(
                inputs = zeroInputs,
                inputSamples = safeZeroInputSamples,
                outputs = outputs,
                outputSamples = outputBlock,
                inputOffset = 0,
                outputOffset = outputOffset
            )
        }

        val tailSamples = outputSamples - outputBlock
        if (tmpProcessBuffer.size < tailSamples) {
            tmpProcessBuffer = FloatArray(tailSamples)
        }

        stft.finishOutput(1)

        val tailOffset = outputOffset + outputBlock
        for (c in 0 until channels) {
            stft.readOutput(c, tailSamples, tmpProcessBuffer, 0)
            val outputChannel = outputs[c]
            for (i in 0 until tailSamples) {
                outputChannel[tailOffset + i] = tmpProcessBuffer[i]
            }

            stft.readOutput(c, tailSamples, tmpProcessBuffer, 0, offsetFromHead = tailSamples)
            for (i in 0 until tailSamples) {
                outputChannel[tailOffset + tailSamples - 1 - i] -= tmpProcessBuffer[i]
            }
        }

        stft.reset(0.1f)
        bandPrevInputRe.fill(0f)
        bandPrevInputIm.fill(0f)
        bandOutputRe.fill(0f)
        bandOutputIm.fill(0f)
    }
}