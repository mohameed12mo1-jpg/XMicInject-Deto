package com.xmicinject

import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class XMicHook : IXposedHookLoadPackage {

    private companion object {
        private const val TAG = "XMicHook"
        private const val DETO = "ae.deto.app"
        private const val MODULE_PACKAGE = "ae.deto.xmicinject"
        private val SKIP_PACKAGES = setOf("android", "com.xmicinject", MODULE_PACKAGE)
        private val MIC_SOURCES = setOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val uid = lpparam.appInfo?.uid ?: Process.INVALID_UID
        if (lpparam.packageName != DETO) return
        if (lpparam.packageName in SKIP_PACKAGES) return
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return

        Log.i(TAG, "Deto hook loaded")

        IpcClient.startOnce()

        val injectionLogged = AtomicBoolean(false)
        val muteLogged = AtomicBoolean(false)

        hookByteArray(lpparam.classLoader, injectionLogged, muteLogged)
        hookShortArray(lpparam.classLoader, injectionLogged, muteLogged)
        hookByteBuffer(lpparam.classLoader, injectionLogged, muteLogged)
    }

    private fun hookByteArray(
        cl: ClassLoader,
        injectionLogged: AtomicBoolean,
        muteLogged: AtomicBoolean
    ) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return

                val buf = param.args[0] as? ByteArray ?: return
                val off = param.args[1] as? Int ?: return
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)

                val replaced = if (isMicSource(record)) {
                    UplinkSender.send(buf, off, count, hz, channelCount(record), streamId(record))
                    injectBytes(buf, off, count, hz)
                } else {
                    injectBytes(buf, off, count, hz)
                }

                if (replaced) {
                    if (injectionLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Injection active: byte[]")
                    }
                } else if (IpcClient.muteRealMic) {
                    val end = minOf(buf.size, off + count)
                    if (off >= 0 && end > off) buf.fill(0, off, end)
                    if (muteLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Real mic muted: byte[]")
                    }
                }
            }
        }

        hookRead(cl, hook, ByteArray::class.java, Int::class.java, Int::class.java)
        hookRead(
            cl,
            hook,
            ByteArray::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java
        )
    }

    private fun hookShortArray(
        cl: ClassLoader,
        injectionLogged: AtomicBoolean,
        muteLogged: AtomicBoolean
    ) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return

                val buf = param.args[0] as? ShortArray ?: return
                val off = param.args[1] as? Int ?: return
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)

                if (isMicSource(record)) {
                    val end = minOf(buf.size, off + count)
                    if (off >= 0 && end > off) {
                        val bytes = AudioResampler.shortsToBytes(buf.copyOfRange(off, end))
                        UplinkSender.send(
                            bytes, 0, bytes.size, hz,
                            channelCount(record), streamId(record)
                        )
                    }
                }

                val replaced = injectShorts(buf, off, count, hz)

                if (replaced) {
                    if (injectionLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Injection active: short[]")
                    }
                } else if (IpcClient.muteRealMic) {
                    val end = minOf(buf.size, off + count)
                    if (off >= 0 && end > off) buf.fill(0, off, end)
                    if (muteLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Real mic muted: short[]")
                    }
                }
            }
        }

        hookRead(cl, hook, ShortArray::class.java, Int::class.java, Int::class.java)
        hookRead(
            cl,
            hook,
            ShortArray::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java
        )
    }

    private fun hookByteBuffer(
        cl: ClassLoader,
        injectionLogged: AtomicBoolean,
        muteLogged: AtomicBoolean
    ) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return

                val buffer = param.args[0] as? ByteBuffer ?: return
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)

                val start = (buffer.position() - count).coerceAtLeast(0)
                val end = minOf(buffer.capacity(), start + count)
                if (end <= start) return

                val bytes = ByteArray(end - start)
                buffer.duplicate().apply {
                    position(start)
                    limit(end)
                }.get(bytes)

                if (isMicSource(record)) {
                    UplinkSender.send(
                        bytes, 0, bytes.size, hz,
                        channelCount(record), streamId(record)
                    )
                }

                val replaced = injectBytes(bytes, 0, bytes.size, hz)

                if (replaced) {
                    buffer.duplicate().apply {
                        position(start)
                        limit(end)
                    }.put(bytes)

                    if (injectionLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Injection active: ByteBuffer")
                    }
                } else if (IpcClient.muteRealMic) {
                    val view = buffer.duplicate().apply {
                        position(start)
                        limit(end)
                    }
                    while (view.hasRemaining()) view.put(0)

                    if (muteLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "Real mic muted: ByteBuffer")
                    }
                }
            }
        }

        hookRead(cl, hook, ByteBuffer::class.java, Int::class.java)
        hookRead(
            cl,
            hook,
            ByteBuffer::class.java,
            Int::class.java,
            Int::class.java
        )
    }

    private fun injectBytes(
        dst: ByteArray,
        offset: Int,
        count: Int,
        targetHz: Int
    ): Boolean {
        if (offset < 0 || count <= 0 || offset >= dst.size) return false
        val safeCount = minOf(count, dst.size - offset)

        if (targetHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            return PcmRingBuffer.readBytes(dst, offset, safeCount)
        }

        val srcBytes = AudioResampler.sourceBytesNeeded(
            safeCount,
            targetHz,
            PcmRingBuffer.SAMPLE_RATE_HZ
        )
        if (srcBytes <= 0) return false

        val tmp = ByteArray(srcBytes)
        if (!PcmRingBuffer.readBytes(tmp, 0, srcBytes)) return false

        val out = AudioResampler.resampleBytes(
            tmp, 0, srcBytes,
            PcmRingBuffer.SAMPLE_RATE_HZ,
            targetHz
        )

        val copyCount = minOf(out.size, safeCount)
        if (copyCount <= 0) return false
        System.arraycopy(out, 0, dst, offset, copyCount)
        return true
    }

    private fun injectShorts(
        dst: ShortArray,
        offset: Int,
        count: Int,
        targetHz: Int
    ): Boolean {
        if (offset < 0 || count <= 0 || offset >= dst.size) return false
        val safeCount = minOf(count, dst.size - offset)

        if (targetHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            return PcmRingBuffer.readShorts(dst, offset, safeCount)
        }

        val srcCount = AudioResampler.sourceSamplesNeeded(
            safeCount,
            targetHz,
            PcmRingBuffer.SAMPLE_RATE_HZ
        )
        if (srcCount <= 0) return false

        val tmp = ShortArray(srcCount)
        if (!PcmRingBuffer.readShorts(tmp, 0, srcCount)) return false

        val out = AudioResampler.resampleShorts(
            tmp, 0, srcCount,
            PcmRingBuffer.SAMPLE_RATE_HZ,
            targetHz
        )

        val copyCount = minOf(out.size, safeCount)
        if (copyCount <= 0) return false
        System.arraycopy(out, 0, dst, offset, copyCount)
        return true
    }

    private fun sampleRate(record: AudioRecord): Int =
        runCatching { record.sampleRate }
            .getOrDefault(PcmRingBuffer.SAMPLE_RATE_HZ)
            .takeIf { it > 0 }
            ?: PcmRingBuffer.SAMPLE_RATE_HZ

    private fun channelCount(record: AudioRecord): Int =
        runCatching { record.channelCount }
            .getOrDefault(1)
            .takeIf { it > 0 }
            ?: 1

    private fun streamId(record: AudioRecord): Long =
        System.identityHashCode(record).toLong()

    private fun isMicSource(record: AudioRecord): Boolean {
        val source = runCatching { record.audioSource }.getOrDefault(-1)
        return source in MIC_SOURCES
    }

    private fun hookRead(
        cl: ClassLoader,
        hook: XC_MethodHook,
        vararg types: Class<*>
    ) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.media.AudioRecord",
                cl,
                "read",
                *(types.toList() + hook).toTypedArray()
            )
        }.onFailure {
            XposedBridge.log("XMicHook hook error: " + it.message)
        }
    }
}
