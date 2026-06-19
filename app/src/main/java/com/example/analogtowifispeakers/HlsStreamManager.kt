package com.example.analogtowifispeakers

import java.util.Locale
import kotlin.math.ceil

object HlsStreamManager {

    private const val SEGMENT_DURATION_MS = 3000L
    private const val MAX_SEGMENTS = 12
    private const val MIN_SEGMENTS_BEFORE_ADVERTISING = 3

    data class Segment(
        val id: Int,
        val data: ByteArray,
        val durationSec: Float
    )

    private val segments = mutableListOf<Segment>()
    private val currentRawAacFrames = mutableListOf<ByteArray>()

    private val tsWriter = MpegTsWriter(
        sampleRate = 48_000,
        channelCount = 1
    )

    private var segmentId = 0
    private var segmentStartTime = System.currentTimeMillis()

    @Synchronized
    fun addAacFrame(frame: ByteArray) {
        if (frame.isEmpty()) return

        currentRawAacFrames.add(frame.copyOf())

        val now = System.currentTimeMillis()
        val elapsed = now - segmentStartTime

        if (elapsed >= SEGMENT_DURATION_MS) {
            finalizeSegment(elapsed)
            segmentStartTime = now
        }
    }

    @Synchronized
    fun getMasterPlaylist(): String {
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Live Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n")
            append("#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\",AUDIO=\"audio\"\n")
            append("audio.m3u8\n")
        }
    }

    @Synchronized
    fun getMediaPlaylist(): String {

        val advertisedSegments =
            if (segments.size >= MIN_SEGMENTS_BEFORE_ADVERTISING)
                segments.toList()
            else
                emptyList()

        val targetDuration = segments
            .maxOfOrNull { ceil(it.durationSec.toDouble()).toInt() }
            ?.coerceAtLeast(1) ?: 1

        val firstSeq = segments.firstOrNull()?.id ?: 0

        return buildString {

            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:$targetDuration\n")
            append("#EXT-X-MEDIA-SEQUENCE:$firstSeq\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")

            for (seg in advertisedSegments) {
                append("#EXTINF:${String.format(Locale.US, "%.3f", seg.durationSec)},\n")
                append("seg_${seg.id}.ts\n")
            }
        }
    }

    @Synchronized
    fun getSegment(id: Int): ByteArray? {
        return segments.firstOrNull { it.id == id }?.data
    }

    @Synchronized
    fun reset() {
        segments.clear()
        currentRawAacFrames.clear()
        segmentId = 0
        segmentStartTime = System.currentTimeMillis()
    }

    private fun finalizeSegment(elapsedMs: Long) {

        if (currentRawAacFrames.isEmpty()) return

        val tsBytes = tsWriter.writeAacSegment(currentRawAacFrames)
        if (tsBytes.isEmpty()) {
            currentRawAacFrames.clear()
            return
        }

        val seg = Segment(
            id = segmentId++,
            data = tsBytes,
            durationSec = (elapsedMs / 1000f).coerceAtLeast(0.001f)
        )

        segments.add(seg)

        while (segments.size > MAX_SEGMENTS) {
            segments.removeAt(0)
        }

        currentRawAacFrames.clear()
    }
}