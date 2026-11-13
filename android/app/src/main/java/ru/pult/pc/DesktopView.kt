package ru.pult.pc

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Экран компьютера на весь экран телефона (горизонтально, только Wi-Fi).
 *  - картинка занимает всё место, на ней сверху панель инструментов (сворачивается кнопкой ▲),
 *    снизу полупрозрачная клавиатура (кнопка ⌨), сквозь неё виден монитор
 *  - пока картинка движется, идёт до 60 кадров в секунду, когда замерла, приходит один чёткий кадр
 *  - инструменты: двойной / тройной / правый клик, выделение и перенос, копировать / вставить / вырезать /
 *    отменить / выделить всё, зум (картинка едет за курсором), режим «касание» или «тачпад»
 *  - боковая зона-тачпад: полоса сбоку (справа или слева), где палец двигает курсор, кликает и прокручивает,
 *    не закрывая картинку. Работают все те же жесты и инструменты.
 */
class DesktopView(
    context: Context,
    private val remote: WifiRemote,
    private val prefs: SharedPreferences,
    private val onExit: () -> Unit
) : FrameLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val dm = resources.displayMetrics
    private val widthPx = maxOf(dm.widthPixels, dm.heightPixels)
    private val names = arrayOf("Лёгкое", "Плавное", "Чёткое", "Макс. fps", "Оригинал")
    private val zooms = floatArrayOf(1.5f, 2f, 3f, 4f)
    private var level = prefs.getInt("dq2", 5).coerceIn(1, 5)
    private var frames = 0
    private var docked = false
    private var zoneMode = prefs.getInt("zone", 1)       // 0 выкл, 1 справа, 2 слева
    private var activeTool = TouchpadView.Tool.NONE
    private var syncing = false

    private var videoOn = prefs.getBoolean("dvideo", true)
    private var encName = ""
    private val vl = VideoLayer(context, remote) { msg -> onVideoFailed(msg) }
    private val pad = TouchpadView(context, remote)
    private val zone = TouchpadView(context, remote)
    private val kb = KeyboardView(context, remote, { setKeyboard(false) }, { toggleDock() })
    private val bar = HorizontalScrollView(context)
    private val fab: Button
    private val fps: TextView = context.label("", 12f, Colors.MUTED)
    private val toolBtns = HashMap<TouchpadView.Tool, Button>()
    private val kbHeight: Int

    private fun dp(v: Int): Int = context.dp(v)

    /** Маленькая кнопка для панели. */
    private fun tb(label: String, textColor: Int = Colors.TEXT, onClick: () -> Unit): Button =
        context.button(label, 13f, Colors.SURFACE, textColor, onClick).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
        }

    private val fpsTick = object : Runnable {
        override fun run() {
            val vf = vl.frames.getAndSet(0)
            val total = frames + vf
            val ping = remote.lastPing
            fps.text = if (total == 0) "покой" else "$total fps" +
                (if (encName.isNotEmpty()) " · $encName" else "") + (if (ping > 0) " · ${ping} мс" else "")
            frames = 0
            main.postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(Colors.BG)
        pad.fullscreenStyle = true
        pad.direct = prefs.getBoolean("direct2", false)
        pad.onToolChange = { t -> setTool(t) }
        // видео лежит под картинкой-прозрачкой: курсор и жесты остаются в pad
        addView(vl, FrameLayout.LayoutParams(-1, -1))
        vl.onVideoSize = { w, h ->
            pad.videoW = w
            pad.videoH = h
            pad.invalidate()
        }
        pad.onDst = { r -> vl.place(r) }
        remote.onVideoMode = { codec, enc -> onVideoMode(codec, enc) }
        // чёткий JPEG покоя лежит поверх видео; когда картинка снова движется, он убирается
        remote.onSharpGone = { if (vl.videoW > 0) pad.frame = null }
        if (videoOn) vl.attach()
        addView(pad, FrameLayout.LayoutParams(-1, -1))

        // боковая зона-тачпад (всегда относительное управление)
        zone.direct = false
        zone.caption = "Тачпад"
        zone.onToolChange = { t -> setTool(t) }
        addView(zone, FrameLayout.LayoutParams(dp(220), -1, Gravity.END))

        // клавиатура снизу
        kbHeight = minOf(dp(196), (minOf(dm.widthPixels, dm.heightPixels) * 0.52f).toInt())
        kb.visibility = View.GONE
        addView(kb, FrameLayout.LayoutParams(-1, kbHeight, Gravity.BOTTOM).apply {
            setMargins(dp(4), 0, dp(4), dp(4))
        })

        // панель инструментов сверху
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        fun add(b: View) {
            row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), 0, dp(2), 0) })
        }

        add(tb("▲") { setBar(false) })

        val modeBtn = tb(modeText()) {}
        modeBtn.setOnClickListener {
            pad.direct = !pad.direct
            prefs.edit().putBoolean("direct2", pad.direct).apply()
            modeBtn.text = modeText()
        }
        add(modeBtn)

        fun tool(label: String, t: TouchpadView.Tool) {
            val b = tb(label) { setTool(if (activeTool == t) TouchpadView.Tool.NONE else t) }
            toolBtns[t] = b
            add(b)
        }
        tool("Двойной", TouchpadView.Tool.DOUBLE)
        tool("Тройной", TouchpadView.Tool.TRIPLE)
        tool("Правый", TouchpadView.Tool.RIGHT)
        tool("Выделить / тянуть", TouchpadView.Tool.DRAG)

        add(tb("Все") { remote.key("ctrl+a") })
        add(tb("Копир.") { remote.key("ctrl+c") })
        add(tb("Вырез.") { remote.key("ctrl+x") })
        add(tb("Встав.") { remote.key("ctrl+v") })
        add(tb("Отмена") { remote.key("ctrl+z") })
        add(tb("Del") { remote.key("delete") })
        add(tb("Esc") { remote.key("esc") })
        add(tb("Разбудить экран") { remote.wake() })
        add(tb("⌨") { setKeyboard(kb.visibility != View.VISIBLE) })

        val zoneBtn = tb(zoneText()) {}
        zoneBtn.setOnClickListener {
            zoneMode = (zoneMode + 1) % 3
            prefs.edit().putInt("zone", zoneMode).apply()
            zoneBtn.text = zoneText()
            applyLayout()
        }
        add(zoneBtn)

        val zoomBtn = tb("Зум ×1") {}
        // кнопка: следующая ступень зума, после последней обратно к ×1; щипок двумя пальцами работает и без неё
        zoomBtn.setOnClickListener {
            pad.zoom = zooms.firstOrNull { it > pad.zoom + 0.05f } ?: 1f
        }
        pad.onZoomChange = { z -> zoomBtn.text = "Зум ×" + String.format(java.util.Locale.US, "%.1f", z).replace(".0", "") }
        add(zoomBtn)

        val vBtn = tb("") {}
        vBtn.text = if (videoOn) "Видео: вкл" else "Видео: выкл"
        vBtn.setOnClickListener {
            videoOn = !videoOn
            prefs.edit().putBoolean("dvideo", videoOn).apply()
            vBtn.text = if (videoOn) "Видео: вкл" else "Видео: выкл"
            applyVideo()
        }
        add(vBtn)

        val qBtn = tb(names[level - 1]) {}
        qBtn.setOnClickListener {
            level = if (level >= 5) 1 else level + 1
            prefs.edit().putInt("dq2", level).apply()
            qBtn.text = names[level - 1]
            remote.screen(true, level, widthPx)
        }
        add(qBtn)

        fps.minWidth = dp(110)
        fps.gravity = Gravity.CENTER
        add(fps)

        add(tb("✕", Colors.DANGER) { onExit() })

        bar.isHorizontalScrollBarEnabled = false
        bar.isClickable = true
        bar.background = context.rounded(0xE60E0F13.toInt(), 18)
        bar.addView(row)
        addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(6), dp(8), 0)
        })

        // кнопка, когда панель свёрнута
        fab = tb("☰") { setBar(true) }
        fab.background = context.rounded(0xCC1B1D25.toInt(), 18)
        fab.visibility = View.GONE
        addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(6), 0, 0)
        })

        refreshTools()
        applyLayout()
        start()
    }

    /** Включить или выключить видео и перезапустить передачу экрана в нужном виде. */
    private fun applyVideo() {
        if (videoOn) {
            remote.h264Enabled = true
            vl.attach()
        } else {
            vl.detach()
            pad.videoW = 0
            pad.videoH = 0
            vl.visibility = View.INVISIBLE
        }
        vl.visibility = if (videoOn) View.VISIBLE else View.INVISIBLE
        remote.screen(true, level, widthPx)
        pad.invalidate()
    }

    private fun onVideoMode(codec: String, enc: String) {
        if (codec == "h264") {
            encName = enc.removePrefix("h264_").uppercase()
            pad.frame = null                 // старый JPEG-кадр не должен закрывать видео
            vl.visibility = View.VISIBLE
        } else {
            encName = "JPEG"
            pad.videoW = 0
            pad.videoH = 0
            vl.visibility = View.INVISIBLE
            pad.invalidate()
        }
    }

    private fun onVideoFailed(msg: String) {
        // декодер на телефоне не запустился: возвращаемся к JPEG, чтобы картинка была
        remote.h264Enabled = false
        vl.detach()
        pad.videoW = 0
        pad.videoH = 0
        vl.visibility = View.INVISIBLE
        remote.screen(true, level, widthPx)
        android.widget.Toast.makeText(context, "Видео не запустилось, включён JPEG: $msg", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun zoneText(): String = when (zoneMode) {
        1 -> "Тачпад: справа"
        2 -> "Тачпад: слева"
        else -> "Тачпад: выкл"
    }

    /** Расставляет картинку, боковую зону и клавиатуру. Зона занимает около четверти ширины, картинка остаётся в остатке. */
    private fun applyLayout() {
        val zw = if (zoneMode == 0) 0 else (widthPx * 0.26f).toInt().coerceIn(dp(140), dp(300))
        val pl = pad.layoutParams as FrameLayout.LayoutParams
        val zl = zone.layoutParams as FrameLayout.LayoutParams
        val kl = kb.layoutParams as FrameLayout.LayoutParams
        pl.leftMargin = if (zoneMode == 2) zw else 0
        pl.rightMargin = if (zoneMode == 1) zw else 0
        kl.leftMargin = dp(4) + pl.leftMargin
        kl.rightMargin = dp(4) + pl.rightMargin
        zl.width = zw
        zl.gravity = if (zoneMode == 2) Gravity.START else Gravity.END
        zl.setMargins(dp(4), dp(4), dp(4), dp(4))
        zone.visibility = if (zoneMode == 0) View.GONE else View.VISIBLE
        pad.layoutParams = pl
        zone.layoutParams = zl
        kb.layoutParams = kl
    }

    /** Инструмент один на оба поля: взведённый «Двойной» сработает на следующее касание где угодно. */
    private fun setTool(t: TouchpadView.Tool) {
        if (syncing) return
        syncing = true
        activeTool = t
        pad.tool = t
        zone.tool = t
        syncing = false
        refreshTools()
    }

    private fun modeText(): String = if (pad.direct) "Касание" else "Тачпад"

    private fun refreshTools() {
        for ((t, b) in toolBtns) {
            b.background = context.rippleBg(if (activeTool == t) Colors.ACCENT else Colors.SURFACE)
        }
    }

    private fun setBar(show: Boolean) {
        bar.visibility = if (show) View.VISIBLE else View.GONE
        fab.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setKeyboard(show: Boolean) {
        kb.visibility = if (show) View.VISIBLE else View.GONE
        applyInset()
    }

    /** ⇅ на клавиатуре: картинка либо под клавиатурой (видно всё, но сквозь неё), либо над ней. */
    private fun toggleDock() {
        docked = !docked
        applyInset()
    }

    private fun applyInset() {
        pad.insetBottom = if (docked && kb.visibility == View.VISIBLE) kbHeight + dp(4) else 0
    }

    private fun start() {
        remote.onFrame = { b, cx, cy ->
            pad.cursorX = cx
            pad.cursorY = cy
            pad.frame = b
            frames++
        }
        remote.onCursor = { cx, cy ->
            pad.cursorX = cx
            pad.cursorY = cy
            pad.postInvalidateOnAnimation()
        }
        remote.screen(true, level, widthPx)
        main.postDelayed(fpsTick, 1000)
    }

    /** Закрытие режима: отпустить мышь и клавиши, остановить поток картинки. */
    fun shutdown() {
        main.removeCallbacks(fpsTick)
        pad.release()
        zone.release()
        kb.shutdown()
        remote.onFrame = null
        remote.onCursor = null
        remote.onVideoMode = null
        remote.onSharpGone = null
        vl.shutdown()
        remote.screen(false, 1)
    }
}
