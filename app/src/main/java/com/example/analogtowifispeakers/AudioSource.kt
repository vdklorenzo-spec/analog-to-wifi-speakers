package com.example.analogtowifispeakers.audio

import android.media.AudioFormat

/**
 * Input-agnostische audio bron.
 * Levert PCM chunks (nog GEEN encoder in Stap 1).
 */
interface AudioSource {

    data class PcmChunk(
        /** Raw PCM bytes (meestal PCM_16BIT, little-endian) */
        val data: ByteArray,
        /** Aantal geldige bytes in data */
        val sizeBytes: Int,
        /** Monotone timestamp (System.nanoTime) op moment van read */
        val timestampNs: Long
    )

    data class Format(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int = AudioFormat.ENCODING_PCM_16BIT
    )

    val format: Format

    /**
     * Start capture. onPcm wordt op een audio thread aangeroepen.
     * Return true als start gelukt is.
     */
    fun start(onPcm: (PcmChunk) -> Unit): Boolean

    /** Stop capture (idempotent). */
    fun stop()

    /** Release resources (idempotent). */
    fun release()
}