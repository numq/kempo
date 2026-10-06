package io.github.numq.kempo.dsp

import io.github.numq.kempo.InternalKempoApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Modified Real FFT evaluating spectra on half-bin shifted frequency points (k + 0.5) / N.
 * Converts an N-sample real signal into N/2 complex bins without DC/Nyquist edge singularities.
 */
@InternalKempoApi
class ModifiedRealFFT(size: Int = 0) {
    private val complexFft = FastFft()
    private var _size = 0

    val size: Int get() = _size

    private var buf1Re = FloatArray(0)
    private var buf1Im = FloatArray(0)
    private var buf2Re = FloatArray(0)
    private var buf2Im = FloatArray(0)

    private var twMinusIRe = FloatArray(0)
    private var twMinusIIm = FloatArray(0)
    private var modRotRe = FloatArray(0)
    private var modRotIm = FloatArray(0)

    init {
        if (size > 0) setSize(max(size, 2))
    }

    fun setSize(size: Int): Int {
        _size = size
        val hSize = size / 2
        buf1Re = FloatArray(hSize)
        buf1Im = FloatArray(hSize)
        buf2Re = FloatArray(hSize)
        buf2Im = FloatArray(hSize)

        val hhSize = size / 4 + 1
        twMinusIRe = FloatArray(hhSize)
        twMinusIIm = FloatArray(hhSize)
        for (i in 0 until hhSize) {
            val rotPhase = -2.0 * PI * (i + 0.5) / size
            twMinusIRe[i] = sin(rotPhase).toFloat()
            twMinusIIm[i] = (-cos(rotPhase)).toFloat()
        }

        modRotRe = FloatArray(hSize)
        modRotIm = FloatArray(hSize)
        for (i in 0 until hSize) {
            val rotPhase = -2.0 * PI * i / size
            modRotRe[i] = cos(rotPhase).toFloat()
            modRotIm[i] = sin(rotPhase).toFloat()
        }

        complexFft.setSize(hSize)
        return size
    }

    fun fft(input: FloatArray, inOffset: Int, outRe: FloatArray, outIm: FloatArray, outOffset: Int = 0) {
        val hSize = complexFft.size
        for (i in 0 until hSize) {
            val inR = input[inOffset + 2 * i]
            val inI = input[inOffset + 2 * i + 1]
            val rotR = modRotRe[i]
            val rotI = modRotIm[i]
            buf1Re[i] = inR * rotR - inI * rotI
            buf1Im[i] = inR * rotI + inI * rotR
        }

        complexFft.fft(buf1Re, buf1Im, buf2Re, buf2Im)

        for (i in 0..hSize / 2) {
            val conjI = hSize - 1 - i
            val b2iR = buf2Re[i]
            val b2iI = buf2Im[i]
            val b2cR = buf2Re[conjI]
            val b2cI = -buf2Im[conjI]

            val oddR = (b2iR + b2cR) * 0.5f
            val oddI = (b2iI + b2cI) * 0.5f

            val evenIR = (b2iR - b2cR) * 0.5f
            val evenII = (b2iI - b2cI) * 0.5f

            val twR = twMinusIRe[i]
            val twI = twMinusIIm[i]
            val rotMinusIR = evenIR * twR - evenII * twI
            val rotMinusII = evenIR * twI + evenII * twR

            outRe[outOffset + i] = oddR + rotMinusIR
            outIm[outOffset + i] = oddI + rotMinusII

            outRe[outOffset + conjI] = oddR - rotMinusIR
            outIm[outOffset + conjI] = -(oddI - rotMinusII)
        }
    }

    fun ifft(inRe: FloatArray, inIm: FloatArray, output: FloatArray, outOffset: Int, inOffset: Int = 0) {
        val hSize = complexFft.size
        for (i in 0..hSize / 2) {
            val conjI = hSize - 1 - i
            val vR = inRe[inOffset + i]
            val vI = inIm[inOffset + i]
            val v2R = inRe[inOffset + conjI]
            val v2I = -inIm[inOffset + conjI]

            val oddR = vR + v2R
            val oddI = vI + v2I

            val diffR = vR - v2R
            val diffI = vI - v2I

            val twR = twMinusIRe[i]
            val twI = twMinusIIm[i]
            val evenIR = twR * diffR + twI * diffI
            val evenII = twR * diffI - twI * diffR

            buf1Re[i] = oddR + evenIR
            buf1Im[i] = oddI + evenII
            buf1Re[conjI] = oddR - evenIR
            buf1Im[conjI] = -(oddI - evenII)
        }

        complexFft.ifft(buf1Re, buf1Im, buf2Re, buf2Im)

        for (i in 0 until hSize) {
            val b2R = buf2Re[i]
            val b2I = buf2Im[i]
            val rotR = modRotRe[i]
            val rotI = modRotIm[i]
            output[outOffset + 2 * i] = rotR * b2R + rotI * b2I
            output[outOffset + 2 * i + 1] = rotR * b2I - rotI * b2R
        }
    }

    companion object {
        fun fastSizeAbove(size: Int): Int {
            return FastFft.fastSizeAbove((size + 1) / 2) * 2
        }
    }
}