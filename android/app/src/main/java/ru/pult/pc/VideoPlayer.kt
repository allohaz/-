package ru.pult.pc

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Раскодирует поток H.264 на видеочипе телефона (MediaCodec) и выводит прямо на Surface.
 * Кадры приходят целиком (формат Annex B, ключевой кадр несёт SPS и PPS). Раскодирование идёт в своём потоке.
 * Если кадры копятся, очередь сбрасывается и просится новый ключевой кадр.
 */
class VideoPlayer(
    private val onFailure: (String) -> Unit,
    private val onNeedKey: () -> Unit
) {
    private class Au(val data: ByteArray, val key: Boolean, val w: Int, val h: Int)

    private val queue = LinkedBlockingQueue<Au>()
    @Volatile private var surface: Surface? = null
    @Volatile private var running = true
    @Volatile private var waitKey = true
    @Volatile private var resetRequested = false
    private var codec: MediaCodec? = null
    private var curW = 0
    private var curH = 0
    private var ptsUs = 0L
    private val thread = Thread { loop() }

    init {
        thread.name = "pult-video"
        thread.start()
    }

    /** Новая поверхность для вывода (или null, если она исчезла): декодер пересоздаётся. */
    fun setSurface(s: Surface?) {
        surface = s
        queue.clear()
        waitKey = true
        resetRequested = true
        if (s != null) onNeedKey()
    }

    /** Вызывается из потока чтения сети. */
    fun submit(data: ByteArray, key: Boolean, w: Int, h: Int) {
        if (!running) return
        if (waitKey && !key) return              // пока нет ключевого кадра, остальные бесполезны
        if (queue.size > 24) {                   // декодер не успевает: начинаем с чистого листа
            queue.clear()
            if (!key) {
                waitKey = true
                onNeedKey()
                return
            }
        }
        if (key) waitKey = false
        queue.offer(Au(data, key, w, h))
    }

    fun stop() {
        running = false
        queue.clear()
        thread.interrupt()
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val au = queue.poll(30, TimeUnit.MILLISECONDS)
                if (resetRequested) {
                    resetRequested = false
                    release()
                    if (au != null && !au.key) continue
                }
                if (au != null) {
                    val s = surface
                    if (s == null || !s.isValid) {
                        if (!waitKey) {
                            waitKey = true
                            onNeedKey()
                        }
                        continue
                    }
                    if (codec == null || au.w != curW || au.h != curH) {
                        if (!au.key) continue
                        release()
                        start(s, au)
                    }
                    feed(au)
                }
                drain(info)
            }
        } catch (e: InterruptedException) {
            // остановка
        } catch (e: Exception) {
            if (running) onFailure(e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            release()
        }
    }

    private fun start(s: Surface, au: Au) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, au.w, au.h)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitNals(au.data)) {
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = byteArrayOf(0, 0, 0, 1) + nal
                8 -> pps = byteArrayOf(0, 0, 0, 1) + nal
            }
        }
        if (sps != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        if (pps != null) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (Build.VERSION.SDK_INT >= 23) fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, s, null, 0)
        c.start()
        codec = c
        curW = au.w
        curH = au.h
        ptsUs = 0L
    }

    private fun feed(au: Au) {
        val c = codec ?: return
        var tries = 0
        while (running) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                if (au.data.size > buf.capacity()) {
                    waitKey = true
                    onNeedKey()
                    return
                }
                buf.put(au.data)
                ptsUs += 16_667
                c.queueInputBuffer(idx, 0, au.data.size, ptsUs, if (au.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                return
            }
            drain(MediaCodec.BufferInfo())
            tries++
            if (tries > 20) {                    // декодер завис: сбрасываем очередь и просим новый ключевой кадр
                queue.clear()
                waitKey = true
                onNeedKey()
                return
            }
        }
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        val c = codec ?: return
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            if (out >= 0) {
                c.releaseOutputBuffer(out, true)       // сразу на экран
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return
            }
        }
    }

    private fun release() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: Exception) {
        }
        try {
            c.release()
        } catch (e: Exception) {
        }
    }

    /** Режет поток Annex B на NAL-блоки (без стартовых кодов). */
    private fun splitNals(d: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<ByteArray>()
        for ((k, s) in starts.withIndex()) {
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else d.size
            while (e > s && d[e - 1] == 0.toByte()) e--
            if (e > s) out.add(d.copyOfRange(s, e))
        }
        return out
    }
}
