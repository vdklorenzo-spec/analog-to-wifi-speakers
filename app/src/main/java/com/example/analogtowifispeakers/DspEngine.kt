package com.example.analogtowifispeakers.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * DSP Foundation (Phase 8)
 *
 * Receives PCM 16-bit little-endian frames as ByteArray and processes IN PLACE.
 * Outputs the same PCM format so the AAC encoder stays unchanged.
 *
 * Goals:
 * - No growing queues
 * - No leaks
 * - Low overhead: in-place processing, no per-frame allocations
 */
class DspEngine {

    /**
     * A DSP stage processes the PCM buffer IN PLACE.
     *
     * pcm: ByteArray containing PCM16 LE interleaved samples
     * sizeBytes: valid data length in pcm (may be <= pcm.size)
     * channels: 1 or 2
     * sampleRate: e.g. 48000
     */
    fun interface Stage {
        fun processPcm16leInPlace(
            pcm: ByteArray,
            sizeBytes: Int,
            channels: Int,
            sampleRate: Int
        )
    }

    // Atomic swap keeps processing lock-free.
    private val stagesRef = AtomicReference<List<Stage>>(emptyList())

    fun setStages(stages: List<Stage>) {
        stagesRef.set(stages.toList())
    }

    fun clearStages() {
        stagesRef.set(emptyList())
    }

    /**
     * Process PCM in place and return same reference (no allocations).
     */
    fun processInPlace(
        pcm: ByteArray,
        sizeBytes: Int,
        channels: Int,
        sampleRate: Int
    ): ByteArray {
        val stages = stagesRef.get()
        if (stages.isEmpty()) return pcm // pass-through

        val safeSize = sizeBytes.coerceIn(0, pcm.size)
        for (s in stages) {
            s.processPcm16leInPlace(pcm, safeSize, channels, sampleRate)
        }
        return pcm
    }
}