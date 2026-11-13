package ru.pult.pc

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Кнопка игрового экрана. Положение: доли экрана (центр кнопки), размер: радиус в dp.
 * key: клавиша клавиатуры, mouse: 1 левая / 2 правая кнопка мыши, look: «камера по пальцу»,
 * toggle: «залипание» (нажал и клавиша удерживается, нажал ещё раз и отпустилась).
 * special: служебная кнопка ("exit", "settings", "menu"), её нельзя удалить или переназначить.
 */
class GBtn(
    var id: String,
    var label: String,
    var key: String?,
    var mouse: Int,
    var x: Float,
    var y: Float,
    var r: Float,
    var look: Boolean = false,
    val special: String? = null,
    var toggle: Boolean = false
) {
    var cx = 0f
    var cy = 0f
    var rr = 0f
}

/** Раскладки кнопок: «Шутер» (CS и подобные), «GTA пешком», «GTA авто». У каждой свои кнопки и настройки. */
object GameConfig {
    val PROFILES = listOf("cs", "gta", "car")
    val NAMES = mapOf("cs" to "Шутер", "gta" to "GTA пешком", "car" to "GTA авто")

    private fun key(p: String) = if (p == "cs") "game_btns" else "game_btns_$p"

    private fun shooter(): MutableList<GBtn> {
        val l = ArrayList<GBtn>()
        l.add(GBtn("fire", "Огонь", null, 1, 0.885f, 0.744f, 46f, look = true))
        l.add(GBtn("aim", "Цель", null, 2, 0.75f, 0.83f, 32f))
        l.add(GBtn("jump", "Прыжок", "space", 0, 0.925f, 0.456f, 34f))
        l.add(GBtn("crouch", "Присесть", "ctrl", 0, 0.79f, 0.54f, 30f))
        l.add(GBtn("reload", "R", "r", 0, 0.6775f, 0.69f, 28f))
        l.add(GBtn("use", "E", "e", 0, 0.6775f, 0.456f, 28f))
        l.add(GBtn("f", "F", "f", 0, 0.57f, 0.82f, 26f))
        l.add(GBtn("q", "Q", "q", 0, 0.57f, 0.583f, 26f))
        l.add(GBtn("sprint", "Бег", "shift", 0, 0.2875f, 0.806f, 30f))
        l.add(GBtn("tab", "Tab", "tab", 0, 0.075f, 0.1f, 24f))
        l.add(GBtn("esc", "Esc", "esc", 0, 0.8125f, 0.1f, 24f))
        for (i in 1..4) l.add(GBtn("k$i", "$i", "$i", 0, 0.5f + (i - 2.5f) * 0.07f, 0.19f, 22f))
        return l
    }

    // Клавиши GTA V по умолчанию на ПК. Если у тебя другие, поменяй их в настройке кнопок.
    private fun gtaFoot(): MutableList<GBtn> {
        val l = ArrayList<GBtn>()
        l.add(GBtn("fire", "Огонь", null, 1, 0.885f, 0.744f, 46f, look = true))
        l.add(GBtn("aim", "Цель", null, 2, 0.75f, 0.83f, 32f))
        l.add(GBtn("jump", "Прыжок", "space", 0, 0.925f, 0.456f, 34f))
        l.add(GBtn("cover", "Укрытие", "q", 0, 0.79f, 0.54f, 30f))
        l.add(GBtn("reload", "R", "r", 0, 0.6775f, 0.69f, 28f))
        l.add(GBtn("enter", "Сесть", "f", 0, 0.57f, 0.82f, 28f))
        l.add(GBtn("wheel", "Оружие", "tab", 0, 0.6775f, 0.456f, 28f))
        l.add(GBtn("stealth", "Красться", "ctrl", 0, 0.57f, 0.583f, 26f))
        l.add(GBtn("sprint", "Бег", "shift", 0, 0.2875f, 0.806f, 30f))
        l.add(GBtn("phone", "Телефон", "up", 0, 0.075f, 0.3f, 24f))
        l.add(GBtn("inter", "M", "m", 0, 0.075f, 0.47f, 22f))
        l.add(GBtn("esc", "Esc", "esc", 0, 0.8125f, 0.1f, 24f))
        return l
    }

    private fun gtaCar(): MutableList<GBtn> {
        val l = ArrayList<GBtn>()
        l.add(GBtn("gas", "Газ", "w", 0, 0.925f, 0.62f, 50f))
        l.add(GBtn("brake", "Тормоз", "s", 0, 0.78f, 0.8f, 42f))
        l.add(GBtn("left", "◀", "a", 0, 0.09f, 0.72f, 44f))
        l.add(GBtn("right", "▶", "d", 0, 0.25f, 0.72f, 44f))
        l.add(GBtn("hand", "Ручник", "space", 0, 0.64f, 0.82f, 32f))
        l.add(GBtn("horn", "Сигнал", "e", 0, 0.64f, 0.58f, 26f))
        l.add(GBtn("lights", "Фары", "h", 0, 0.5f, 0.86f, 24f))
        l.add(GBtn("cam", "Камера", "v", 0, 0.075f, 0.3f, 24f))
        l.add(GBtn("back", "Назад", "c", 0, 0.075f, 0.47f, 22f))
        l.add(GBtn("exitcar", "Выйти", "f", 0, 0.9f, 0.27f, 30f))
        l.add(GBtn("phone", "Телефон", "up", 0, 0.5f, 0.3f, 24f))
        l.add(GBtn("esc", "Esc", "esc", 0, 0.8125f, 0.1f, 24f))
        return l
    }

    private fun specials(): List<GBtn> = listOf(
        GBtn("exit", "✕", null, 0, 0.9375f, 0.1f, 24f, special = "exit"),
        GBtn("settings", "⚙", null, 0, 0.745f, 0.1f, 24f, special = "settings"),
        GBtn("menu", "Меню", null, 0, 0.17f, 0.1f, 24f, special = "menu")
    )

    fun defaults(p: String): MutableList<GBtn> {
        val l = when (p) {
            "gta" -> gtaFoot()
            "car" -> gtaCar()
            else -> shooter()
        }
        l.addAll(specials())
        return l
    }

    fun load(prefs: SharedPreferences, p: String): MutableList<GBtn> {
        val raw = prefs.getString(key(p), null) ?: return defaults(p)
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<GBtn>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    GBtn(
                        o.getString("id"), o.getString("label"),
                        if (o.isNull("key")) null else o.getString("key"),
                        o.optInt("mouse"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.getDouble("r").toFloat(), o.optBoolean("look"),
                        if (o.isNull("special")) null else o.getString("special"),
                        o.optBoolean("toggle")
                    )
                )
            }
            // служебные кнопки должны быть всегда
            for (s in specials()) if (out.none { it.special == s.special }) out.add(s)
            out
        } catch (e: Exception) {
            defaults(p)
        }
    }

    fun save(prefs: SharedPreferences, p: String, list: List<GBtn>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(
                JSONObject()
                    .put("id", b.id).put("label", b.label)
                    .put("key", b.key ?: JSONObject.NULL).put("mouse", b.mouse)
                    .put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("r", b.r.toDouble())
                    .put("look", b.look).put("special", b.special ?: JSONObject.NULL)
                    .put("toggle", b.toggle)
            )
        }
        prefs.edit().putString(key(p), arr.toString()).apply()
    }
}

/**
 * Игровая раскладка на весь экран (горизонтально):
 *  - слева: стик (WASD), касание в левой части, куда угодно; у края стика можно включить авто-бег (Shift)
 *  - справа: камера, двигай пальцем по свободному месту; кнопка «Огонь» тоже ведёт камеру
 *    (держишь огонь и тянешь палец, камера поворачивается). При прицеливании (правая кнопка мыши) камера медленнее
 *  - сверху по центру: переключатель раскладок (Шутер, GTA пешком, GTA авто)
 *  - позади: картинка игры с экрана компьютера (видео или JPEG)
 *  - режим «Меню»: палец двигает обычный курсор, касание это клик (для меню игры)
 *  - режим настройки: кнопки перетаскиваются, размер и клавиши меняются в панели
 * Работает с несколькими пальцами сразу.
 */
class GameView(
    context: Context,
    private val remote: Remote,
    private val prefs: SharedPreferences,
    private val onExit: () -> Unit,
    private val onSettings: () -> Unit,
    private val onSelect: (GBtn?) -> Unit,
    private val onProfile: (String) -> Unit
) : View(context) {

    var profile: String = (prefs.getString("gprofile", "cs") ?: "cs").let { if (it in GameConfig.PROFILES) it else "cs" }
    var buttons: MutableList<GBtn> = GameConfig.load(prefs, profile)
    var sensX = 1f
    var sensY = 1f
    var adsMul = 0.7f              // во сколько раз камера медленнее, пока зажат прицел
    var btnAlpha = 0.75f
    var haptic = true
    var showPills = true
    var stickEnabled = prefs.getBoolean("gstick_$profile", profile != "car")
    var autoSprint = prefs.getBoolean("gauto_$profile", profile == "gta")
    var showFrame = true
    var videoOn = false            // картинку рисует слой с видео под этим view
    var info = ""
        set(v) {
            field = v
            postInvalidateOnAnimation()
        }
    var frame: Bitmap? = null
        set(v) {
            field = v
            postInvalidateOnAnimation()
        }
    var selected: GBtn? = null
    var editMode = false
        set(v) {
            if (v) releaseAll()
            field = v
            selected = null
            onSelect(null)
            invalidate()
        }
    var cursorMode = false

    private class Role(val type: Int, val btn: GBtn?) {
        var ox = 0f
        var oy = 0f
        var lx = 0f
        var ly = 0f
        var t = 0L
        var moved = false
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val main = Handler(Looper.getMainLooper())
    private val pills = ArrayList<RectF>()

    private val roles = HashMap<Int, Role>()
    private val pressed = HashSet<String>()          // нажатые кнопки (для подсветки)
    private val latched = HashSet<String>()          // «залипшие» кнопки
    private val heldKeys = HashSet<String>()
    private val heldMouse = HashSet<Int>()
    private val stickKeys = HashSet<String>()
    private var stickRole: Role? = null
    private var autoShift = false
    private var knobX = 0f
    private var knobY = 0f

    private var accX = 0f
    private var accY = 0f
    private var pendX = 0
    private var pendY = 0
    private var flushScheduled = false
    private val flush = Runnable {
        flushScheduled = false
        if (pendX != 0 || pendY != 0) {
            if (cursorMode) remote.move(pendX, pendY) else remote.look(pendX, pendY)
            pendX = 0
            pendY = 0
        }
    }

    private var dragBtn: GBtn? = null
    private var dragOffX = 0f
    private var dragOffY = 0f

    fun saveButtons() = GameConfig.save(prefs, profile, buttons)

    fun resetButtons() {
        buttons = GameConfig.defaults(profile)
        selected = null
        onSelect(null)
        saveButtons()
        invalidate()
    }

    /** Переключает раскладку: свои кнопки, свой стик и авто-бег. */
    fun loadProfile(name: String) {
        if (name == profile || name !in GameConfig.PROFILES) return
        releaseAll()
        profile = name
        prefs.edit().putString("gprofile", name).apply()
        buttons = GameConfig.load(prefs, name)
        stickEnabled = prefs.getBoolean("gstick_$name", name != "car")
        autoSprint = prefs.getBoolean("gauto_$name", name == "gta")
        selected = null
        onSelect(null)
        onProfile(name)
        invalidate()
    }

    private fun layoutButtons() {
        val w = width.toFloat()
        val h = height.toFloat()
        for (b in buttons) {
            b.cx = b.x * w
            b.cy = b.y * h
            b.rr = b.r * d
        }
        // переключатель раскладок сверху по центру
        pills.clear()
        val pw = 92 * d
        val ph = 24 * d
        val gap = 6 * d
        val total = GameConfig.PROFILES.size * pw + (GameConfig.PROFILES.size - 1) * gap
        var x = (w - total) / 2f
        for (i in GameConfig.PROFILES.indices) {
            pills.add(RectF(x, 4 * d, x + pw, 4 * d + ph))
            x += pw + gap
        }
    }

    private fun pillAt(x: Float, y: Float): Int {
        if (!showPills) return -1
        for (i in pills.indices) if (pills[i].contains(x, y)) return i
        return -1
    }

    private fun hit(x: Float, y: Float): GBtn? {
        // с конца: верхняя кнопка выбирается первой
        for (i in buttons.indices.reversed()) {
            val b = buttons[i]
            if (hypot(x - b.cx, y - b.cy) <= b.rr + 6 * d) return b
        }
        return null
    }

    /** Нажать или отпустить то, что привязано к кнопке (клавишу или кнопку мыши). */
    private fun act(b: GBtn, down: Boolean) {
        val k = b.key
        if (k != null) {
            if (down) {
                heldKeys.add(k)
                remote.keyHold(k, true)
            } else if (heldKeys.remove(k)) {
                remote.keyHold(k, false)
            }
        } else if (b.mouse != 0) {
            if (down) {
                heldMouse.add(b.mouse)
                remote.gameButton(b.mouse, true)
            } else if (heldMouse.remove(b.mouse)) {
                remote.gameButton(b.mouse, false)
            }
        }
    }

    private fun press(b: GBtn) {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pressed.add(b.id)
        when (b.special) {
            "exit" -> {
                onExit()
                return
            }
            "settings" -> {
                onSettings()
                return
            }
            "menu" -> {
                cursorMode = !cursorMode
                return
            }
        }
        if (b.toggle) {
            if (b.id in latched) {
                latched.remove(b.id)
                pressed.remove(b.id)
                act(b, false)
            } else {
                latched.add(b.id)
                act(b, true)
            }
            return
        }
        act(b, true)
    }

    private fun release(b: GBtn) {
        if (b.toggle) return                 // залипшая кнопка отпускается повторным нажатием
        pressed.remove(b.id)
        act(b, false)
    }

    private fun setAutoShift(on: Boolean) {
        if (on == autoShift) return
        autoShift = on
        if (!on && "shift" in heldKeys) return     // Shift при этом держит кнопка
        remote.keyHold("shift", on)
    }

    private fun setStick(dx: Float, dy: Float) {
        val dead = 16 * d
        val want = HashSet<String>()
        if (dy < -dead) want.add("w")
        if (dy > dead) want.add("s")
        if (dx < -dead) want.add("a")
        if (dx > dead) want.add("d")
        for (k in stickKeys.toList()) {
            if (k !in want) {
                stickKeys.remove(k)
                remote.keyHold(k, false)
            }
        }
        for (k in want) {
            if (stickKeys.add(k)) remote.keyHold(k, true)
        }
        // у самого края стика вперёд: включается бег
        setAutoShift(autoSprint && dy < -dead && hypot(dx, dy) > 0.85f * 64 * d)
    }

    private fun stopStick() {
        for (k in stickKeys.toList()) remote.keyHold(k, false)
        stickKeys.clear()
        setAutoShift(false)
        stickRole = null
    }

    private fun addLook(dx: Float, dy: Float) {
        val k = if (cursorMode) 1.8f else 2.2f
        val ads = if (!cursorMode && 2 in heldMouse) adsMul else 1f
        accX += dx / d * k * (if (cursorMode) 1f else sensX * ads)
        accY += dy / d * k * (if (cursorMode) 1f else sensY * ads)
        val ix = accX.toInt()
        val iy = accY.toInt()
        accX -= ix
        accY -= iy
        pendX += ix
        pendY += iy
        if ((pendX != 0 || pendY != 0) && !flushScheduled) {
            flushScheduled = true
            main.postDelayed(flush, 8)
        }
    }

    /** Отпустить всё, что зажато (при выходе из режима, смене раскладки или потере связи). */
    fun releaseAll() {
        main.removeCallbacks(flush)
        flushScheduled = false
        pendX = 0
        pendY = 0
        stopStick()
        for (k in heldKeys.toList()) remote.keyHold(k, false)
        for (m in heldMouse.toList()) remote.gameButton(m, false)
        heldKeys.clear()
        heldMouse.clear()
        pressed.clear()
        latched.clear()
        roles.clear()
        invalidate()
    }

    private fun editTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pi = pillAt(e.x, e.y)
                if (pi >= 0) {
                    loadProfile(GameConfig.PROFILES[pi])
                    return true
                }
                val b = hit(e.x, e.y)
                selected = b
                onSelect(b)
                dragBtn = b
                if (b != null) {
                    dragOffX = b.cx - e.x
                    dragOffY = b.cy - e.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val b = dragBtn
                if (b != null && width > 0 && height > 0) {
                    b.x = ((e.x + dragOffX) / width).coerceIn(0.03f, 0.97f)
                    b.y = ((e.y + dragOffY) / height).coerceIn(0.05f, 0.95f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragBtn != null) saveButtons()
                dragBtn = null
            }
        }
        invalidate()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return editTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                val pi = pillAt(x, y)
                if (pi >= 0) {
                    roles[id] = Role(3, null)
                    loadProfile(GameConfig.PROFILES[pi])
                    return true
                }
                val b = hit(x, y)
                if (b != null) {
                    val r = Role(0, b)
                    r.lx = x
                    r.ly = y
                    roles[id] = r
                    press(b)
                } else if (stickEnabled && x < width * 0.45f && stickRole == null && !cursorMode) {
                    val r = Role(1, null)
                    r.ox = x
                    r.oy = y
                    knobX = x
                    knobY = y
                    roles[id] = r
                    stickRole = r
                } else {
                    val r = Role(2, null)
                    r.lx = x
                    r.ly = y
                    r.ox = x
                    r.oy = y
                    r.t = e.eventTime
                    roles[id] = r
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val role = roles[e.getPointerId(i)] ?: continue
                    val x = e.getX(i)
                    val y = e.getY(i)
                    when (role.type) {
                        0 -> if (role.btn?.look == true) {
                            // палец на кнопке «Огонь» и ведёт камеру
                            addLook(x - role.lx, y - role.ly)
                            role.lx = x
                            role.ly = y
                        }
                        1 -> {
                            var dx = x - role.ox
                            var dy = y - role.oy
                            val len = hypot(dx, dy)
                            val maxLen = 64 * d
                            if (len > maxLen) {
                                dx = dx / len * maxLen
                                dy = dy / len * maxLen
                            }
                            knobX = role.ox + dx
                            knobY = role.oy + dy
                            setStick(dx, dy)
                        }
                        2 -> {
                            if (!role.moved && hypot(x - role.ox, y - role.oy) > 8 * d) role.moved = true
                            addLook(x - role.lx, y - role.ly)
                            role.lx = x
                            role.ly = y
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val role = roles.remove(id)
                if (role != null) {
                    when (role.type) {
                        0 -> role.btn?.let { release(it) }
                        1 -> stopStick()
                        2 -> if (cursorMode && !role.moved && e.eventTime - role.t < 250) remote.click(1)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun onDraw(c: Canvas) {
        layoutButtons()
        if (!videoOn) {
            p.style = Paint.Style.FILL
            p.color = Colors.BG
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            val f = frame
            if (showFrame && f != null) {
                val s = minOf(width.toFloat() / f.width, height.toFloat() / f.height)
                val dw = f.width * s
                val dh = f.height * s
                dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
                c.drawBitmap(f, null, dst, bmpPaint)
            } else if (!editMode) {
                p.color = Colors.MUTED
                p.textSize = 13 * d
                p.textAlign = Paint.Align.CENTER
                c.drawText("слева: движение", width * 0.22f, height * 0.5f, p)
                c.drawText("свободное место справа: камера", width * 0.66f, height * 0.5f, p)
            }
        }

        // стик
        val st = stickRole
        if (st != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = alpha(Colors.TEXT, 0.5f)
            c.drawCircle(st.ox, st.oy, 64 * d, p)
            p.style = Paint.Style.FILL
            p.color = alpha(if (autoShift) Colors.OK else Colors.ACCENT, 0.85f)
            c.drawCircle(knobX, knobY, 26 * d, p)
        }

        // переключатель раскладок
        if (showPills) {
            for (i in pills.indices) {
                val on = GameConfig.PROFILES[i] == profile
                p.style = Paint.Style.FILL
                p.color = if (on) alpha(Colors.ACCENT, 0.95f) else alpha(Colors.SURFACE, 0.7f)
                c.drawRoundRect(pills[i], 12 * d, 12 * d, p)
                p.color = Colors.TEXT
                p.textAlign = Paint.Align.CENTER
                p.textSize = 11 * d
                c.drawText(GameConfig.NAMES[GameConfig.PROFILES[i]] ?: "", pills[i].centerX(), pills[i].centerY() + 4 * d, p)
            }
        }

        // кнопки
        for (b in buttons) {
            val on = b.id in pressed || (b.special == "menu" && cursorMode)
            p.style = Paint.Style.FILL
            p.color = if (on) alpha(Colors.ACCENT, 0.95f) else alpha(Colors.SURFACE, btnAlpha)
            c.drawCircle(b.cx, b.cy, b.rr, p)
            // тонкий светлый ободок: кнопка читается и на светлой картинке
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = alpha(Colors.TEXT, if (b.toggle) 0.9f else 0.3f)
            c.drawCircle(b.cx, b.cy, b.rr, p)
            if (editMode && b === selected) {
                p.strokeWidth = 3 * d
                p.color = Colors.OK
                c.drawCircle(b.cx, b.cy, b.rr + 3 * d, p)
            }
            p.style = Paint.Style.FILL
            p.color = Colors.TEXT
            p.textAlign = Paint.Align.CENTER
            p.textSize = (if (b.rr > 40 * d) 15 else if (b.rr > 24 * d) 12 else 10) * d
            c.drawText(b.label, b.cx, b.cy + p.textSize / 3f, p)
        }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
        p.textSize = 11 * d
        p.color = Colors.OK
        if (info.isNotEmpty()) c.drawText(info, 10 * d, height - 10 * d, p)
        if (cursorMode) {
            p.textAlign = Paint.Align.CENTER
            p.color = Colors.WARN
            c.drawText("режим меню: палец двигает курсор, касание это клик", width / 2f, height - 10 * d, p)
        }
        if (editMode) {
            p.textAlign = Paint.Align.CENTER
            p.color = Colors.WARN
            p.textSize = 13 * d
            c.drawText("Настройка раскладки «" + (GameConfig.NAMES[profile] ?: "") + "»: перетащи кнопку, внизу размер и клавиша",
                width / 2f, height * 0.5f, p)
        }
    }
}
