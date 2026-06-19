package com.example.analogtowifispeakers.dsp

class PassthroughDsp : DspStage {
    override fun process(inPcm: ShortArray, samples: Int, outPcm: ShortArray): Int {
        // Bit-identical copy: no DSP yet
        System.arraycopy(inPcm, 0, outPcm, 0, samples)
        return samples
    }
}