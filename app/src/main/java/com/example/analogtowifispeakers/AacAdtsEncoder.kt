package com.example.analogtowifispeakers.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AacAdtsEncoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitrate: Int = 96_000,
    private val aacProfile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
    private val inputQueueCapacity: Int = 24
) {

    interface Callback {
        fun onAdtsFrame(frame: ByteArray, ptsUs: Long)
        fun onEncoderError(t: Throwable)
    }

    private data class PcmIn(
        val data: ByteArray,
        val size: Int,
        val timestampNs: Long
    )

    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private val inputQueue = ArrayBlockingQueue<PcmIn>(inputQueueCapacity)
    private var thread: Thread? = null

    fun start(callback: Callback): Boolean {
        if (running.get()) return true

        val c = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (t: Throwable) {
            callback.onEncoderError(t)
            return false
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)

            // BELANGRIJK
            // LATM / LOAS output i.p.v. ADTS
            setInteger(MediaFormat.KEY_IS_ADTS, 1)
        }

        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (t: Throwable) {
            try { c.release() } catch (_: Throwable) {}
            callback.onEncoderError(t)
            return false
        }

        codec = c
        running.set(true)

        thread = Thread {
            try {
                encodeLoop(c, callback)
            } catch (t: Throwable) {
                callback.onEncoderError(t)
            } finally {
                try { c.stop() } catch (_: Throwable) {}
                try { c.release() } catch (_: Throwable) {}
                codec = null
                running.set(false)
                inputQueue.clear()
            }
        }.apply {
            name = "AacAdtsEncoder-Thread"
            start()
        }

        return true
    }

    fun stop() {
        running.set(false)
        try { thread?.join(600) } catch (_: Throwable) {}
        thread = null
        inputQueue.clear()
    }

    fun offerPcm(pcm: ByteArray, size: Int, timestampNs: Long) {
        if (!running.get()) return
        if (size <= 0) return

        val copy = ByteArray(size)
        System.arraycopy(pcm, 0, copy, 0, size)
        val item = PcmIn(copy, size, timestampNs)

        if (!inputQueue.offer(item)) {
            inputQueue.poll()
            inputQueue.offer(item)
        }
    }

    private fun encodeLoop(c: MediaCodec, callback: Callback) {
        val bufferInfo = MediaCodec.BufferInfo()

        fun nsToUs(ns: Long): Long = ns / 1000L

        var pending: PcmIn? = null

        while (running.get()) {

            var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    val aac = ByteArray(bufferInfo.size)
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    outBuf.get(aac)

                    // LATM frame rechtstreeks uitsturen
                    callback.onAdtsFrame(aac, bufferInfo.presentationTimeUs)
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }

            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ok
            }

            if (pending == null) {
                pending = inputQueue.poll(10, TimeUnit.MILLISECONDS)
            }

            val pcm = pending
            if (pcm != null) {
                val inIndex = c.dequeueInputBuffer(2000)
                if (inIndex >= 0) {
                    val inputBuf = c.getInputBuffer(inIndex)
                    if (inputBuf != null) {
                        inputBuf.clear()
                        val toWrite = pcm.size.coerceAtMost(inputBuf.remaining())
                        inputBuf.put(pcm.data, 0, toWrite)
                        c.queueInputBuffer(inIndex, 0, toWrite, nsToUs(pcm.timestampNs), 0)
                        pending = null
                    } else {
                        pending = null
                    }
                }
            } else {
                Thread.sleep(2)
            }
        }
    }
}