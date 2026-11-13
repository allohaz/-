package ru.pult.pc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val PROTOCOL = 2

/**
 * Связь по Wi-Fi: короткие JSON-сообщения, по одному в строке (см. README).
 * Кадры экрана приходят бинарно: строка-заголовок и следом JPEG (протокол версии 2).
 * Соединение живёт, пока открыт экран приложения.
 */
class WifiRemote(
    private val host: String,
    private val port: Int,
    private val code: String,
    private val cb: Callback
) : Remote {

    interface Callback {
        fun onResult(ok: Boolean, message: String)
        fun onClosed()
    }

    // то, что присылает компьютер (всё вызывается в главном потоке)
    var onFrame: ((Bitmap, Float, Float) -> Unit)? = null
    var onCursor: ((Float, Float) -> Unit)? = null     // курсор сдвинулся, а картинка та же
    // видео H.264: (кадр, ключевой ли, ширина, высота). Вызывается в потоке чтения сети, не в главном
    var onVideo: ((ByteArray, Boolean, Int, Int) -> Unit)? = null
    var onVideoMode: ((String, String) -> Unit)? = null    // выбранный способ: "h264" или "jpeg", название кодировщика
    var onSharpGone: (() -> Unit)? = null                   // видео пошло дальше: чёткий JPEG поверх него пора убрать
    @Volatile private var lastVidOrder = 0L                  // порядок прихода: JPEG, пришедший раньше последнего видеокадра, устарел
    private var arrival = 0L
    @Volatile private var sharpUp = false
    var h264Enabled = true
    @Volatile var lastPing = -1                              // задержка доставки кадров, мс
    var onClip: ((String) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    @Volatile var lastRung = -1          // ступень качества, которую сейчас выбрал компьютер (0..4)
    var serverName: String = ""
        private set
    var buttons: List<Pair<Int, String>> = emptyList()
        private set

    override val isWifi: Boolean get() = true

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()

    // кадры: сеть читает один поток, JPEG раскодирует другой; если не успеваем, берём самый свежий
    private class Frame(val bytes: ByteArray, val cx: Float, val cy: Float, val order: Long)
    private val decoder = Executors.newSingleThreadExecutor()
    private val latest = AtomicReference<Frame?>(null)
    private val decoding = AtomicBoolean(false)
    private val pool = arrayOfNulls<Bitmap>(3)     // три картинки по кругу, чтобы не плодить мусор
    private var poolIdx = 0

    /** Читает строки и точное число байт из одного потока. */
    private class LineIn(private val ins: InputStream) {
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        private var lim = 0

        private fun fill(): Boolean {
            val n = ins.read(buf, 0, buf.size)
            if (n <= 0) return false
            pos = 0
            lim = n
            return true
        }

        fun readLine(): String? {
            val sb = ByteArrayOutputStream()
            while (true) {
                if (pos >= lim && !fill()) {
                    return if (sb.size() == 0) null else String(sb.toByteArray(), Charsets.UTF_8)
                }
                val b = buf[pos++]
                if (b == '\n'.code.toByte()) return String(sb.toByteArray(), Charsets.UTF_8)
                sb.write(b.toInt())
            }
        }

        fun readFully(n: Int): ByteArray? {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                if (pos >= lim && !fill()) return null
                val c = minOf(n - got, lim - pos)
                System.arraycopy(buf, pos, out, got, c)
                pos += c
                got += c
            }
            return out
        }
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var ready = false
    @Volatile private var closed = false

    fun connect() {
        Thread {
            var authed = false
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                socket = s
                val o = s.getOutputStream()
                out = o
                val input = LineIn(s.getInputStream())
                writeLine(o, JSONObject().put("v", PROTOCOL).put("t", "hello").put("code", code))
                val line = input.readLine() ?: throw IOException("closed")
                val j = JSONObject(line)
                if (j.optBoolean("ok")) {
                    authed = true
                    serverName = j.optString("name")
                    val list = ArrayList<Pair<Int, String>>()
                    val arr = j.optJSONArray("buttons")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val b = arr.optJSONObject(i) ?: continue
                            list.add(Pair(b.optInt("id"), b.optString("name")))
                        }
                    }
                    buttons = list
                    ready = true
                    s.soTimeout = 0
                    main.post { cb.onResult(true, serverName) }
                    while (true) {
                        val l = input.readLine() ?: break
                        handleServer(l, input)
                    }
                } else {
                    val text = errorText(j.optString("err"))
                    main.post { cb.onResult(false, text) }
                }
            } catch (e: Exception) {
                if (!authed && !closed) {
                    main.post {
                        cb.onResult(false, "Не получилось подключиться. Проверь адрес, и что телефон и компьютер в одной сети.")
                    }
                }
            } finally {
                ready = false
                closeQuiet()
                if (authed && !closed) main.post { cb.onClosed() }
            }
        }.start()
    }

    private fun handleServer(line: String, input: LineIn) {
        try {
            val j = JSONObject(line)
            when (j.optString("t")) {
                "frame" -> {
                    val n = j.getInt("n")
                    if (n < 0 || n > 16 * 1024 * 1024) throw IOException("bad frame size")
                    val bytes = input.readFully(n) ?: throw IOException("closed")
                    val seq = j.optInt("s", 0)
                    lastRung = j.optInt("r", -1)
                    // сразу сообщаем компьютеру, что кадр дошёл: по этому он подбирает качество под канал
                    if (seq > 0) send(msg("ack").put("s", seq))
                    arrival++
                    if (onVideo != null) sharpUp = true      // в режиме видео JPEG это чёткий кадр покоя поверх картинки
                    offerFrame(Frame(bytes, j.optDouble("cx", -1.0).toFloat(), j.optDouble("cy", -1.0).toFloat(), arrival))
                }
                "vid" -> {
                    val n = j.getInt("n")
                    if (n < 0 || n > 16 * 1024 * 1024) throw IOException("bad frame size")
                    val bytes = input.readFully(n) ?: throw IOException("closed")
                    val seq = j.optInt("s", 0)
                    lastRung = j.optInt("r", -1)
                    lastPing = j.optInt("p", -1)
                    if (seq > 0) send(msg("ack").put("s", seq))
                    val cx = j.optDouble("cx", -1.0).toFloat()
                    val cy = j.optDouble("cy", -1.0).toFloat()
                    arrival++
                    lastVidOrder = arrival
                    if (sharpUp) {
                        sharpUp = false
                        main.post { onSharpGone?.invoke() }
                    }
                    onVideo?.invoke(bytes, j.optInt("k") == 1, j.optInt("w"), j.optInt("h"))
                    main.post { onCursor?.invoke(cx, cy) }
                }
                "vmode" -> {
                    val codec = j.optString("codec")
                    val enc = j.optString("enc")
                    main.post { onVideoMode?.invoke(codec, enc) }
                }
                "cur" -> {
                    val cx = j.optDouble("cx", -1.0).toFloat()
                    val cy = j.optDouble("cy", -1.0).toFloat()
                    main.post { onCursor?.invoke(cx, cy) }
                }
                "clip" -> {
                    val s = j.optString("s")
                    main.post { onClip?.invoke(s) }
                }
                "shared" -> {
                    val s = j.optString("s")
                    main.post { onInfo?.invoke(s) }
                }
            }
        } catch (e: IOException) {
            throw e            // поток кадров сбился: соединение переоткроется
        } catch (e: Exception) {
            // непонятное сообщение просто пропускаем
        }
    }

    private fun offerFrame(f: Frame) {
        latest.set(f)
        startDecode()
    }

    private fun startDecode() {
        if (closed) return
        if (decoding.compareAndSet(false, true)) {
            try {
                decoder.execute { drain() }
            } catch (e: Exception) {
                decoding.set(false)
            }
        }
    }

    private fun drain() {
        try {
            while (!closed) {
                val f = latest.getAndSet(null) ?: break
                val bmp = decode(f.bytes) ?: continue
                // чёткий кадр, который раскодировался уже после нового видеокадра, показывать не нужно
                main.post { if (onVideo == null || f.order >= lastVidOrder) onFrame?.invoke(bmp, f.cx, f.cy) }
            }
        } finally {
            decoding.set(false)
            if (latest.get() != null) startDecode()
        }
    }

    private fun decode(b: ByteArray): Bitmap? {
        var bmp: Bitmap? = null
        val reuse = pool[poolIdx]
        if (reuse != null && reuse.isMutable) {
            try {
                val o = BitmapFactory.Options()
                o.inMutable = true
                o.inBitmap = reuse
                bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o)
            } catch (e: Exception) {
                bmp = null
            }
        }
        if (bmp == null) {
            val o = BitmapFactory.Options()
            o.inMutable = true
            bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o)
        }
        if (bmp != null) {
            pool[poolIdx] = bmp
            poolIdx = (poolIdx + 1) % pool.size
        }
        return bmp
    }

    private fun errorText(code: String): String = when (code) {
        "BAD_CODE" -> "Неверный код. Он показан в значке программы на компьютере."
        "LOCKED" -> "Слишком много попыток. Подожди минуту."
        "NOT_LOCAL" -> "Компьютер принимает подключения только из домашней сети или через Tailscale."
        "BAD_VERSION" -> "Программа на компьютере другой версии. Обнови обе."
        else -> "Компьютер отказал в подключении ($code)."
    }

    private fun writeLine(o: OutputStream, j: JSONObject) {
        o.write((j.toString() + "\n").toByteArray(Charsets.UTF_8))
        o.flush()
    }

    private fun send(j: JSONObject) {
        if (!ready || closed) return
        try {
            sender.execute {
                try {
                    val o = out
                    if (o != null) writeLine(o, j)
                } catch (e: Exception) {
                    closeQuiet()
                }
            }
        } catch (e: Exception) {
            // уже закрыто
        }
    }

    private fun msg(t: String) = JSONObject().put("v", PROTOCOL).put("t", t)

    override fun move(dx: Int, dy: Int) = send(msg("move").put("dx", dx).put("dy", dy))
    override fun mouseButton(button: Int, down: Boolean) =
        send(msg("btn").put("b", button).put("down", down))
    override fun scroll(steps: Int) = send(msg("scroll").put("n", steps))
    override fun key(spec: String) = send(msg("key").put("k", spec))
    override fun text(s: String) = send(msg("text").put("s", s))
    override fun media(name: String) = send(msg("media").put("k", name))

    override fun moveTo(nx: Float, ny: Float) =
        send(msg("abs").put("x", nx.toDouble()).put("y", ny.toDouble()))
    override fun system(action: String) = send(msg("system").put("a", action))
    override fun custom(id: Int) = send(msg("custom").put("id", id))
    override fun share(s: String) = send(msg("share").put("s", s))
    override fun clipSet(s: String) = send(msg("clip_set").put("s", s))
    override fun clipGet() = send(msg("clip_get"))
    override fun wake() = send(msg("wake"))

    /** Попросить компьютер прислать новый ключевой кадр видео (после сбоя или смены поверхности). */
    fun requestKey() = send(msg("idr"))
    override fun screen(on: Boolean, quality: Int, width: Int, game: Boolean) {
        val m = msg("screen").put("on", on).put("q", quality)
        if (width > 0) m.put("w", width)
        if (game) m.put("game", true)
        if (on && h264Enabled && onVideo != null) m.put("codec", "h264")
        send(m)
    }

    override fun multiClick(button: Int, n: Int) =
        send(msg("click").put("b", button).put("n", n))

    override fun keyHold(name: String, down: Boolean) =
        send(msg("keyd").put("k", name).put("down", down))
    override fun look(dx: Int, dy: Int) = send(msg("gmove").put("dx", dx).put("dy", dy))
    override fun gameButton(b: Int, down: Boolean) =
        send(msg("gbtn").put("b", b).put("down", down))

    override fun close() {
        closed = true
        ready = false
        onFrame = null
        onVideo = null
        onVideoMode = null
        onSharpGone = null
        onCursor = null
        onClip = null
        onInfo = null
        closeQuiet()
        try { sender.shutdown() } catch (_: Exception) {}
        try { decoder.shutdown() } catch (_: Exception) {}
    }

    private fun closeQuiet() {
        val s = socket ?: return
        socket = null
        Thread { try { s.close() } catch (_: Exception) {} }.start()
    }
}
