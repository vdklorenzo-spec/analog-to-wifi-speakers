package com.example.analogtowifispeakers.dsp

/**
 * DSP stage that can modify PCM audio frames.
 * Input/Output are 16-bit PCM mono/stereo depending on your pipeline.
 *
 * Contract:
 * - inPcm contains 'samples' valid shorts
 * - outPcm must be able to hold at least 'samples' shorts
 * - returns number of output samples written (usually == samples)
 */
interface DspStage {
    fun process(inPcm: ShortArray, samples: Int, outPcm: ShortArray): Int
}