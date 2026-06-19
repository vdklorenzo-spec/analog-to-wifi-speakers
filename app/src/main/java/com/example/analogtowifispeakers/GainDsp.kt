package com.example.analogtowifispeakers.dsp

import kotlin.math.roundToInt

class GainDsp(private val gain: Float) : DspStage {
    override fun process(inPcm: ShortArray, samples: Int, outPcm: ShortArray): Int {
        val g = gain
        for (i in 0 until samples) {
            val v = (inPcm[i].toInt() * g).roundToInt()
            val clipped = when {
                v > 32767 -> 32767
                v < -32768 -> -32768
                else -> v
            }
            outPcm[i] = clipped.toShort()
        }
        return samples
    }
}