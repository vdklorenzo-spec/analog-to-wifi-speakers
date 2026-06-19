package com.example.analogtowifispeakers

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AacEncoder(
    private val sampleRateHz: Int = 48000,
    private val channelCount: Int = 1,
    private val bitRate: Int = 128_000
) {
    private var codec: MediaCodec? = null
    private val started = AtomicBoolean(false)

    private val ptsUs = object {
        var value: Long = 0L
    }

    /**
     * Callback gets a full ADTS frame (AAC raw + ADTS header) ready to send.
     */
    var onAdtsFrame: ((ByteArray) -> Unit)? = null

    fun start() {
        if (started.getAndSet(true)) return

        val format = MediaFormat.createAudioFormat(MIME, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        ptsUs.value = 0L
    }

    fun stop() {
        started.set(false)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    /**
     * Feed PCM 16-bit little-endian mono/stereo data.
     * pcm: ShortArray
     * size: number of valid shorts
     */
    fun encodePcm(pcm: ShortArray, size: Int) {
        val c = codec ?: return
        if (!started.get()) return
        if (size <= 0) return

        // Convert ShortArray -> ByteArray (little-endian)
        val pcmBytes = ByteArray(size * 2)
        var bi = 0
        for (i in 0 until size) {
            val s = pcm[i].toInt()
            pcmBytes[bi++] = (s and 0xFF).toByte()
            pcmBytes[bi++] = ((s shr 8) and 0xFF).toByte()
        }

        // Queue input
        val inIndex = c.dequeueInputBuffer(0)
        if (inIndex >= 0) {
            val inBuf: ByteBuffer? = c.getInputBuffer(inIndex)
            inBuf?.clear()
            inBuf?.put(pcmBytes)

            // PTS: advance by number of samples
            // samples per channel = size / channelCount
            val samplesPerChannel = size / channelCount
            val frameDurationUs = (samplesPerChannel * 1_000_000L) / sampleRateHz
            val presentationTimeUs = ptsUs.value
            ptsUs.value += frameDurationUs

            c.queueInputBuffer(inIndex, 0, pcmBytes.size, presentationTimeUs, 0)
        }

        // Drain output (non-blocking)
        drainOutput(c)
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ignore, but could inspect c.outputFormat if you want
            } else if (outIndex >= 0) {
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    val aacRaw = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    outBuf.get(aacRaw)

                    val adts = buildAdtsHeader(
                        aacPayloadLength = aacRaw.size,
                        sampleRate = sampleRateHz,
                        channelCount = channelCount
                    )

                    val frame = ByteArray(adts.size + aacRaw.size)
                    System.arraycopy(adts, 0, frame, 0, adts.size)
                    System.arraycopy(aacRaw, 0, frame, adts.size, aacRaw.size)

                    onAdtsFrame?.invoke(frame)
                    HlsStreamManager.addAacFrame(frame)
                }
                c.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    private fun buildAdtsHeader(aacPayloadLength: Int, sampleRate: Int, channelCount: Int): ByteArray {
        // ADTS header is 7 bytes
        val adtsLen = 7
        val frameLength = adtsLen + aacPayloadLength

        val freqIdx = sampleRateToAdtsFreqIndex(sampleRate)
        val chanCfg = channelCount

        val packet = ByteArray(adtsLen)

        // Syncword 0xFFF (12), MPEG-4 (1), layer (2), protection_absent (1)
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF1.toByte() // 1111 0001

        // profile (2) AAC LC = 2 -> store as (profile-1) in ADTS
        val profileMinus1 = 2 - 1 // AAC LC
        packet[2] = (((profileMinus1 shl 6) and 0xC0) or ((freqIdx shl 2) and 0x3C) or ((chanCfg shr 2) and 0x01)).toByte()
        packet[3] = (((chanCfg shl 6) and 0xC0) or ((frameLength shr 11) and 0x03)).toByte()
        packet[4] = ((frameLength shr 3) and 0xFF).toByte()
        packet[5] = (((frameLength shl 5) and 0xE0) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()

        return packet
    }

    private fun sampleRateToAdtsFreqIndex(sr: Int): Int {
        // ADTS sampling_frequency_index table
        return when (sr) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 3 // default to 48000
        }
    }

    companion object {
        private const val MIME = "audio/mp4a-latm"
    }
}