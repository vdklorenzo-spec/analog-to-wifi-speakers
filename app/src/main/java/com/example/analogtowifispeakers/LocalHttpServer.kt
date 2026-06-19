package com.example.analogtowifispeakers

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalHttpServer(
    private val port: Int = 9090
) {

    private val tag = "LocalHttpServer"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var hlsRootDir: File? = null

    fun setHlsRootDir(dir: File?) {
        hlsRootDir = dir
        Log.d(tag, "HLS root dir set to: ${dir?.absolutePath ?: "null"}")
    }

    fun start() {
        if (running.getAndSet(true)) return

        acceptExecutor.execute {
            try {
                val ss = ServerSocket(port).apply {
                    reuseAddress = true
                }
                serverSocket = ss

                Log.d(tag, "Server started on port=$port")

                while (running.get()) {
                    try {
                        val socket = ss.accept()
                        clientExecutor.execute { handleClient(socket) }
                    } catch (_: SocketException) {
                        if (!running.get()) break
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Server start failed", t)
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: Throwable) {
                }
                serverSocket = null
                running.set(false)
                Log.d(tag, "Server stopped")
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true

            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: run {
                socket.close()
                return
            }

            var line: String?
            do {
                line = input.readLine()
            } while (line != null && line.isNotEmpty())

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.trim().orEmpty()
            val rawPath = parts.getOrNull(1)?.trim().orEmpty()

            if (method != "GET") {
                writeText(socket.getOutputStream(), 405, "Method Not Allowed")
                return
            }

            val path = normalizePath(rawPath)
            Log.d(tag, "HTTP GET $path from ${socket.inetAddress?.hostAddress}:${socket.port}")

            when {
                path == "/" -> {
                    writeText(
                        socket.getOutputStream(),
                        200,
                        buildString {
                            appendLine("AnalogToWifiSpeakers local HLS server")
                            appendLine()
                            appendLine("Available endpoints:")
                            appendLine("/live.m3u8")
                            appendLine("/audio.m3u8")
                        }
                    )
                }

                path == "/health" -> {
                    writeText(socket.getOutputStream(), 200, "OK")
                }

                path == "/live.m3u8" -> {
                    serveMasterPlaylist(socket.getOutputStream())
                }

                path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".aac") || path.endsWith(".pcm") -> {
                    serveHlsFile(socket.getOutputStream(), path)
                }

                else -> {
                    writeText(socket.getOutputStream(), 404, "Not Found: $path")
                }
            }

        } catch (t: Throwable) {
            Log.w(tag, "Client handler failed", t)
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun serveMasterPlaylist(out: OutputStream) {
        val body = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:6")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=128000,AVERAGE-BANDWIDTH=96000,CODECS=\"mp4a.40.2\"")
            appendLine("audio.m3u8")
        }

        val bytes = body.toByteArray(Charsets.UTF_8)

        val headers =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n"

        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()

        Log.d(tag, "Served live.m3u8 (${bytes.size} bytes)")
    }

    private fun serveHlsFile(out: OutputStream, requestPath: String) {
        val root = hlsRootDir
        if (root == null) {
            writeText(out, 503, "HLS root directory not set")
            return
        }

        val cleanName = requestPath.removePrefix("/")
        val requestedFile = File(root, cleanName)

        if (!requestedFile.exists() || !requestedFile.isFile) {
            Log.w(tag, "File not found: ${requestedFile.absolutePath}")
            writeText(out, 404, "Not Found: $cleanName")
            return
        }

        val mimeType = when {
            cleanName.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            cleanName.endsWith(".ts") -> "video/mp2t"
            cleanName.endsWith(".aac") -> "audio/aac"
            cleanName.endsWith(".pcm") -> "application/octet-stream"
            else -> "application/octet-stream"
        }

        val fileLength = requestedFile.length()

        val headers =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mimeType\r\n" +
                    "Content-Length: $fileLength\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n"

        out.write(headers.toByteArray(Charsets.US_ASCII))

        FileInputStream(requestedFile).use { fis ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }

        out.flush()
        Log.d(tag, "Served $cleanName ($fileLength bytes)")
    }

    private fun normalizePath(raw: String): String {
        var path = raw.trim()

        val qIndex = path.indexOf('?')
        if (qIndex >= 0) path = path.substring(0, qIndex)

        val hashIndex = path.indexOf('#')
        if (hashIndex >= 0) path = path.substring(0, hashIndex)

        if (path.isBlank()) path = "/"
        if (!path.startsWith("/")) path = "/$path"

        while (path.contains("//")) {
            path = path.replace("//", "/")
        }

        return path
    }

    private fun writeText(out: OutputStream, code: Int, body: String) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "OK"
        }

        val bytes = body.toByteArray(Charsets.UTF_8)

        val headers =
            "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n"

        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}