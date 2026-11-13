package ru.pult.pc

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Игровой экран: картинка игры (видео H.264 или JPEG) под GameView (кнопки, стик, камера), плюс панели настроек.
 *  - вверху три раскладки: Шутер, GTA пешком, GTA авто (у каждой свои кнопки и настройки)
 *  - ⚙ в игре: чувствительность камеры по осям и при прицеливании, прозрачность кнопок, вибрация, стик, авто-бег,
 *    картинка игры (вкл/выкл, видео, качество), «Настроить кнопки», «Сбросить кнопки»
 *  - режим настройки кнопок: перетащи кнопку, размер ползунком, клавиша из списка, «камера пальцем», «залипание»,
 *    удалить, добавить новую. Всё запоминается отдельно для каждой раскладки.
 */
class GameScreen(
    context: Context,
    private val remote: Remote,
    private val prefs: SharedPreferences,
    private val onExit: () -> Unit
) : FrameLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val dm = resources.displayMetrics
    private val widthPx = maxOf(dm.widthPixels, dm.heightPixels)
    private val qNames = arrayOf("Низкая (быстрее)", "Средняя", "Высокая (чётче)")
    private var gq = prefs.getInt("gq", 2).coerceIn(1, 3)
    private var frames = 0
    private var videoOn = prefs.getBoolean("gvideo", true)
    private var encName = ""

    private val vl: VideoLayer? = (remote as? WifiRemote)?.let { w -> VideoLayer(context, w) { msg -> onVideoFailed(msg) } }
    private val gv = GameView(context, remote, prefs, onExit, { openSettings() }, { b -> onSelected(b) }, { _ -> onProfile() })
    private val settings = FrameLayout(context)
    private val editBar = HorizontalScrollView(context)
    private lateinit var selInfo: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var lookBtn: Button
    private lateinit var togBtn: Button
    private lateinit var keyBtn: Button
    private lateinit var delBtn: Button

    private val keyNames: List<String> =
        listOf("ЛКМ", "ПКМ", "space", "shift", "ctrl", "alt", "tab", "esc", "enter", "up", "down", "left", "right") +
            ('a'..'z').map { it.toString() } + (0..9).map { it.toString() } + (1..12).map { "f$it" }

    private val tick = object : Runnable {
        override fun run() {
            val w = remote as? WifiRemote
            val total = frames + (vl?.frames?.getAndSet(0) ?: 0)
            val ping = w?.lastPing ?: -1
            gv.info = if (w == null || !gv.showFrame) "" else if (total == 0) "нет кадров" else
                "$total fps" + (if (encName.isNotEmpty()) " · $encName" else "") + (if (ping > 0) " · $ping мс" else "")
            frames = 0
            main.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = context.dp(v)

    private fun tb(label: String, onClick: () -> Unit): Button =
        context.button(label, 13f, Colors.SURFACE, Colors.TEXT, onClick).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
        }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun yn(v: Boolean): String = if (v) "вкл" else "выкл"

    private fun seek(min: Float, max: Float, step: Float, value: Float, onChange: (Float) -> Unit): SeekBar {
        val sb = SeekBar(context)
        sb.max = Math.round((max - min) / step)
        sb.progress = Math.round((value - min) / step).coerceIn(0, sb.max)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(min + p * step)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        return sb
    }

    init {
        setBackgroundColor(Colors.BG)
        gv.sensX = prefs.getFloat("gsx", prefs.getFloat("gs", 1f))
        gv.sensY = prefs.getFloat("gsy", prefs.getFloat("gs", 1f))
        gv.adsMul = prefs.getFloat("gads", 0.7f)
        gv.btnAlpha = prefs.getFloat("galpha", 0.75f)
        gv.haptic = prefs.getBoolean("ghaptic", true)
        gv.showPills = prefs.getBoolean("gpills", true)
        gv.showFrame = prefs.getBoolean("gshow", true)
        val v = vl
        if (v != null) {
            addView(v, FrameLayout.LayoutParams(-1, -1))
            v.onVideoSize = { _, _ -> fitVideo() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
            (remote as WifiRemote).onVideoMode = { codec, enc -> onVideoMode(codec, enc) }
        }
        addView(gv, FrameLayout.LayoutParams(-1, -1))
        settings.setBackgroundColor(0xAA000000.toInt())
        settings.isClickable = true
        settings.setOnClickListener { closeSettings() }
        settings.visibility = View.GONE
        buildEditBar()
        addView(settings, FrameLayout.LayoutParams(-1, -1))
        startStream()
        main.postDelayed(tick, 1000)
    }

    // ---------- панель настроек ----------

    private fun buildSettings() {
        settings.removeAllViews()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = context.rounded(0xF01B1D25.toInt(), 18)
            isClickable = true
        }
        val prof = GameConfig.NAMES[gv.profile] ?: ""
        col.addView(context.label("Настройки игры (раскладка «$prof»)", 18f), context.lp(-1, -2, 0f, 4))

        val lx = context.label("Камера по горизонтали: ×" + fmt(gv.sensX), 13f, Colors.MUTED)
        col.addView(lx, context.lp(-1, -2, 0f, 2))
        col.addView(seek(0.2f, 4f, 0.05f, gv.sensX) { v ->
            gv.sensX = v
            prefs.edit().putFloat("gsx", v).apply()
            lx.text = "Камера по горизонтали: ×" + fmt(v)
        }, context.lp(-1, -2, 0f, 2))

        val ly = context.label("Камера по вертикали: ×" + fmt(gv.sensY), 13f, Colors.MUTED)
        col.addView(ly, context.lp(-1, -2, 0f, 2))
        col.addView(seek(0.2f, 4f, 0.05f, gv.sensY) { v ->
            gv.sensY = v
            prefs.edit().putFloat("gsy", v).apply()
            ly.text = "Камера по вертикали: ×" + fmt(v)
        }, context.lp(-1, -2, 0f, 2))

        val lads = context.label("При прицеливании камера медленнее: ×" + fmt(gv.adsMul), 13f, Colors.MUTED)
        col.addView(lads, context.lp(-1, -2, 0f, 2))
        col.addView(seek(0.2f, 1.5f, 0.05f, gv.adsMul) { v ->
            gv.adsMul = v
            prefs.edit().putFloat("gads", v).apply()
            lads.text = "При прицеливании камера медленнее: ×" + fmt(v)
        }, context.lp(-1, -2, 0f, 2))

        val la = context.label("Прозрачность кнопок: " + Math.round(gv.btnAlpha * 100) + "%", 13f, Colors.MUTED)
        col.addView(la, context.lp(-1, -2, 0f, 2))
        col.addView(seek(0.2f, 1f, 0.05f, gv.btnAlpha) { v ->
            gv.btnAlpha = v
            prefs.edit().putFloat("galpha", v).apply()
            la.text = "Прозрачность кнопок: " + Math.round(v * 100) + "%"
            gv.invalidate()
        }, context.lp(-1, -2, 0f, 2))

        fun toggle(text: () -> String, onClick: () -> Unit) {
            val b = tb("") {}
            b.text = text()
            b.setOnClickListener {
                onClick()
                b.text = text()
            }
            col.addView(b, context.lp(-1, -2, 0f, 3))
        }
        toggle({ "Вибрация кнопок: " + yn(gv.haptic) }) {
            gv.haptic = !gv.haptic
            prefs.edit().putBoolean("ghaptic", gv.haptic).apply()
        }
        toggle({ "Переключатель раскладок сверху: " + yn(gv.showPills) }) {
            gv.showPills = !gv.showPills
            prefs.edit().putBoolean("gpills", gv.showPills).apply()
            gv.invalidate()
        }
        toggle({ "Стик движения (для этой раскладки): " + yn(gv.stickEnabled) }) {
            gv.stickEnabled = !gv.stickEnabled
            prefs.edit().putBoolean("gstick_" + gv.profile, gv.stickEnabled).apply()
        }
        toggle({ "Авто-бег у края стика (для этой раскладки): " + yn(gv.autoSprint) }) {
            gv.autoSprint = !gv.autoSprint
            prefs.edit().putBoolean("gauto_" + gv.profile, gv.autoSprint).apply()
        }
        if (vl != null) {
            toggle({ "Картинка игры: " + yn(gv.showFrame) }) {
                gv.showFrame = !gv.showFrame
                prefs.edit().putBoolean("gshow", gv.showFrame).apply()
                startStream()
                gv.invalidate()
            }
            toggle({ "Видео H.264 (чётче и плавнее): " + yn(videoOn) }) {
                videoOn = !videoOn
                prefs.edit().putBoolean("gvideo", videoOn).apply()
                startStream()
            }
            toggle({ "Качество картинки: " + qNames[gq - 1] }) {
                gq = if (gq >= 3) 1 else gq + 1
                prefs.edit().putInt("gq", gq).apply()
                startStream()
            }
        }

        col.addView(tb("Настроить кнопки этой раскладки") {
            closeSettings()
            gv.editMode = true
            editBar.visibility = View.VISIBLE
        }, context.lp(-1, -2, 0f, 4))
        col.addView(tb("Сбросить кнопки этой раскладки") { gv.resetButtons() }, context.lp(-1, -2, 0f, 4))
        col.addView(tb("Закрыть") { closeSettings() }, context.lp(-1, -2, 0f, 4))

        val scroll = ScrollView(context)
        scroll.addView(col)
        settings.addView(scroll, FrameLayout.LayoutParams(minOf((widthPx * 0.55f).toInt(), dp(560)), -2, Gravity.CENTER))
    }

    private fun openSettings() {
        gv.releaseAll()
        buildSettings()
        settings.visibility = View.VISIBLE
    }

    private fun closeSettings() {
        settings.visibility = View.GONE
    }

    private fun onProfile() {
        onSelected(null)
    }

    // ---------- панель настройки кнопок ----------

    private fun buildEditBar() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun add(v: View, w: Int = -2) {
            row.addView(v, LinearLayout.LayoutParams(w, -2).apply { setMargins(dp(3), 0, dp(3), 0) })
        }

        selInfo = context.label("Выбери кнопку", 13f, Colors.TEXT)
        selInfo.minWidth = dp(110)
        add(selInfo)

        sizeSeek = SeekBar(context)
        sizeSeek.max = 56
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val b = gv.selected
                if (fromUser && b != null) {
                    b.r = 14f + p
                    gv.saveButtons()
                    gv.invalidate()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        add(context.label("Размер", 12f, Colors.MUTED))
        add(sizeSeek, dp(150))

        keyBtn = tb("Клавиша") {
            val b = gv.selected
            if (b != null && b.special == null) {
                pickKey { name ->
                    applyKey(b, name)
                    gv.saveButtons()
                    onSelected(b)
                    gv.invalidate()
                }
            }
        }
        add(keyBtn)

        lookBtn = tb("Камера пальцем: нет") {
            val b = gv.selected
            if (b != null && b.special == null) {
                b.look = !b.look
                gv.saveButtons()
                onSelected(b)
            }
        }
        add(lookBtn)

        togBtn = tb("Залипание: нет") {
            val b = gv.selected
            if (b != null && b.special == null) {
                b.toggle = !b.toggle
                gv.saveButtons()
                onSelected(b)
                gv.invalidate()
            }
        }
        add(togBtn)

        delBtn = tb("Удалить") {
            val b = gv.selected
            if (b != null && b.special == null) {
                gv.buttons.remove(b)
                gv.selected = null
                onSelected(null)
                gv.saveButtons()
                gv.invalidate()
            }
        }
        add(delBtn)

        add(tb("+ Кнопка") {
            pickKey { name ->
                val b = GBtn("u" + System.currentTimeMillis(), "", null, 0, 0.5f, 0.5f, 30f)
                applyKey(b, name)
                gv.buttons.add(b)
                gv.selected = b
                onSelected(b)
                gv.saveButtons()
                gv.invalidate()
            }
        })
        add(tb("Готово") {
            gv.editMode = false
            editBar.visibility = View.GONE
        })

        editBar.isHorizontalScrollBarEnabled = false
        editBar.isClickable = true
        editBar.background = context.rounded(0xE60E0F13.toInt(), 18)
        editBar.addView(row)
        editBar.visibility = View.GONE
        addView(editBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })
        onSelected(null)
    }

    private fun onSelected(b: GBtn?) {
        if (!::selInfo.isInitialized) return
        val own = b != null && b.special == null
        selInfo.text = if (b == null) "Выбери кнопку" else "Кнопка: " + b.label
        sizeSeek.isEnabled = b != null
        if (b != null) sizeSeek.progress = (b.r - 14f).toInt().coerceIn(0, sizeSeek.max)
        keyBtn.isEnabled = own
        lookBtn.isEnabled = own
        togBtn.isEnabled = own
        delBtn.isEnabled = own
        lookBtn.text = "Камера пальцем: " + (if (b != null && b.look) "да" else "нет")
        togBtn.text = "Залипание: " + (if (b != null && b.toggle) "да" else "нет")
    }

    private fun keyLabel(name: String): String = when (name) {
        "space" -> "Пробел"
        "up" -> "↑"
        "down" -> "↓"
        "left" -> "←"
        "right" -> "→"
        "enter" -> "Enter"
        "esc" -> "Esc"
        "tab" -> "Tab"
        "shift" -> "Shift"
        "ctrl" -> "Ctrl"
        "alt" -> "Alt"
        "ЛКМ" -> "ЛКМ"
        "ПКМ" -> "ПКМ"
        else -> name.uppercase()
    }

    private fun applyKey(b: GBtn, name: String) {
        when (name) {
            "ЛКМ" -> {
                b.key = null
                b.mouse = 1
            }
            "ПКМ" -> {
                b.key = null
                b.mouse = 2
            }
            else -> {
                b.key = name
                b.mouse = 0
            }
        }
        b.label = keyLabel(name)
    }

    private fun pickKey(onPick: (String) -> Unit) {
        val items = keyNames.map { keyLabel(it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Клавиша")
            .setItems(items) { _, i -> onPick(keyNames[i]) }
            .show()
    }

    // ---------- картинка игры: видео и JPEG ----------

    private fun fitVideo() {
        val v = vl ?: return
        if (v.videoW <= 0 || width == 0 || height == 0) return
        val s = minOf(width.toFloat() / v.videoW, height.toFloat() / v.videoH)
        val dw = v.videoW * s
        val dh = v.videoH * s
        v.place(RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f))
    }

    private fun onVideoMode(codec: String, enc: String) {
        if (codec == "h264") {
            encName = enc.removePrefix("h264_").uppercase()
            gv.videoOn = gv.showFrame
            vl?.visibility = View.VISIBLE
        } else {
            encName = "JPEG"
            gv.videoOn = false
            vl?.visibility = View.INVISIBLE
        }
        gv.invalidate()
    }

    private fun onVideoFailed(msg: String) {
        // декодер на телефоне не запустился: возвращаемся к JPEG
        (remote as? WifiRemote)?.h264Enabled = false
        vl?.detach()
        gv.videoOn = false
        startStream()
        Toast.makeText(context, "Видео не запустилось, включён JPEG: $msg", Toast.LENGTH_LONG).show()
    }

    private fun startStream() {
        val w = remote as? WifiRemote ?: return
        if (!gv.showFrame) {
            w.onFrame = null
            vl?.detach()
            gv.videoOn = false
            w.screen(false, 1)
            gv.frame = null
            return
        }
        w.onFrame = { b, _, _ ->
            gv.frame = b
            frames++
        }
        if (videoOn && w.h264Enabled) {
            vl?.attach()
            vl?.visibility = View.VISIBLE
        } else {
            vl?.detach()
            vl?.visibility = View.INVISIBLE
            gv.videoOn = false
        }
        w.screen(true, gq, widthPx, true)
    }

    /** Закрытие режима: отпустить все клавиши и кнопки, остановить поток картинки. */
    fun shutdown() {
        main.removeCallbacks(tick)
        gv.releaseAll()
        val w = remote as? WifiRemote
        if (w != null) {
            w.onFrame = null
            w.onVideoMode = null
            vl?.shutdown()
            w.screen(false, 1)
        }
    }
}
