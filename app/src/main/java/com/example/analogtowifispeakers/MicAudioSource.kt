package com.example.analogtowifispeakers.audio

import android.media.*
import android.os.Process

/**
 * Microfoon-based AudioSource.
 * Levert PCM_16BIT chunks (little-endian) via callback.
 *
 * Stap 1: enkel capture (nog geen encoder, geen server push).
 */
class MicAudioSource(
    sampleRate: Int = 48_000,
    channelCount: Int = 1
) : AudioSource {

    override val format: AudioSource.Format = AudioSource.Format(
        sampleRate = sampleRate,
        channelCount = channelCount,
        encoding = AudioFormat.ENCODING_PCM_16BIT
    )

    private var audioRecord: AudioRecord? = null
    @Volatile private var running: Boolean = false
    private var thread: Thread? = null

    override fun start(onPcm: (AudioSource.PcmChunk) -> Unit): Boolean {
        if (running) return true

        val channelMask = if (format.channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBuf = AudioRecord.getMinBufferSize(
            format.sampleRate,
            channelMask,
            format.encoding
        )

        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        val bufferBytes = (minBuf * 2).coerceAtLeast(4096)

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(format.sampleRate)
            .setEncoding(format.encoding)
            .setChannelMask(channelMask)
            .build()

        // Probeer UNPROCESSED (lager “Android magic”), fallback naar MIC.
        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        var record: AudioRecord? = null
        for (src in sourcesToTry) {
            try {
                record = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()

                if (record.state == AudioRecord.STATE_INITIALIZED) break
                record.release()
                record = null
            } catch (_: Throwable) {
                record?.release()
                record = null
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return false
        }

        audioRecord = record
        running = true

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val local = audioRecord ?: return@Thread
            val buf = ByteArray(bufferBytes)

            try {
                local.startRecording()
            } catch (_: Throwable) {
                running = false
                return@Thread
            }

            while (running) {
                val read = try {
                    local.read(buf, 0, buf.size)
                } catch (_: Throwable) {
                    break
                }

                if (read > 0) {
                    // Copy exact read length so downstream niet met oversized buffer zit.
                    val out = ByteArray(read)
                    System.arraycopy(buf, 0, out, 0, read)

                    onPcm(
                        AudioSource.PcmChunk(
                            data = out,
                            sizeBytes = read,
                            timestampNs = System.nanoTime()
                        )
                    )
                }
                // read == 0: niks, loop verder
                // read < 0: error, break
                if (read < 0) break
            }

            try {
                local.stop()
            } catch (_: Throwable) {
                // ignore
            }
        }.apply { name = "MicAudioSource-Thread"; start() }

        return true
    }

    override fun stop() {
        running = false
        try {
            thread?.join(300)
        } catch (_: Throwable) {
            // ignore
        }
        thread = null
        // audioRecord.stop() gebeurt in thread cleanup; force stop als die nog leeft:
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            // ignore
        }
    }

    override fun release() {
        stop()
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
            // ignore
        } finally {
            audioRecord = null
        }
    }
}