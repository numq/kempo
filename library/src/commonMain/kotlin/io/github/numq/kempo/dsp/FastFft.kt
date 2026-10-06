package io.github.numq.kempo.dsp

import io.github.numq.kempo.InternalKempoApi
import kotlin.math.*

/**
 * High-performance in-place mixed-radix Fast Fourier Transform (radix-2, radix-3, radix-4, and generic composite).
 * All memory allocations during transform execution are strictly eliminated.
 */
@InternalKempoApi
class FastFft(initialSize: Int = 0) {
    private var _size = 0

    val size: Int get() = _size

    private enum class StepType { GENERIC, STEP2, STEP3, STEP4 }

    private class Step(
        val type: StepType,
        val factor: Int,
        val startIndex: Int,
        val innerRepeats: Int,
        val outerRepeats: Int,
        val twiddleIndex: Int
    )

    private val factors = ArrayList<Int>()
    private val plan = ArrayList<Step>()
    private var twiddleRe = FloatArray(0)
    private var twiddleIm = FloatArray(0)
    private var twiddleCount = 0

    private var permFrom = IntArray(0)
    private var permTo = IntArray(0)
    private var permSize = 0

    private var workRe = FloatArray(0)
    private var workIm = FloatArray(0)

    init {
        if (initialSize > 0) setSize(initialSize)
    }

    fun setSize(newSize: Int): Int {
        if (newSize == _size) return _size
        _size = newSize
        workRe = FloatArray(_size)
        workIm = FloatArray(_size)
        setPlan()
        return _size
    }

    private fun addPlanSteps(factorIndex: Int, start: Int, length: Int, repeats: Int) {
        if (factorIndex >= factors.size) return
        var fIndex = factorIndex
        var factor = factors[fIndex]
        if (fIndex + 1 < factors.size && factors[fIndex] == 2 && factors[fIndex + 1] == 2) {
            fIndex++
            factor = 4
        }

        val subLength = length / factor
        val sType = when (factor) {
            2 -> StepType.STEP2
            3 -> StepType.STEP3
            4 -> StepType.STEP4
            else -> StepType.GENERIC
        }

        var twiddleIdx = -1
        for (i in 0 until plan.size) {
            val step = plan[i]
            if (step.factor == factor && step.innerRepeats == subLength) {
                twiddleIdx = step.twiddleIndex
                break
            }
        }

        if (twiddleIdx == -1) {
            twiddleIdx = twiddleCount
            val needed = twiddleCount + subLength * factor
            if (needed > twiddleRe.size) {
                val newCap = max(needed, twiddleRe.size * 2)
                twiddleRe = twiddleRe.copyOf(newCap)
                twiddleIm = twiddleIm.copyOf(newCap)
            }
            val angleStep = 2.0 * PI / length
            for (i in 0 until subLength) {
                for (f in 0 until factor) {
                    val phase = angleStep * i * f
                    twiddleRe[twiddleCount] = cos(phase).toFloat()
                    twiddleIm[twiddleCount] = (-sin(phase)).toFloat()
                    twiddleCount++
                }
            }
        }

        val mainStep = Step(sType, factor, start, subLength, repeats, twiddleIdx)

        if (repeats == 1 && (8 * subLength) > 65536) {
            for (i in 0 until factor) {
                addPlanSteps(fIndex + 1, start + i * subLength, subLength, 1)
            }
        } else {
            addPlanSteps(fIndex + 1, start, subLength, repeats * factor)
        }
        plan.add(mainStep)
    }

    private fun setPlan() {
        factors.clear()
        var s = _size
        var factor = 2
        while (s > 1) {
            if (s % factor == 0) {
                factors.add(factor)
                s /= factor
            } else if (factor > sqrt(s.toDouble())) {
                factor = s
            } else {
                factor++
            }
        }

        plan.clear()
        twiddleCount = 0
        addPlanSteps(0, 0, _size, 1)

        val tempFrom = IntArray(_size)
        val tempTo = IntArray(_size)
        tempFrom[0] = 0
        tempTo[0] = 0
        var currentPermSize = 1

        var indexLow = 0
        var indexHigh = factors.size
        var inputStepLow = _size
        var outputStepLow = 1
        var inputStepHigh = 1
        var outputStepHigh = _size

        while (outputStepLow * inputStepHigh < _size) {
            val f: Int
            val inputStep: Int
            val outputStep: Int
            if (outputStepLow <= inputStepHigh) {
                f = factors[indexLow++]
                inputStepLow /= f
                inputStep = inputStepLow
                outputStep = outputStepLow
                outputStepLow *= f
            } else {
                f = factors[--indexHigh]
                inputStep = inputStepHigh
                inputStepHigh *= f
                outputStepHigh /= f
                outputStep = outputStepHigh
            }
            val oldSize = currentPermSize
            for (i in 1 until f) {
                for (j in 0 until oldSize) {
                    tempFrom[currentPermSize] = tempFrom[j] + i * inputStep
                    tempTo[currentPermSize] = tempTo[j] + i * outputStep
                    currentPermSize++
                }
            }
        }

        permFrom = tempFrom.copyOf(currentPermSize)
        permTo = tempTo.copyOf(currentPermSize)
        permSize = currentPermSize
    }

    fun fft(inRe: FloatArray, inIm: FloatArray, outRe: FloatArray, outIm: FloatArray) =
        run(inRe, inIm, outRe, outIm, inverse = false)

    fun ifft(inRe: FloatArray, inIm: FloatArray, outRe: FloatArray, outIm: FloatArray) =
        run(inRe, inIm, outRe, outIm, inverse = true)

    private fun run(
        inRe: FloatArray, inIm: FloatArray, outRe: FloatArray, outIm: FloatArray, inverse: Boolean
    ) {
        val pFrom = permFrom
        val pTo = permTo
        val pLen = permSize
        for (i in 0 until pLen) {
            val from = pFrom[i]
            val to = pTo[i]
            outRe[from] = inRe[to]
            outIm[from] = inIm[to]
        }

        val planList = plan
        val planSize = planList.size
        for (s in 0 until planSize) {
            val step = planList[s]
            when (step.type) {
                StepType.STEP2 -> step2(outRe, outIm, step, inverse)
                StepType.STEP3 -> step3(outRe, outIm, step, inverse)
                StepType.STEP4 -> step4(outRe, outIm, step, inverse)
                StepType.GENERIC -> stepGeneric(outRe, outIm, step, inverse)
            }
        }
    }

    private fun step2(re: FloatArray, im: FloatArray, step: Step, inv: Boolean) {
        val stride = step.innerRepeats
        val origTwiddle = step.twiddleIndex
        var origData = step.startIndex
        val twRe = twiddleRe
        val twIm = twiddleIm

        for (outer in 0 until step.outerRepeats) {
            var tw = origTwiddle
            for (data in origData until origData + stride) {
                val ar = re[data]
                val ai = im[data]
                val bIdx = data + stride
                val br = re[bIdx]
                val bi = im[bIdx]
                val tr = twRe[tw + 1]
                val ti = twIm[tw + 1]

                val mBReal: Float
                val mBImag: Float
                if (inv) {
                    mBReal = tr * br + ti * bi
                    mBImag = tr * bi - ti * br
                } else {
                    mBReal = br * tr - bi * ti
                    mBImag = br * ti + bi * tr
                }

                re[data] = ar + mBReal
                im[data] = ai + mBImag
                re[bIdx] = ar - mBReal
                im[bIdx] = ai - mBImag
                tw += 2
            }
            origData += 2 * stride
        }
    }

    private fun step4(re: FloatArray, im: FloatArray, step: Step, inv: Boolean) {
        val stride = step.innerRepeats
        val origTwiddle = step.twiddleIndex
        var origData = step.startIndex
        val twRe = twiddleRe
        val twIm = twiddleIm
        val stride2 = stride * 2
        val stride3 = stride * 3

        for (outer in 0 until step.outerRepeats) {
            var tw = origTwiddle
            for (data in origData until origData + stride) {
                val iA = data
                val iB = data + stride2
                val iC = data + stride
                val iD = data + stride3

                val aR = re[iA]
                val aI = im[iA]

                val cSrcR = re[iC]
                val cSrcI = im[iC]
                val tw2R = twRe[tw + 2]
                val tw2I = twIm[tw + 2]
                val cR = if (inv) tw2R * cSrcR + tw2I * cSrcI else cSrcR * tw2R - cSrcI * tw2I
                val cI = if (inv) tw2R * cSrcI - tw2I * cSrcR else cSrcR * tw2I + cSrcI * tw2R

                val bSrcR = re[iB]
                val bSrcI = im[iB]
                val tw1R = twRe[tw + 1]
                val tw1I = twIm[tw + 1]
                val bR = if (inv) tw1R * bSrcR + tw1I * bSrcI else bSrcR * tw1R - bSrcI * tw1I
                val bI = if (inv) tw1R * bSrcI - tw1I * bSrcR else bSrcR * tw1I + bSrcI * tw1R

                val dSrcR = re[iD]
                val dSrcI = im[iD]
                val tw3R = twRe[tw + 3]
                val tw3I = twIm[tw + 3]
                val dR = if (inv) tw3R * dSrcR + tw3I * dSrcI else dSrcR * tw3R - dSrcI * tw3I
                val dI = if (inv) tw3R * dSrcI - tw3I * dSrcR else dSrcR * tw3I + dSrcI * tw3R

                val sumAcR = aR + cR
                val sumAcI = aI + cI
                val sumBdR = bR + dR
                val sumBdI = bI + dI
                val diffAcR = aR - cR
                val diffAcI = aI - cI
                val diffBdR = bR - dR
                val diffBdI = bI - dI

                re[iA] = sumAcR + sumBdR
                im[iA] = sumAcI + sumBdI

                if (!inv) {
                    re[iC] = diffAcR + diffBdI
                    im[iC] = diffAcI - diffBdR
                    re[iD] = diffAcR - diffBdI
                    im[iD] = diffAcI + diffBdR
                } else {
                    re[iC] = diffAcR - diffBdI
                    im[iC] = diffAcI + diffBdR
                    re[iD] = diffAcR + diffBdI
                    im[iD] = diffAcI - diffBdR
                }

                re[iB] = sumAcR - sumBdR
                im[iB] = sumAcI - sumBdI
                tw += 4
            }
            origData += 4 * stride
        }
    }

    private fun step3(re: FloatArray, im: FloatArray, step: Step, inv: Boolean) {
        val f3Re = -0.5f
        val f3Im = if (inv) 0.8660254f else -0.8660254f
        val stride = step.innerRepeats
        val origTwiddle = step.twiddleIndex
        var origData = step.startIndex
        val twRe = twiddleRe
        val twIm = twiddleIm
        val stride2 = stride * 2

        for (outer in 0 until step.outerRepeats) {
            var tw = origTwiddle
            for (data in origData until origData + stride) {
                val iA = data
                val iB = data + stride
                val iC = data + stride2

                val aR = re[iA]
                val aI = im[iA]

                val bSrcR = re[iB]
                val bSrcI = im[iB]
                val tw1R = twRe[tw + 1]
                val tw1I = twIm[tw + 1]
                val bR = if (inv) tw1R * bSrcR + tw1I * bSrcI else bSrcR * tw1R - bSrcI * tw1I
                val bI = if (inv) tw1R * bSrcI - tw1I * bSrcR else bSrcR * tw1I + bSrcI * tw1R

                val cSrcR = re[iC]
                val cSrcI = im[iC]
                val tw2R = twRe[tw + 2]
                val tw2I = twIm[tw + 2]
                val cR = if (inv) tw2R * cSrcR + tw2I * cSrcI else cSrcR * tw2R - cSrcI * tw2I
                val cI = if (inv) tw2R * cSrcI - tw2I * cSrcR else cSrcR * tw2I + cSrcI * tw2R

                val realSumR = aR + (bR + cR) * f3Re
                val realSumI = aI + (bI + cI) * f3Re
                val imagSumR = (bR - cR) * f3Im
                val imagSumI = (bI - cI) * f3Im

                re[iA] = aR + bR + cR
                im[iA] = aI + bI + cI

                re[iB] = realSumR - imagSumI
                im[iB] = realSumI + imagSumR

                re[iC] = realSumR + imagSumI
                im[iC] = realSumI - imagSumR

                tw += 3
            }
            origData += 3 * stride
        }
    }

    private fun stepGeneric(re: FloatArray, im: FloatArray, step: Step, inv: Boolean) {
        val stride = step.innerRepeats
        val factor = step.factor
        val origTwiddle = step.twiddleIndex
        var origData = step.startIndex
        val twRe = twiddleRe
        val twIm = twiddleIm
        val wRe = workRe
        val wIm = workIm
        val angleFactor = 2.0 * PI / factor

        for (outer in 0 until step.outerRepeats) {
            var data = origData
            var tw = origTwiddle
            for (repeat in 0 until step.innerRepeats) {
                for (i in 0 until factor) {
                    val dIdx = data + i * stride
                    val dR = re[dIdx]
                    val dI = im[dIdx]
                    val tR = twRe[tw + i]
                    val tI = twIm[tw + i]
                    wRe[i] = if (inv) tR * dR + tI * dI else dR * tR - dI * tI
                    wIm[i] = if (inv) tR * dI - tI * dR else dR * tI + dI * tR
                }
                for (f in 0 until factor) {
                    var sR = wRe[0]
                    var sI = wIm[0]
                    for (i in 1 until factor) {
                        val phase = angleFactor * f * i
                        val pTwR = cos(phase).toFloat()
                        val pTwI = (-sin(phase)).toFloat()
                        val wiR = wRe[i]
                        val wiI = wIm[i]
                        sR += if (inv) pTwR * wiR + pTwI * wiI else wiR * pTwR - wiI * pTwI
                        sI += if (inv) pTwR * wiI - pTwI * wiR else wiR * pTwI + wiI * pTwR
                    }
                    val outIdx = data + f * stride
                    re[outIdx] = sR
                    im[outIdx] = sI
                }
                data++
                tw += factor
            }
            origData += factor * step.innerRepeats
        }
    }

    companion object {
        private val FILTER = booleanArrayOf(
            true,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            true,
            true,
            false,
            false,
            true,
            false,
            false,
            false,
            true,
            false,
            true,
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        )

        fun fastSizeAbove(size: Int): Int {
            var s = size
            var power2 = 1
            while (s >= 32) {
                s = (s - 1) / 2 + 1
                power2 *= 2
            }
            while (s < 32 && !FILTER[s]) {
                s++
            }
            return power2 * s
        }
    }
}