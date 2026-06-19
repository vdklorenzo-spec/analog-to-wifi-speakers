package com.example.analogtowifispeakers

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class AudioCaptureEngine {

    data class State(
        val running: Boolean = false,
        val sampleRate: Int = 48_000,
        val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
        val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
        val lastError: String? = null
    )

    @Volatile
    var state: State = State()
        private set

    @Volatile
    var onLevel: ((Float) -> Unit)? = null

    @Volatile
    var onPeak: ((Float, Boolean) -> Unit)? = null

    // Reusable buffer contract
    @Volatile
    var onPcm: ((ShortArray, Int) -> Unit)? = null

    // Phase 9 DSP hook
    @Volatile
    var dspStage: com.example.analogtowifispeakers.dsp.DspStage =
        com.example.analogtowifispeakers.dsp.PassthroughDsp()

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    private var inBuf: ShortArray = ShortArray(0)
    private var dspOut: ShortArray = ShortArray(0)

    fun start(): Boolean {
        if (isRunning.get()) return true

        val sampleRate = state.sampleRate
        val channelConfig = state.channelConfig
        val audioFormat = state.audioFormat

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            state = state.copy(running = false, lastError = "getMinBufferSize failed: $minBuffer")
            return false
        }

        val bufferSizeBytes = minBuffer * 2

        // 🔥 Critical: choose a “cleaner” source (less AGC/processing)
        val source = pickBestAudioSource()

        val ar = AudioRecord(
            source,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSizeBytes
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            state = state.copy(running = false, lastError = "AudioRecord not initialized (source=$source)")
            try { ar.release() } catch (_: Throwable) {}
            return false
        }

        recorder = ar
        isRunning.set(true)
        state = state.copy(running = true, lastError = null)

        thread = Thread {
            val shortsPerRead = bufferSizeBytes / 2
            if (inBuf.size < shortsPerRead) inBuf = ShortArray(shortsPerRead)
            if (dspOut.size < shortsPerRead) dspOut = ShortArray(shortsPerRead)

            try {
                ar.startRecording()

                while (isRunning.get()) {
                    val read = ar.read(inBuf, 0, inBuf.size)
                    if (read <= 0) continue

                    // DSP
                    val stage = dspStage
                    if (dspOut.size < read) dspOut = ShortArray(read)
                    val outSamples = stage.process(inBuf, read, dspOut)

                    onPcm?.invoke(dspOut, outSamples)

                    // meters on output
                    var sum = 0.0
                    var peak = 0
                    for (i in 0 until outSamples) {
                        val s = dspOut[i].toInt()
                        val a = abs(s)
                        sum += (s * s).toDouble()
                        if (a > peak) peak = a
                    }

                    val rms = sqrt(sum / outSamples) / 32768.0
                    val peakNorm = peak / 32768f
                    val clipping = peak >= 32760

                    onLevel?.invoke(rms.toFloat())
                    onPeak?.invoke(peakNorm, clipping)
                }
            } catch (t: Throwable) {
                state = state.copy(lastError = "Capture thread crash: ${t.message}")
            } finally {
                try { ar.stop() } catch (_: Throwable) {}
                try { ar.release() } catch (_: Throwable) {}
                recorder = null
                isRunning.set(false)
                state = state.copy(running = false)
            }
        }.also {
            it.name = "AudioCaptureEngine"
            it.start()
        }

        return true
    }

    fun stop() {
        isRunning.set(false)
        try { thread?.join(600) } catch (_: Throwable) {}
        thread = null
    }

    private fun pickBestAudioSource(): Int {
        // UNPROCESSED is best when available (Android 7.0+), fallback to VOICE_RECOGNITION
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}