package com.xmicinject

import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class XMicHook : IXposedHookLoadPackage {

    private companion object {
        private const val TAG = "XMicHook"
        private const val DETO = "ae.deto.app"
        private val MIC_SOURCES = setOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != DETO) return

        val uid = lpparam.appInfo?.uid ?: Process.INVALID_UID
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return

        Log.i(TAG, "Deto native hook loaded: " + DETO)
        IpcClient.startOnce()

        val injectionLogged = AtomicBoolean(false)
        val muteLogged = AtomicBoolean(false)

        hookNative("native_read_in_byte_array", injectionLogged, muteLogged)
        hookNative("native_read_in_short_array", injectionLogged, muteLogged)
        hookNative("native_read_in_float_array", injectionLogged, muteLogged)
        hookNative("native_read_in_direct_buffer", injectionLogged, muteLogged)
    }

    private fun hookNative(
        methodName: String,
        injectionLogged: AtomicBoolean,
        muteLogged: AtomicBoolean
    ) {
        runCatching {
            val hooks = XposedBridge.hookAllMethods(
                AudioRecord::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val record = param.thisObject as? AudioRecord ?: return
                        val hz = sampleRate(record)
                        val channels = channelCount(record)

                        when (methodName) {
                            "native_read_in_byte_array" -> {
                                val resultBytes = param.result as? Int ?: return
                                if (resultBytes <= 0) return

                                val dst = param.args.getOrNull(0) as? ByteArray ?: return
                                val off = param.args.getOrNull(1) as? Int ?: return
                                if (off < 0 || off >= dst.size) return

                                val safeCount = minOf(resultBytes, dst.size - off)

                                if (isMicSource(record)) {
                                    UplinkSender.send(
                                        dst, off, safeCount, hz,
                                        channels, streamId(record)
                                    )
                                }

                                val replaced = injectPcm16Bytes(
                                    dst, off, safeCount, hz, channels
                                )

                                finishResult(
                                    replaced, methodName,
                                    injectionLogged, muteLogged
                                ) {
                                    val end = minOf(dst.size, off + safeCount)
                                    if (end > off) dst.fill(0, off, end)
                                }
                            }

                            "native_read_in_short_array" -> {
                                val resultSamples = param.result as? Int ?: return
                                if (resultSamples <= 0) return

                                val dst = param.args.getOrNull(0) as? ShortArray ?: return
                                val off = param.args.getOrNull(1) as? Int ?: return
                                if (off < 0 || off >= dst.size) return

                                val safeCount = minOf(resultSamples, dst.size - off)

                                if (isMicSource(record)) {
                                    val end = off + safeCount
                                    val bytes = AudioResampler.shortsToBytes(
                                        dst.copyOfRange(off, end)
                                    )
                                    UplinkSender.send(
                                        bytes, 0, bytes.size, hz,
                                        channels, streamId(record)
                                    )
                                }

                                val replaced = injectPcm16Shorts(
                                    dst, off, safeCount, hz, channels
                                )

                                finishResult(
                                    replaced, methodName,
                                    injectionLogged, muteLogged
                                ) {
                                    val end = minOf(dst.size, off + safeCount)
                                    if (end > off) dst.fill(0, off, end)
                                }
                            }

                            "native_read_in_float_array" -> {
                                val resultSamples = param.result as? Int ?: return
                                if (resultSamples <= 0) return

                                val dst = param.args.getOrNull(0) as? FloatArray ?: return
                                val off = param.args.getOrNull(1) as? Int ?: return
                                if (off < 0 || off >= dst.size) return

                                val safeCount = minOf(resultSamples, dst.size - off)

                                val replaced = injectFloatSamples(
                                    dst, off, safeCount, hz, channels
                                )

                                finishResult(
                                    replaced, methodName,
                                    injectionLogged, muteLogged
                                ) {
                                    val end = minOf(dst.size, off + safeCount)
                                    if (end > off) dst.fill(0f, off, end)
                                }
                            }

                            "native_read_in_direct_buffer" -> {
                                val resultBytes = param.result as? Int ?: return
                                if (resultBytes <= 0) return

                                val buffer =
                                    param.args.getOrNull(0) as? ByteBuffer ?: return

                                val start = buffer.position().coerceAtLeast(0)
                                val safeCount = minOf(
                                    resultBytes,
                                    buffer.capacity() - start
                                )
                                if (safeCount <= 0) return

                                val original = ByteArray(safeCount)
                                runCatching {
                                    buffer.duplicate().apply {
                                        position(start)
                                        limit(start + safeCount)
                                    }.get(original)
                                }.getOrElse { return }

                                if (isMicSource(record)) {
                                    UplinkSender.send(
                                        original, 0, original.size, hz,
                                        channels, streamId(record)
                                    )
                                }

                                val replacedBytes = ByteArray(safeCount)
                                val replaced = injectPcm16Bytes(
                                    replacedBytes, 0, safeCount, hz, channels
                                )

                                if (replaced) {
                                    runCatching {
                                        buffer.duplicate().apply {
                                            position(start)
                                            limit(start + safeCount)
                                        }.put(replacedBytes)
                                    }.onFailure {
                                        XposedBridge.log(
                                            "XMicHook buffer write failed: " + it.message
                                        )
                                    }
                                }

                                finishResult(
                                    replaced, methodName,
                                    injectionLogged, muteLogged
                                ) {
                                    runCatching {
                                        val view = buffer.duplicate().apply {
                                            position(start)
                                            limit(start + safeCount)
                                        }
                                        while (view.hasRemaining()) view.put(0)
                                    }
                                }
                            }
                        }
                    }
                }
            )

            Log.i(
                TAG,
                "Native hook registered: " + methodName +
                    " overloads=" + hooks.size
            )
        }.onFailure {
            XposedBridge.log(
                "XMicHook failed native hook " + methodName +
                    ": " + it.message
            )
        }
    }

    private fun finishResult(
        replaced: Boolean,
        methodName: String,
        injectionLogged: AtomicBoolean,
        muteLogged: AtomicBoolean,
        muteAction: () -> Unit
    ) {
        if (replaced) {
            if (injectionLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Injection active: native=" + methodName +
                        " source=16k-mono target=" +
                        PcmRingBuffer.SAMPLE_RATE_HZ + "k source with channel-aware output"
                )
            }
        } else if (IpcClient.muteRealMic) {
            muteAction()
            if (muteLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Real mic muted: native=" + methodName +
                        " (V6 connected, ring underflow)"
                )
            }
        }
    }

    private fun injectPcm16Bytes(
        dst: ByteArray,
        offset: Int,
        countBytes: Int,
        targetHz: Int,
        channels: Int
    ): Boolean {
        if (offset < 0 || countBytes <= 0 || offset >= dst.size) return false

        val ch = channels.coerceAtLeast(1)
        val frameBytes = ch * 2
        val frames = countBytes / frameBytes
        if (frames <= 0) return false

        val sourceSamples = sourceSamplesForFrames(
            frames, targetHz, PcmRingBuffer.SAMPLE_RATE_HZ
        )

        val src = ByteArray(sourceSamples * 2)
        if (!PcmRingBuffer.readBytes(src, 0, src.size)) return false

        val sourceShorts = AudioResampler.bytesToShorts(
            src, 0, sourceSamples
        )

        val monoOut = if (targetHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            if (sourceShorts.size < frames) return false
            sourceShorts.copyOfRange(0, frames)
        } else {
            val resampled = AudioResampler.resampleShorts(
                sourceShorts,
                0,
                sourceShorts.size,
                PcmRingBuffer.SAMPLE_RATE_HZ,
                targetHz
            )
            if (resampled.size < frames) return false
            resampled.copyOf(frames)
        }

        var out = offset
        for (frame in 0 until frames) {
            val sample = monoOut[frame].toInt()
            repeat(ch) {
                dst[out] = (sample and 0xFF).toByte()
                dst[out + 1] = ((sample ushr 8) and 0xFF).toByte()
                out += 2
            }
        }

        val written = frames * frameBytes
        if (written < countBytes) {
            dst.fill(0, offset + written, offset + countBytes)
        }
        return true
    }

    private fun injectPcm16Shorts(
        dst: ShortArray,
        offset: Int,
        countSamples: Int,
        targetHz: Int,
        channels: Int
    ): Boolean {
        if (offset < 0 || countSamples <= 0 || offset >= dst.size) return false

        val ch = channels.coerceAtLeast(1)
        val frames = countSamples / ch
        if (frames <= 0) return false

        val sourceSamples = sourceSamplesForFrames(
            frames, targetHz, PcmRingBuffer.SAMPLE_RATE_HZ
        )
        val src = ShortArray(sourceSamples)

        if (!PcmRingBuffer.readShorts(src, 0, sourceSamples)) return false

        val monoOut = if (targetHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            if (src.size < frames) return false
            src.copyOfRange(0, frames)
        } else {
            val resampled = AudioResampler.resampleShorts(
                src, 0, src.size,
                PcmRingBuffer.SAMPLE_RATE_HZ, targetHz
            )
            if (resampled.size < frames) return false
            resampled.copyOf(frames)
        }

        var out = offset
        for (frame in 0 until frames) {
            val sample = monoOut[frame]
            repeat(ch) {
                dst[out++] = sample
            }
        }
        return true
    }

    private fun injectFloatSamples(
        dst: FloatArray,
        offset: Int,
        countSamples: Int,
        targetHz: Int,
        channels: Int
    ): Boolean {
        if (offset < 0 || countSamples <= 0 || offset >= dst.size) return false

        val ch = channels.coerceAtLeast(1)
        val frames = countSamples / ch
        if (frames <= 0) return false

        val sourceSamples = sourceSamplesForFrames(
            frames, targetHz, PcmRingBuffer.SAMPLE_RATE_HZ
        )
        val src = ShortArray(sourceSamples)

        if (!PcmRingBuffer.readShorts(src, 0, sourceSamples)) return false

        val monoOut = if (targetHz == PcmRingBuffer.SAMPLE_RATE_HZ) {
            if (src.size < frames) return false
            src.copyOfRange(0, frames)
        } else {
            val resampled = AudioResampler.resampleShorts(
                src, 0, src.size,
                PcmRingBuffer.SAMPLE_RATE_HZ, targetHz
            )
            if (resampled.size < frames) return false
            resampled.copyOf(frames)
        }

        var out = offset
        for (frame in 0 until frames) {
            val value = monoOut[frame] / 32768.0f
            repeat(ch) {
                dst[out++] = value
            }
        }
        return true
    }

    private fun sourceSamplesForFrames(
        targetFrames: Int,
        targetHz: Int,
        sourceHz: Int
    ): Int {
        if (targetFrames <= 0 || targetHz <= 0 || sourceHz <= 0) return 0
        return ceil(
            targetFrames.toDouble() *
                sourceHz.toDouble() /
                targetHz.toDouble()
        ).toInt().coerceAtLeast(1)
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
}
