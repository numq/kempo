package io.github.numq.kempo

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KempoMultichannelTest {
    private val sampleRate = 48000f

    @Test
    fun testSixChannelTimeStretchingAtOnePointFiveSpeed() {
        val channels = 6 // FL, FR, C, LFE, SL, SR
        val numSamples = 48000 // 1.0 sec
        val timeRatio = 1.5f
        val outSamples = (numSamples / timeRatio).toInt()

        // Channel 2 (Center) has primary dialogue tone (440 Hz)
        // FL and FR have stereo music bed (220 Hz and 330 Hz)
        val input = Array(channels) { c ->
            FloatArray(numSamples) { i ->
                when (c) {
                    0 -> sin(2.0 * PI * 220.0 * i / sampleRate).toFloat() * 0.5f // FL
                    1 -> sin(2.0 * PI * 330.0 * i / sampleRate).toFloat() * 0.5f // FR
                    2 -> sin(2.0 * PI * 440.0 * i / sampleRate).toFloat() * 0.8f // C (dialogue)
                    3 -> sin(2.0 * PI * 60.0 * i / sampleRate).toFloat() * 0.4f  // LFE
                    else -> sin(2.0 * PI * 880.0 * i / sampleRate).toFloat() * 0.2f // Surrounds
                }
            }
        }
        val output = Array(channels) { FloatArray(outSamples) }

        val stretch = KempoStretch()
        stretch.presetDefault(channels, sampleRate)
        stretch.setTransposeSemitones(0f)

        val success = stretch.exact(input, numSamples, output, outSamples)
        assertTrue(success, "Six-channel exact processing failed")

        // 1. Verify all output channels have exact equal lengths and no NaN values
        for (c in 0 until channels) {
            assertEquals(outSamples, output[c].size)
            for (i in 0 until outSamples) {
                assertFalse(output[c][i].isNaN(), "NaN detected in channel $c at sample $i")
            }
        }

        // 2. Perform ITU stereo downmix of the stretched 5.1 output:
        // L = FL + 0.707 * C + 0.707 * SL
        // R = FR + 0.707 * C + 0.707 * SR
        val downmixL = FloatArray(outSamples)
        val downmixR = FloatArray(outSamples)
        val centerCoeff = 0.7071f
        for (i in 0 until outSamples) {
            downmixL[i] = output[0][i] + centerCoeff * output[2][i] + centerCoeff * output[4][i]
            downmixR[i] = output[1][i] + centerCoeff * output[2][i] + centerCoeff * output[5][i]
        }

        // 3. Verify Center speech energy is preserved in downmix without phase cancellation
        val evalStart = (outSamples * 0.25f).toInt()
        val evalEnd = (outSamples * 0.75f).toInt()
        var centerEnergy = 0.0
        var downmixLEnergy = 0.0

        for (i in evalStart until evalEnd) {
            centerEnergy += output[2][i] * output[2][i]
            downmixLEnergy += downmixL[i] * downmixL[i]
        }

        assertTrue(
            downmixLEnergy > centerEnergy * 0.4,
            "Center channel experienced destructive phase cancellation during stereo downmixing"
        )
    }

    @Test
    fun testChannelIsolationDuringMultiChannelStretch() {
        val channels = 6
        val numSamples = 24000
        val outSamples = (numSamples / 1.5f).toInt()

        // Pure signal exclusively in Channel 2 (Center); all other channels silent
        val input = Array(channels) { c ->
            FloatArray(numSamples) { i ->
                if (c == 2) sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() else 0f
            }
        }
        val output = Array(channels) { FloatArray(outSamples) }

        val stretch = KempoStretch()
        stretch.presetDefault(channels, sampleRate)
        stretch.exact(input, numSamples, output, outSamples)

        val evalStart = (outSamples * 0.2f).toInt()
        val evalEnd = (outSamples * 0.8f).toInt()

        for (c in 0 until channels) {
            var energy = 0.0
            for (i in evalStart until evalEnd) {
                energy += output[c][i] * output[c][i]
            }

            if (c == 2) {
                assertTrue(energy > 10.0, "Center channel signal was lost")
            } else {
                assertTrue(energy < 1e-4, "Crosstalk/bleed detected into silent channel $c: energy=$energy")
            }
        }
    }

    @Test
    fun testSixChannelStreamingPipelineAtOnePointFiveSpeed() {
        val channels = 6
        val timeRatio = 1.5f
        val stretch = KempoStretch()
        stretch.presetDefault(channels, sampleRate)

        val outBlockSize = stretch.defaultInterval
        val inBlockSize = (outBlockSize * timeRatio).toInt()

        val inBlock = Array(channels) { FloatArray(inBlockSize) }
        val outBlock = Array(channels) { FloatArray(outBlockSize) }

        var phase = 0.0
        val phaseStep = 2.0 * PI * 1000.0 / sampleRate

        // Run 25 consecutive blocks simulating real-time streaming playback
        for (block in 0 until 25) {
            for (i in 0 until inBlockSize) {
                val s = sin(phase).toFloat()
                inBlock[0][i] = s * 0.7f // FL
                inBlock[1][i] = s * 0.7f // FR
                inBlock[2][i] = s        // Center dialogue
                phase += phaseStep
            }

            stretch.process(inBlock, inBlockSize, outBlock, outBlockSize)

            for (c in 0 until channels) {
                for (i in 0 until outBlockSize) {
                    assertFalse(outBlock[c][i].isNaN(), "Streaming output contains NaN in ch $c at block $block")
                }
            }
        }
    }

    @Test
    fun testCorrelatedCenterDialoguePhaseRetention() {
        val channels = 6
        val numSamples = 48000
        val timeRatio = 1.5f
        val outSamples = (numSamples / timeRatio).toInt()

        // Same speech fundamental shared between FL, FR, and Center with different gains
        val speechSignal = FloatArray(numSamples) { i -> sin(2.0 * PI * 300.0 * i / sampleRate).toFloat() }
        val input = Array(channels) { c ->
            FloatArray(numSamples) { i ->
                when (c) {
                    0 -> speechSignal[i] * 0.5f // Bleed into FL
                    1 -> speechSignal[i] * 0.5f // Bleed into FR
                    2 -> speechSignal[i] * 1.0f // Dominant Center
                    else -> 0f
                }
            }
        }
        val output = Array(channels) { FloatArray(outSamples) }

        val stretch = KempoStretch()
        stretch.presetDefault(channels, sampleRate)
        stretch.exact(input, numSamples, output, outSamples)

        val evalStart = (outSamples * 0.3f).toInt()
        val evalEnd = (outSamples * 0.7f).toInt()

        // Verify that Center and FL/FR stay strictly in-phase post-stretch
        var dotProduct = 0.0
        var normL = 0.0
        var normC = 0.0
        for (i in evalStart until evalEnd) {
            val fl = output[0][i].toDouble()
            val c = output[2][i].toDouble()
            dotProduct += fl * c
            normL += fl * fl
            normC += c * c
        }

        val correlation = dotProduct / (sqrt(normL * normC) + 1e-15)
        assertTrue(
            correlation > 0.90,
            "Correlated dialogue lost phase coherence between Center and Front channels: $correlation"
        )
    }
}