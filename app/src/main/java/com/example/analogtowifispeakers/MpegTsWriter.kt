package com.example.analogtowifispeakers

import java.io.ByteArrayOutputStream
import kotlin.math.min

class MpegTsWriter(
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 1,
    private val aacProfile: Int = 2 // AAC LC
) {

    private val continuityCounters = HashMap<Int, Int>()
    private var nextPts90k: Long = 0L

    private val patPid = 0x0000
    private val pmtPid = 0x0100
    private val audioPid = 0x0101

    private val adtsFreqIndex = sampleRateToAdtsFreqIndex(sampleRate)
    private val frameDuration90k = (1024L * 90_000L) / sampleRate.toLong()

    fun writeAacSegment(rawAacFrames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        writePat(out)
        writePmt(out)

        for (rawFrame in rawAacFrames) {
            if (rawFrame.isEmpty()) continue

            val pts90k = nextPts90k
            val adtsFrame = ensureAdtsFrame(rawFrame)
            val pes = buildPesPacket(adtsFrame, pts90k)

            writePesAsTs(
                out = out,
                pid = audioPid,
                pes = pes,
                pcrBase90k = pts90k
            )

            nextPts90k += frameDuration90k
        }

        return out.toByteArray()
    }

    private fun writePat(out: ByteArrayOutputStream) {
        val section = ByteArrayOutputStream()

        section.write(0x00) // table_id
        section.write(0xB0)
        section.write(0x0D) // section_length

        section.write(0x00)
        section.write(0x01) // transport_stream_id

        section.write(0xC1) // version + current_next
        section.write(0x00) // section_number
        section.write(0x00) // last_section_number

        section.write(0x00)
        section.write(0x01) // program_number

        section.write(0xE0 or ((pmtPid shr 8) and 0x1F))
        section.write(pmtPid and 0xFF)

        val sectionBytesWithoutCrc = section.toByteArray()
        val crc = mpegCrc32(sectionBytesWithoutCrc)

        val payload = ByteArrayOutputStream()
        payload.write(0x00) // pointer_field
        payload.write(sectionBytesWithoutCrc)
        writeInt(payload, crc)

        writeSingleTsPacket(out, patPid, true, payload.toByteArray())
    }

    private fun writePmt(out: ByteArrayOutputStream) {
        val section = ByteArrayOutputStream()

        section.write(0x02) // table_id
        section.write(0xB0)
        section.write(0x12) // section_length

        section.write(0x00)
        section.write(0x01) // program_number

        section.write(0xC1) // version + current_next
        section.write(0x00) // section_number
        section.write(0x00) // last_section_number

        // PCR PID = audio PID
        section.write(0xE0 or ((audioPid shr 8) and 0x1F))
        section.write(audioPid and 0xFF)

        // program_info_length = 0
        section.write(0xF0)
        section.write(0x00)

        // stream_type = 0x0F (AAC with ADTS)
        section.write(0x0F)

        section.write(0xE0 or ((audioPid shr 8) and 0x1F))
        section.write(audioPid and 0xFF)

        // ES_info_length = 0
        section.write(0xF0)
        section.write(0x00)

        val sectionBytesWithoutCrc = section.toByteArray()
        val crc = mpegCrc32(sectionBytesWithoutCrc)

        val payload = ByteArrayOutputStream()
        payload.write(0x00) // pointer_field
        payload.write(sectionBytesWithoutCrc)
        writeInt(payload, crc)

        writeSingleTsPacket(out, pmtPid, true, payload.toByteArray())
    }

    private fun buildPesPacket(adtsFrame: ByteArray, pts90k: Long): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0xC0) // stream_id = audio stream 0

        val pesPacketLength = adtsFrame.size + 8
        out.write((pesPacketLength shr 8) and 0xFF)
        out.write(pesPacketLength and 0xFF)

        out.write(0x80) // '10'
        out.write(0x80) // PTS only
        out.write(0x05) // PES_header_data_length

        writePts(out, pts90k)

        out.write(adtsFrame)

        return out.toByteArray()
    }

    private fun writePesAsTs(
        out: ByteArrayOutputStream,
        pid: Int,
        pes: ByteArray,
        pcrBase90k: Long
    ) {
        var offset = 0
        var firstPacket = true

        while (offset < pes.size) {
            val remaining = pes.size - offset
            val cc = nextContinuityCounter(pid)

            val header = ByteArray(4)
            header[0] = 0x47.toByte()
            header[1] = (((if (firstPacket) 0x40 else 0x00) or ((pid shr 8) and 0x1F)) and 0xFF).toByte()
            header[2] = (pid and 0xFF).toByte()

            if (firstPacket) {
                header[3] = (0x30 or cc).toByte() // adaptation + payload
                out.write(header)

                val payloadCapacity = 188 - 4 - 1 - 1 - 6
                val copy = min(payloadCapacity, remaining)
                val stuffingBytes = payloadCapacity - copy
                val adaptationFieldLength = 1 + 6 + stuffingBytes

                out.write(adaptationFieldLength)
                out.write(0x10) // PCR flag

                writePcr(out, pcrBase90k)

                repeat(stuffingBytes) {
                    out.write(0xFF)
                }

                out.write(pes, offset, copy)
                offset += copy

            } else {
                if (remaining >= 184) {
                    header[3] = (0x10 or cc).toByte() // payload only
                    out.write(header)
                    out.write(pes, offset, 184)
                    offset += 184
                } else {
                    header[3] = (0x30 or cc).toByte() // adaptation + payload
                    out.write(header)

                    val adaptationFieldLength = 183 - remaining
                    out.write(adaptationFieldLength)

                    if (adaptationFieldLength > 0) {
                        out.write(0x00) // no flags
                        repeat(adaptationFieldLength - 1) {
                            out.write(0xFF)
                        }
                    }

                    out.write(pes, offset, remaining)
                    offset += remaining
                }
            }

            firstPacket = false
        }
    }

    private fun writeSingleTsPacket(
        out: ByteArrayOutputStream,
        pid: Int,
        payloadUnitStart: Boolean,
        payload: ByteArray
    ) {
        require(payload.size <= 184)

        val cc = nextContinuityCounter(pid)

        val header = ByteArray(4)
        header[0] = 0x47.toByte()
        header[1] = (((if (payloadUnitStart) 0x40 else 0x00) or ((pid shr 8) and 0x1F)) and 0xFF).toByte()
        header[2] = (pid and 0xFF).toByte()
        header[3] = (0x30 or cc).toByte() // adaptation + payload

        out.write(header)

        val adaptationFieldLength = 183 - payload.size
        out.write(adaptationFieldLength)

        if (adaptationFieldLength > 0) {
            out.write(0x00) // no flags
            repeat(adaptationFieldLength - 1) {
                out.write(0xFF)
            }
        }

        out.write(payload)
    }

    private fun writePcr(out: ByteArrayOutputStream, pcrBase90k: Long) {
        val base = pcrBase90k and 0x1FFFFFFFFL
        val ext = 0L

        out.write(((base shr 25) and 0xFFL).toInt())
        out.write(((base shr 17) and 0xFFL).toInt())
        out.write(((base shr 9) and 0xFFL).toInt())
        out.write(((base shr 1) and 0xFFL).toInt())
        out.write((((((base and 0x1L) shl 7) or 0x7EL or ((ext shr 8) and 0x1L)) and 0xFFL)).toInt())
        out.write((ext and 0xFFL).toInt())
    }

    private fun ensureAdtsFrame(frame: ByteArray): ByteArray {
        if (frame.size >= 7) {
            val b0 = frame[0].toInt() and 0xFF
            val b1 = frame[1].toInt() and 0xFF

            val hasAdtsSync = (b0 == 0xFF) && ((b1 and 0xF0) == 0xF0)
            if (hasAdtsSync) {
                return frame
            }
        }

        return buildAdtsFrame(frame)
    }

    private fun buildAdtsFrame(rawAac: ByteArray): ByteArray {
        val header = buildAdtsHeader(rawAac.size, aacProfile, adtsFreqIndex, channelCount)

        val out = ByteArray(header.size + rawAac.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(rawAac, 0, out, header.size, rawAac.size)
        return out
    }

    private fun buildAdtsHeader(
        aacPayloadLength: Int,
        profile: Int,
        freqIndex: Int,
        channelConfig: Int
    ): ByteArray {
        val frameLength = aacPayloadLength + 7
        val profileMinus1 = (profile - 1).coerceAtLeast(0)

        val packet = ByteArray(7)

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF1.toByte()
        packet[2] =
            (((profileMinus1 shl 6) and 0xC0) or
                    ((freqIndex shl 2) and 0x3C) or
                    ((channelConfig shr 2) and 0x01)).toByte()
        packet[3] =
            (((channelConfig shl 6) and 0xC0) or
                    ((frameLength shr 11) and 0x03)).toByte()
        packet[4] = ((frameLength shr 3) and 0xFF).toByte()
        packet[5] = (((frameLength shl 5) and 0xE0) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()

        return packet
    }

    private fun writePts(out: ByteArrayOutputStream, pts: Long) {
        val value = pts and 0x1FFFFFFFFL

        out.write((((value shr 29) and 0x0E) or 0x21).toInt())
        out.write(((value shr 22) and 0xFF).toInt())
        out.write((((value shr 14) and 0xFE) or 0x01).toInt())
        out.write(((value shr 7) and 0xFF).toInt())
        out.write((((value shl 1) and 0xFE) or 0x01).toInt())
    }

    private fun nextContinuityCounter(pid: Int): Int {
        val current = continuityCounters[pid] ?: 0
        continuityCounters[pid] = (current + 1) and 0x0F
        return current
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun sampleRateToAdtsFreqIndex(sr: Int): Int {
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
            else -> 3
        }
    }

    private fun mpegCrc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()

        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)

            repeat(8) {
                crc = if ((crc and 0x80000000.toInt()) != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }

        return crc
    }
}