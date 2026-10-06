package io.github.numq.kempo.dsp

import io.github.numq.kempo.InternalKempoApi
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Window generation and Weighted Overlap-Add (WOLA) normalization utilities.
 */
@InternalKempoApi
object Windows {
    private const val BESSEL_LIMIT = 1e-4

    fun bessel0(x: Double): Double {
        var result = 0.0
        var term = 1.0
        var m = 0.0
        val xSq4 = (x * x) * 0.25
        while (term > BESSEL_LIMIT) {
            result += term
            m += 1.0
            term *= xSq4 / (m * m)
        }
        return result
    }

    class Kaiser private constructor(val beta: Double) {
        private val invB0 = 1.0 / bessel0(beta)

        operator fun invoke(unit: Double): Double {
            val r = 2.0 * unit - 1.0
            val arg = sqrt(max(0.0, 1.0 - r * r))
            return bessel0(beta * arg) * invB0
        }

        fun fill(data: FloatArray, size: Int, offset: Int = 0) {
            val invSize = 1.0 / size
            for (i in 0 until size) {
                val r = (2.0 * i + 1.0) * invSize - 1.0
                val arg = sqrt(max(0.0, 1.0 - r * r))
                data[offset + i] = (bessel0(beta * arg) * invB0).toFloat()
            }
        }

        companion object {
            private fun heuristicBandwidth(bw: Double): Double {
                return bw + 8.0 / ((bw + 3.0) * (bw + 3.0)) + 0.25 * max(3.0 - bw, 0.0)
            }

            fun bandwidthToBeta(bandwidth: Double, heuristicOptimal: Boolean = false): Double {
                var bw = if (heuristicOptimal) heuristicBandwidth(bandwidth) else bandwidth
                bw = max(bw, 2.0)
                val alpha = sqrt(bw * bw * 0.25 - 1.0)
                return alpha * PI
            }

            fun withBandwidth(bandwidth: Double, heuristicOptimal: Boolean = false): Kaiser {
                return Kaiser(bandwidthToBeta(bandwidth, heuristicOptimal))
            }
        }
    }

    fun forcePerfectReconstruction(data: FloatArray, windowLength: Int, interval: Int) {
        for (i in 0 until interval) {
            var sum2 = 0.0
            var index = i
            while (index < windowLength) {
                val v = data[index].toDouble()
                sum2 += v * v
                index += interval
            }
            val factor = (1.0 / sqrt(sum2)).toFloat()
            index = i
            while (index < windowLength) {
                data[index] *= factor
                index += interval
            }
        }
    }
}