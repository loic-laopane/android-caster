package com.caster.app.screen

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenStreamServer(val port: Int = 8080) {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var out: OutputStream? = null
    private val active = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>(120)

    fun start() {
        active.set(true)
        serverSocket = ServerSocket(port)
        Thread({
            try {
                Log.i(TAG, "Waiting for client on port $port...")
                clientSocket = serverSocket!!.accept()
                Log.i(TAG, "Client connected: ${clientSocket!!.inetAddress.hostAddress}")
                out = clientSocket!!.getOutputStream()
                out!!.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: video/h264\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                while (active.get()) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out!!.write(chunk)
                }
            } catch (e: Exception) {
                if (active.get()) Log.w(TAG, "Stream ended: ${e.message}")
            }
        }, "screen-stream").start()
    }

    fun write(data: ByteArray) {
        if (active.get() && data.isNotEmpty()) queue.offer(data)
    }

    fun stop() {
        active.set(false)
        queue.clear()
        try { clientSocket?.close() } catch (e: Exception) { /* ignore */ }
        try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
    }

    companion object {
        private const val TAG = "ScreenStreamServer"
    }
}
