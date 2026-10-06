package com.xmicinject

import android.util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// Android-side TCP server transport.
//
// Direct LAN / USB-tether network mode for Deto:
//   PC provider -> phone IP:38673 -> LowLatencyPcmRingBuffer -> AudioRecord injection
//
// No adb forward/reverse is used for the audio stream, so ADB remains free for
// scrcpy, shell commands and logcat. Uplink from the phone mic is disabled.
internal object IpcClient {

    private const val TAG = "XMicIpcClient"
    private const val HOST = "0.0.0.0"
    private const val PORT = 38673
    private const val READ_CHUNK = 4096
    private const val CLIENT_READ_TIMEOUT_MS = 5000

    @Volatile private var started = false

    @Volatile var muteRealMic: Boolean = false
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

    // Uplink intentionally disabled in one-way mode.
    fun write(data: ByteArray) {
        // no-op
    }

    private fun serverLoop() {
        while (true) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(HOST, PORT))
                Log.i(TAG, "Listening on $HOST:$PORT (direct one-way V4, low-latency 500ms)")

                while (true) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Server failed: ${t.message}")
            } finally {
                muteRealMic = false
                LowLatencyPcmRingBuffer.clear()
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
            Log.i(TAG, "Provider connected from ${socket.inetAddress.hostAddress}:${socket.port} (direct one-way V4, low-latency 500ms)")
            muteRealMic = true

            val input = socket.inputStream
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                LowLatencyPcmRingBuffer.write(buf, 0, n)
            }
        } catch (t: SocketTimeoutException) {
            Log.w(TAG, "Provider stalled for ${CLIENT_READ_TIMEOUT_MS}ms; closing stale socket")
        } catch (t: Throwable) {
            Log.w(TAG, "Provider connection ended: ${t.message}")
        } finally {
            muteRealMic = false
            LowLatencyPcmRingBuffer.clear()
            UplinkSender.reset()
            runCatching { socket.close() }
            Log.i(TAG, "Provider disconnected")
        }
    }
}

// Separate buffer object used by the patched transport/hook so the upstream
// PcmRingBuffer.kt can remain untouched. This keeps the existing GitHub build
// workflow compatible while bounding queued audio to 500 ms.
internal object LowLatencyPcmRingBuffer {

    private const val TAG = "XMicLowLatBuffer"

    const val SAMPLE_RATE_HZ: Int = 16_000
    private const val CAPACITY = SAMPLE_RATE_HZ * 2 / 2  // 500 ms, mono PCM16

    private val data = ByteArray(CAPACITY)
    private var writePos = 0
    private var available = 0
    private val lock = Any()
    private var lastOverflowLogMs = 0L

    val active: AtomicBoolean = AtomicBoolean(false)

    fun write(src: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            if (!active.getAndSet(true)) {
                Log.i(TAG, "Low-latency buffer activated")
            }
            if (available == CAPACITY) {
                val now = System.currentTimeMillis()
                if (now - lastOverflowLogMs > 5_000) {
                    Log.w(TAG, "500ms buffer full — dropping oldest queued audio")
                    lastOverflowLogMs = now
                }
            }

            var remaining = length
            var srcPos = offset
            while (remaining > 0) {
                val chunk = minOf(remaining, CAPACITY - writePos)
                System.arraycopy(src, srcPos, data, writePos, chunk)
                writePos = (writePos + chunk) % CAPACITY
                available = minOf(available + chunk, CAPACITY)
                srcPos += chunk
                remaining -= chunk
            }
        }
    }

    fun readBytes(dst: ByteArray, dstOffset: Int, count: Int): Boolean {
        if (!active.get()) return false
        synchronized(lock) {
            if (available < count) return false

            var readPos = (writePos - available + CAPACITY) % CAPACITY
            var remaining = count
            var dstPos = dstOffset
            while (remaining > 0) {
                val chunk = minOf(remaining, CAPACITY - readPos)
                System.arraycopy(data, readPos, dst, dstPos, chunk)
                readPos = (readPos + chunk) % CAPACITY
                dstPos += chunk
                remaining -= chunk
            }
            available -= count
            return true
        }
    }

    fun readShorts(dst: ShortArray, dstOffset: Int, count: Int): Boolean {
        val bytes = ByteArray(count * 2)
        if (!readBytes(bytes, 0, bytes.size)) return false
        val shorts = AudioResampler.bytesToShorts(bytes, 0, count)
        System.arraycopy(shorts, 0, dst, dstOffset, count)
        return true
    }

    fun clear() {
        synchronized(lock) {
            available = 0
            writePos = 0
            active.set(false)
        }
        Log.i(TAG, "Low-latency buffer cleared")
    }
}
