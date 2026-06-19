package com.example.analogtowifispeakers

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class StreamingController {

    private data class Client(
        val socket: Socket,
        val out: OutputStream
    )

    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Client>()

    fun start() {
        running.set(true)
    }

    fun stop() {
        running.set(false)
        for (c in clients) {
            try { c.out.flush() } catch (_: Exception) {}
            try { c.socket.close() } catch (_: Exception) {}
        }
        clients.clear()
    }

    fun registerChunkedAacClient(socket: Socket, rawOut: OutputStream) {
        val out = BufferedOutputStream(rawOut)
        clients.add(Client(socket, out))
    }

    fun pushAdtsFrame(adtsFrame: ByteArray) {
        if (!running.get()) return
        if (adtsFrame.isEmpty()) return
        if (clients.isEmpty()) return

        val chunkHeader = adtsFrame.size.toString(16) + "\r\n"
        val chunkFooter = "\r\n"

        val toRemove = ArrayList<Client>()

        for (c in clients) {
            try {
                c.out.write(chunkHeader.toByteArray(Charsets.US_ASCII))
                c.out.write(adtsFrame)
                c.out.write(chunkFooter.toByteArray(Charsets.US_ASCII))
                c.out.flush()
            } catch (_: Exception) {
                toRemove.add(c)
            }
        }

        if (toRemove.isNotEmpty()) {
            for (c in toRemove) {
                clients.remove(c)
                try { c.socket.close() } catch (_: Exception) {}
            }
        }
    }
}