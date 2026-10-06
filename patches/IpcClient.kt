package com.xmicinject

import android.util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

internal object IpcClient {

    private const val TAG = "XMicIpcClient"
    private const val HOST = "0.0.0.0"
    private const val PORT = 38673
    private const val READ_CHUNK = 4096
    private const val CLIENT_READ_TIMEOUT_MS = 5000

    @Volatile
    private var started = false

    @Volatile
    var muteRealMic: Boolean = false
        private set

    fun startOnce() {
        if (started) return

        synchronized(this) {
            if (started) return
            started = true

            val thread = Thread(::serverLoop)
            thread.isDaemon = true
            thread.name = "xmicinject-server"
            thread.start()
        }
    }

    fun write(data: ByteArray) {
        // One-way V6 transport: uplink is intentionally unused.
    }

    private fun serverLoop() {
        while (true) {
            var server: ServerSocket? = null

            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(HOST, PORT))

                Log.i(TAG, "Listening on 0.0.0.0:38673")

                while (true) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Server failed: " + t.message)
            } finally {
                muteRealMic = false
                PcmRingBuffer.clear()
                UplinkSender.reset()
                runCatching { server?.close() }
            }

            Thread.sleep(1000)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = 16 * 1024
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS

            Log.i(
                TAG,
                "Provider connected from " +
                    socket.inetAddress.hostAddress +
                    ":" +
                    socket.port
            )

            muteRealMic = true

            val input = socket.inputStream
            val buf = ByteArray(READ_CHUNK)

            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                PcmRingBuffer.write(buf, 0, n)
            }
        } catch (t: SocketTimeoutException) {
            Log.w(TAG, "Provider stalled for 5000ms")
        } catch (t: Throwable) {
            Log.w(TAG, "Provider connection ended: " + t.message)
        } finally {
            muteRealMic = false
            PcmRingBuffer.clear()
            UplinkSender.reset()
            runCatching { socket.close() }
            Log.i(TAG, "Provider disconnected")
        }
    }
}
