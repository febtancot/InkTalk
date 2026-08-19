package com.inktalk.ime.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * 麦克风采集：16 kHz / 单声道 / PCM 16bit，按 200 ms 分片回调（官方建议单包 100~200 ms）。
 */
class AudioCapturer(
    private val sampleRate: Int = 16000,
    private val chunkMs: Int = 200,
) {
    interface Listener {
        /** 一片 PCM 数据（小端 s16le）。 */
        fun onChunk(pcm: ByteArray)
        /** 当前音量 0~100，用于波形展示。 */
        fun onLevel(level: Int)
        fun onError(message: String)
    }

    private var recorder: AudioRecord? = null
    private var running = false
    private var worker: Thread? = null

    val chunkBytes: Int get() = sampleRate * 2 * chunkMs / 1000

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, chunkBytes * 4),
            )
        } catch (t: Throwable) {
            listener.onError("无法初始化录音：" + t.message)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("录音初始化失败（state=" + rec.state + "）")
            rec.release()
            return
        }
        recorder = rec
        running = true
        worker = thread(name = "inktalk-audio", isDaemon = true) {
            val buf = ByteArray(chunkBytes)
            try {
                rec.startRecording()
            } catch (t: Throwable) {
                listener.onError("无法开始录音：" + t.message)
                running = false
                return@thread
            }
            while (running) {
                var off = 0
                while (off < buf.size && running) {
                    val n = rec.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off > 0) {
                    listener.onChunk(if (off == buf.size) buf.copyOf() else buf.copyOf(off))
                    listener.onLevel(computeLevel(buf, off))
                }
            }
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
        recorder = null
    }

    private fun computeLevel(buf: ByteArray, len: Int): Int {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            sum += s * s
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (len / 2.0))
        return (rms / 32768.0 * 300).toInt().coerceIn(0, 100)
    }
}
