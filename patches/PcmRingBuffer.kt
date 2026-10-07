package com.xmicinject

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

// Ring buffer that holds injected PCM audio from the provider.
// Stores audio at SAMPLE_RATE_HZ. Resampling happens in the caller.
internal object PcmRingBuffer {

    private const val TAG = "XMicBuffer"

    const val SAMPLE_RATE_HZ: Int = 16_000
    private const val CAPACITY = SAMPLE_RATE_HZ * 2 / 4  // 250 ms, mono, PCM16

    private val data = ByteArray(CAPACITY)
    private var writePos = 0
    private var available = 0
    private val lock = Any()
    private var lastOverflowLogMs = 0L

    val active: AtomicBoolean = AtomicBoolean(false)

    fun write(src: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            if (!active.getAndSet(true)) {
                Log.i(TAG, "Buffer activated — inject stream flowing")
            }
            if (available == CAPACITY) {
                val now = System.currentTimeMillis()
                if (now - lastOverflowLogMs > 5_000) {
                    Log.w(TAG, "Buffer full — overwriting unread data (provider faster than app reads)")
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

    // Returns false if inactive or not enough data (caller should pass through real mic).
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
            active.set(false)
        }
        Log.i(TAG, "Buffer cleared — passthrough mode")
    }
}
