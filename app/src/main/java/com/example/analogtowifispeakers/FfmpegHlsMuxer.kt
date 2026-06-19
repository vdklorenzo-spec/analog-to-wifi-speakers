package com.example.analogtowifispeakers

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Session
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FfmpegHlsMuxer(
    context: Context,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitrate: Int,
    private val onFatalError: (Throwable) -> Unit
) {

    private val appContext = context.applicationContext
    private val tag = "FfmpegHlsMuxer"

    val outputDir: File = File(appContext.filesDir, "hls-live").apply { mkdirs() }

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>(48)

    @Volatile
    private var pipePath: String? = null

    @Volatile
    private var pipeOut: BufferedOutputStream? = null

    @Volatile
    private var ffmpegSession: Session? = null

    private var writerThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true

        try {
            cleanOutputDir()

            val newPipePath = FFmpegKitConfig.registerNewFFmpegPipe(appContext)
            pipePath = newPipePath

            val playlistPath = File(outputDir, "audio.m3u8").absolutePath
            val segmentPattern = File(outputDir, "seg_%03d.ts").absolutePath

            val cmd = buildString {
                append("-hide_banner ")
                append("-loglevel warning ")
                append("-fflags +genpts+nobuffer ")
                append("-flags low_delay ")
                append("-thread_queue_size 64 ")
                append("-f s16le ")
                append("-ar $sampleRate ")
                append("-ac $channelCount ")
                append("-i \"$newPipePath\" ")
                append("-vn ")
                append("-c:a aac ")
                append("-b:a $bitrate ")
                append("-ar $sampleRate ")
                append("-ac $channelCount ")
                append("-f hls ")
                append("-hls_time 1 ")
                append("-hls_list_size 4 ")
                append("-hls_segment_type mpegts ")
                append("-hls_flags delete_segments+append_list+omit_endlist+independent_segments ")
                append("-hls_allow_cache 0 ")
                append("-hls_segment_filename \"$segmentPattern\" ")
                append("\"$playlistPath\"")
            }

            Log.d(tag, "FFmpeg command: $cmd")

            running.set(true)

            ffmpegSession = FFmpegKit.executeAsync(cmd) { session ->
                val rc = session.returnCode
                if (rc != null && rc.isValueSuccess) {
                    Log.d(tag, "FFmpeg finished normally")
                } else {
                    Log.e(tag, "FFmpeg ended with rc=$rc")
                    if (running.get()) {
                        onFatalError(IllegalStateException("FFmpeg ended unexpectedly: rc=$rc"))
                    }
                }
            }

            startWriterThread(newPipePath)

            return true
        } catch (t: Throwable) {
            Log.e(tag, "start() failed", t)
            stop()
            onFatalError(t)
            return false
        }
    }

    fun offerPcm(pcm: ByteArray, sizeBytes: Int): Boolean {
        if (!running.get()) return false
        if (sizeBytes <= 0) return false

        val copy = ByteArray(sizeBytes)
        System.arraycopy(pcm, 0, copy, 0, sizeBytes)

        if (!queue.offer(copy)) {
            queue.poll()
            return queue.offer(copy)
        }

        return true
    }

    fun stop() {
        running.set(false)
        queue.clear()

        try {
            writerThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            writerThread?.join(500)
        } catch (_: Throwable) {
        }
        writerThread = null

        try {
            pipeOut?.flush()
        } catch (_: Throwable) {
        }
        try {
            pipeOut?.close()
        } catch (_: Throwable) {
        }
        pipeOut = null

        try {
            ffmpegSession?.cancel()
        } catch (_: Throwable) {
        }
        ffmpegSession = null

        val oldPipe = pipePath
        pipePath = null
        if (oldPipe != null) {
            try {
                FFmpegKitConfig.closeFFmpegPipe(oldPipe)
            } catch (_: Throwable) {
            }
        }
    }

    private fun startWriterThread(currentPipePath: String) {
        writerThread = Thread {
            try {
                Log.d(tag, "Opening FFmpeg pipe for writing: $currentPipePath")
                val out = BufferedOutputStream(FileOutputStream(currentPipePath), 64 * 1024)
                pipeOut = out
                Log.d(tag, "FFmpeg pipe opened for writing")

                while (running.get()) {
                    val chunk = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    out.write(chunk)
                    out.flush()
                }
            } catch (_: InterruptedException) {
                Log.d(tag, "Writer interrupted")
            } catch (t: Throwable) {
                if (running.get()) {
                    Log.e(tag, "Writer thread failed", t)
                    onFatalError(t)
                }
            } finally {
                try {
                    pipeOut?.flush()
                } catch (_: Throwable) {
                }
                try {
                    pipeOut?.close()
                } catch (_: Throwable) {
                }
                pipeOut = null
            }
        }.apply {
            name = "FfmpegPipeWriter"
            isDaemon = true
            start()
        }
    }

    private fun cleanOutputDir() {
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { file ->
            if (
                file.isFile &&
                (file.name.endsWith(".m3u8") ||
                        file.name.endsWith(".ts") ||
                        file.name.endsWith(".pcm"))
            ) {
                file.delete()
            }
        }
    }
}