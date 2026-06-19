package com.example.analogtowifispeakers

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalHttpAudioServer(
    private val port: Int = 8080
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    private var streamingController: StreamingController? = null

    fun attachController(controller: StreamingController) {
        streamingController = controller
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        acceptExecutor.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss

                while (isRunning.get()) {
                    val socket = ss.accept()
                    clientExecutor.execute { handleClient(socket) }
                }
            } catch (_: Exception) {
                // ignore
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                isRunning.set(false)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: run {
                socket.close()
                return
            }

            // Read & discard headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val rawTarget = parts.getOrNull(1) ?: "/"

            val out = socket.getOutputStream()

            if (method != "GET") {
                writePlainText(out, 405, "Method Not Allowed")
                socket.close()
                return
            }

            // ✅ Normalize target into a clean path: /live.aac, /health, /
            val path = normalizePath(rawTarget)

            when {
                path == "/" || path == "/index.html" -> {
                    writePlainText(
                        out,
                        200,
                        "AnalogToWifiSpeakers local server is running.\n\nTry:\n/live.aac\n/health\n"
                    )
                    socket.close()
                }

                path == "/health" -> {
                    writePlainText(out, 200, "OK")
                    socket.close()
                }

                path == "/live.aac" -> {
                    writeLiveAacHeaders(out)

                    val controller = streamingController
                    if (controller != null) {
                        controller.registerChunkedAacClient(socket, out)
                    }
                    // Keep connection open for live streaming
                    return
                }

                else -> {
                    writePlainText(out, 404, "Not Found: $path")
                    socket.close()
                }
            }
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles:
     * - absolute-form: "http://ip:8080/live.aac"
     * - querystrings: "/live.aac?x=1"
     * - trailing slashes: "/live.aac/"
     * - double slashes: "//live.aac"
     */
    private fun normalizePath(rawTarget: String): String {
        // Strip query/fragment early if present
        var target = rawTarget

        // If absolute URL was sent, extract path via URI parsing
        if (target.startsWith("http://") || target.startsWith("https://")) {
            target = try {
                URI(target).path ?: "/"
            } catch (_: Exception) {
                "/"
            }
        }

        // If it contains ? or #, strip them
        val q = target.indexOf('?')
        if (q >= 0) target = target.substring(0, q)
        val h = target.indexOf('#')
        if (h >= 0) target = target.substring(0, h)

        // Ensure it starts with /
        if (!target.startsWith("/")) target = "/$target"

        // Collapse multiple leading slashes
        while (target.startsWith("//")) target = target.substring(1)

        // Remove trailing slash except for root
        if (target.length > 1 && target.endsWith("/")) {
            target = target.dropLast(1)
        }

        return target
    }

    private fun writeLiveAacHeaders(out: OutputStream) {
        val headers =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/aac\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writePlainText(out: OutputStream, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }

        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers =
            "HTTP/1.1 $code $status\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}