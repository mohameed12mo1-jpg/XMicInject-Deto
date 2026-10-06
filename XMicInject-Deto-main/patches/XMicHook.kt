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
        private val SKIP_PACKAGES = setOf("com.xmicinject", MODULE_PACKAGE, "android")
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
        if (lpparam.packageName in SKIP_PACKAGES) return
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return

        val pkg = lpparam.packageName
        if (pkg != DETO) return

        Log.i(TAG, "Deto hook loaded: $pkg")
        IpcClient.startOnce()

        val injectionLogged = AtomicBoolean(false)
        val muteLogged = AtomicBoolean(false)

        // Deto uses the native AudioRecord read path on this device.
        // Hook the hidden native methods directly so Java wrappers cannot bypass us.
        hookNativeReads(pkg, injectionLogged, muteLogged)
        hookStartRecording(pkg)
    }

    private fun hookStartRecording(pkg: String) {
        runCatching {
            XposedBridge.hookAllMethods(AudioRecord::class.java, "startRecording", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val record = param.thisObject as? AudioRecord ?: return
                    // Drop audio queued before Deto actually starts recording so the
                    // injected stream starts from "now" instead of stale buffered PCM.
                    LowLatencyPcmRingBuffer.clear()
                    Log.i(TAG, "AudioRecord start: cleared low-latency buffer; pkg=$pkg source=${runCatching { record.audioSource }.getOrDefault(-1)} sampleRate=${sampleRate(record)}Hz channels=${channelCount(record)}")
                }
            })
        }.onFailure {
            XposedBridge.log("XMicHook failed to hook startRecording: ${it.message}")
        }
    }

    private fun hookNativeReads(pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val seen = mutableMapOf<String, AtomicBoolean>()
        val names = listOf(
            "native_read_in_byte_array",
            "native_read_in_short_array",
            "native_read_in_float_array",
            "native_read_in_direct_buffer"
        )

        for (name in names) {
            seen[name] = AtomicBoolean(false)
            runCatching {
                val hooks = XposedBridge.hookAllMethods(AudioRecord::class.java, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val count = param.result as? Int ?: return
                        if (count <= 0) return
                        val record = param.thisObject as? AudioRecord ?: return
                        val hz = sampleRate(record)

                        if (seen[name]?.compareAndSet(false, true) == true) {
                            Log.i(TAG, "Native read seen: pkg=$pkg method=$name count=$count sampleRate=${hz}Hz")
                        }

                        when (name) {
                            "native_read_in_byte_array" -> {
                                val buf = param.args.getOrNull(0) as? ByteArray ?: return
                                val off = param.args.getOrNull(1) as? Int ?: 0
                                if (isMicSource(record)) {
                                    UplinkSender.send(buf, off, count, hz, channelCount(record), streamId(record))
                                }
                                val replaced = injectBytes(buf, off, count, hz)
                                onResult(replaced, pkg, "native byte[]", injectionLogged, muteLogged) {
                                    buf.fill(0, off.coerceAtLeast(0), (off + count).coerceAtMost(buf.size))
                                }
                            }

                            "native_read_in_short_array" -> {
                                val buf = param.args.getOrNull(0) as? ShortArray ?: return
                                val off = param.args.getOrNull(1) as? Int ?: 0
                                if (isMicSource(record)) {
                                    val end = (off + count).coerceAtMost(buf.size)
                                    if (off >= 0 && end > off) {
                                        val bytes = AudioResampler.shortsToBytes(buf.copyOfRange(off, end))
                                        UplinkSender.send(bytes, 0, bytes.size, hz, channelCount(record), streamId(record))
                                    }
                                }
                                val replaced = injectShorts(buf, off, count, hz)
                                onResult(replaced, pkg, "native short[]", injectionLogged, muteLogged) {
                                    buf.fill(0, off.coerceAtLeast(0), (off + count).coerceAtMost(buf.size))
                                }
                            }

                            "native_read_in_float_array" -> {
                                val buf = param.args.getOrNull(0) as? FloatArray ?: return
                                val off = param.args.getOrNull(1) as? Int ?: 0
                                val replaced = injectFloats(buf, off, count, hz)
                                onResult(replaced, pkg, "native float[]", injectionLogged, muteLogged) {
                                    buf.fill(0f, off.coerceAtLeast(0), (off + count).coerceAtMost(buf.size))
                                }
                            }

                            "native_read_in_direct_buffer" -> {
                                val buffer = param.args.getOrNull(0) as? ByteBuffer ?: return
                                val start = buffer.position().coerceAtLeast(0)
                                val end = (start + count).coerceAtMost(buffer.capacity())
                                if (end <= start) return
                                val bytes = ByteArray(end - start)
                                runCatching {
                                    buffer.duplicate().apply { position(start); limit(end) }.get(bytes)
                                }
                                if (isMicSource(record)) {
                                    UplinkSender.send(bytes, 0, bytes.size, hz, channelCount(record), streamId(record))
                                }
                                val replaced = injectBytes(bytes, 0, bytes.size, hz)
                                if (replaced) {
                                    runCatching {
                                        buffer.duplicate().apply { position(start); limit(end) }.put(bytes)
                                    }
                                }
                                onResult(replaced, pkg, "native ByteBuffer", injectionLogged, muteLogged) {
                                    runCatching {
                                        val view = buffer.duplicate().apply { position(start); limit(end) }
                                        while (view.hasRemaining()) view.put(0)
                                    }
                                }
                            }
                        }
                    }
                })
                Log.i(TAG, "Native hook registered: pkg=$pkg method=$name overloads=${hooks.size}")
            }.onFailure {
                XposedBridge.log("XMicHook failed native hook $name: ${it.message}")
            }
        }
    }

    private inline fun onResult(
        replaced: Boolean,
        pkg: String,
        path: String,
        injectionLogged: AtomicBoolean,
        muteLogged: AtomicBoolean,
        muteAction: () -> Unit
    ) {
        if (replaced) {
            if (injectionLogged.compareAndSet(false, true)) {
                Log.i(TAG, "Injection active: pkg=$pkg path=$path")
            }
        } else if (IpcClient.muteRealMic) {
            muteAction()
            if (muteLogged.compareAndSet(false, true)) {
                Log.i(TAG, "Real mic muted: pkg=$pkg path=$path (connected but buffer empty)")
            }
        }
    }

    // Existing public Java hooks for other scoped apps.
    private fun hookByteArray(cl: ClassLoader, pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buf = param.args[0] as ByteArray
                val off = param.args[1] as Int
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)
                if (isMicSource(record)) UplinkSender.send(buf, off, count, hz, channelCount(record), streamId(record))
                val replaced = injectBytes(buf, off, count, hz)
                onResult(replaced, pkg, "byte[]", injectionLogged, muteLogged) {
                    buf.fill(0, off, (off + count).coerceAtMost(buf.size))
                }
            }
        }
        hookRead(cl, hook, ByteArray::class.java, Int::class.java, Int::class.java)
        hookRead(cl, hook, ByteArray::class.java, Int::class.java, Int::class.java, Int::class.java)
    }

    private fun hookShortArray(cl: ClassLoader, pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buf = param.args[0] as ShortArray
                val off = param.args[1] as Int
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)
                if (isMicSource(record)) {
                    val bytes = AudioResampler.shortsToBytes(buf.copyOfRange(off, (off + count).coerceAtMost(buf.size)))
                    UplinkSender.send(bytes, 0, bytes.size, hz, channelCount(record), streamId(record))
                }
                val replaced = injectShorts(buf, off, count, hz)
                onResult(replaced, pkg, "short[]", injectionLogged, muteLogged) {
                    buf.fill(0, off, (off + count).coerceAtMost(buf.size))
                }
            }
        }
        hookRead(cl, hook, ShortArray::class.java, Int::class.java, Int::class.java)
        hookRead(cl, hook, ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java)
    }

    private fun hookByteBuffer(cl: ClassLoader, pkg: String, injectionLogged: AtomicBoolean, muteLogged: AtomicBoolean) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                val buffer = param.args[0] as? ByteBuffer ?: return
                val record = param.thisObject as? AudioRecord ?: return
                val hz = sampleRate(record)
                val start = (buffer.position() - count).coerceAtLeast(0)
                val end = (start + count).coerceAtMost(buffer.capacity())
                if (end <= start) return
                val bytes = ByteArray(end - start)
                buffer.duplicate().apply { position(start); limit(end) }.get(bytes)
                if (isMicSource(record)) UplinkSender.send(bytes, 0, bytes.size, hz, channelCount(record), streamId(record))
                val replaced = injectBytes(bytes, 0, bytes.size, hz)
                if (replaced) buffer.duplicate().apply { position(start); limit(end) }.put(bytes)
                onResult(replaced, pkg, "ByteBuffer", injectionLogged, muteLogged) {
                    val view = buffer.duplicate().apply { position(start); limit(end) }
                    while (view.hasRemaining()) view.put(0)
                }
            }
        }
        hookRead(cl, hook, ByteBuffer::class.java, Int::class.java)
        hookRead(cl, hook, ByteBuffer::class.java, Int::class.java, Int::class.java)
    }

    private fun injectBytes(dst: ByteArray, offset: Int, count: Int, sampleRateHz: Int): Boolean {
        if (offset < 0 || count <= 0 || offset >= dst.size) return false
        val safeCount = minOf(count, dst.size - offset)
        if (sampleRateHz == LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ) {
            return LowLatencyPcmRingBuffer.readBytes(dst, offset, safeCount)
        }
        val srcBytes = AudioResampler.sourceBytesNeeded(safeCount, sampleRateHz, LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ)
        val tmp = ByteArray(srcBytes)
        if (!LowLatencyPcmRingBuffer.readBytes(tmp, 0, srcBytes)) return false
        val out = AudioResampler.resampleBytes(tmp, 0, srcBytes, LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ, sampleRateHz)
        System.arraycopy(out, 0, dst, offset, minOf(out.size, safeCount))
        return true
    }

    private fun injectShorts(dst: ShortArray, offset: Int, count: Int, sampleRateHz: Int): Boolean {
        if (offset < 0 || count <= 0 || offset >= dst.size) return false
        val safeCount = minOf(count, dst.size - offset)
        if (sampleRateHz == LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ) {
            return LowLatencyPcmRingBuffer.readShorts(dst, offset, safeCount)
        }
        val srcCount = AudioResampler.sourceSamplesNeeded(safeCount, sampleRateHz, LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ)
        val tmp = ShortArray(srcCount)
        if (!LowLatencyPcmRingBuffer.readShorts(tmp, 0, srcCount)) return false
        val out = AudioResampler.resampleShorts(tmp, 0, srcCount, LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ, sampleRateHz)
        System.arraycopy(out, 0, dst, offset, minOf(out.size, safeCount))
        return true
    }

    private fun injectFloats(dst: FloatArray, offset: Int, count: Int, sampleRateHz: Int): Boolean {
        if (offset < 0 || count <= 0 || offset >= dst.size) return false
        val safeCount = minOf(count, dst.size - offset)
        val tmp = ShortArray(safeCount)
        if (!injectShorts(tmp, 0, safeCount, sampleRateHz)) return false
        for (i in 0 until safeCount) dst[offset + i] = tmp[i] / 32768.0f
        return true
    }

    private fun sampleRate(record: AudioRecord): Int {
        val raw = runCatching { record.sampleRate }.getOrDefault(-1)
        return raw.takeIf { it > 0 } ?: LowLatencyPcmRingBuffer.SAMPLE_RATE_HZ
    }

    private fun channelCount(record: AudioRecord): Int =
        runCatching { record.channelCount }.getOrDefault(1).takeIf { it > 0 } ?: 1

    private fun streamId(record: AudioRecord): Long = System.identityHashCode(record).toLong()

    private fun isMicSource(record: AudioRecord): Boolean {
        val source = runCatching { record.audioSource }.getOrDefault(-1)
        return source in MIC_SOURCES
    }

    private fun hookRead(cl: ClassLoader, hook: XC_MethodHook, vararg types: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.media.AudioRecord", cl, "read",
                *(types.toList() + hook).toTypedArray()
            )
        }.onFailure {
            XposedBridge.log("XMicHook failed to hook read(${types.joinToString { it.simpleName }}): ${it.message}")
        }
    }
}
