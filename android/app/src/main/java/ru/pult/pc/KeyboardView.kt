package ru.pult.pc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Полупрозрачная клавиатура поверх картинки экрана: монитор виден сквозь неё.
 * Буквы русские и английские, цифры, знаки, Esc/Tab/стрелки и т.д. на второй странице («123»).
 * Ctrl / Alt / Win нажимаются заранее и действуют на следующую клавишу (Ctrl, потом C = Ctrl+C).
 * Шифт: один раз — на одну букву, два раза — постоянно.
 * Работает с несколькими пальцами сразу, ⌫ и стрелки повторяются при удержании.
 */
class KeyboardView(
    context: Context,
    private val remote: Remote,
    private val onHide: () -> Unit,
    private val onDock: () -> Unit
) : View(context) {

    private class Key(val label: String, val w: Float, val kind: Int, val value: String = "") {
        val r = RectF()
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private var rows: List<List<Key>> = emptyList()

    private var ru = false
    private var symbols = false
    private var shift = 0            // 0 выкл, 1 на одну букву, 2 постоянно
    private var ctrl = false
    private var alt = false
    private var win = false

    private val down = HashMap<Int, Key>()
    private var repeatKey: Key? = null
    private val repeat = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            press(k)
            main.postDelayed(this, 55)
        }
    }

    init {
        build()
    }

    // ---------- раскладка ----------

    private fun letter(c: Char): Key {
        val s = c.toString()
        return Key(if (shift > 0) s.uppercase() else s, 1f, CHAR, s)
    }

    private fun sym(c: Char): Key = Key(c.toString(), 1f, CHAR, c.toString())

    private fun build() {
        val out = ArrayList<List<Key>>()
        if (!symbols) {
            val l = if (ru) listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
            else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            out.add("1234567890".map { sym(it) })
            out.add(l[0].map { letter(it) })
            out.add(l[1].map { letter(it) })
            out.add(
                listOf(Key(if (shift == 2) "⇪" else "⇧", 1.5f, SHIFT)) +
                    l[2].map { letter(it) } +
                    listOf(Key("⌫", 1.5f, BKSP, "backspace"))
            )
        } else {
            out.add("!@#\$%^&*()".map { sym(it) })
            out.add("-=[]\\;',./".map { sym(it) })
            out.add("_+{}|:\"<>?".map { sym(it) })
            out.add(
                listOf(
                    Key("Esc", 1.2f, SPECIAL, "esc"), Key("Tab", 1.2f, SPECIAL, "tab"),
                    Key("Home", 1.2f, SPECIAL, "home"), Key("End", 1.2f, SPECIAL, "end"),
                    Key("PgUp", 1.2f, SPECIAL, "pgup"), Key("PgDn", 1.2f, SPECIAL, "pgdn"),
                    Key("↑", 1f, SPECIAL, "up"), Key("↓", 1f, SPECIAL, "down"),
                    Key("Del", 1.2f, SPECIAL, "delete"), Key("⌫", 1.5f, BKSP, "backspace")
                )
            )
        }
        out.add(
            listOf(
                Key(if (symbols) "ABC" else "123", 1.3f, PAGE),
                Key("Ctrl", 1.1f, MOD, "ctrl"), Key("Alt", 1f, MOD, "alt"), Key("Win", 1f, MOD, "win"),
                Key(if (ru) "RU" else "EN", 1f, LANG),
                Key(if (ru) "пробел" else "space", 3.4f, SPACE, "space"),
                Key("←", 1f, SPECIAL, "left"), Key("→", 1f, SPECIAL, "right"),
                Key("⏎", 1.4f, ENTER, "enter"),
                Key("⇅", 1f, DOCK), Key("⌄", 1f, HIDE)
            )
        )
        rows = out
        layoutKeys()
        invalidate()
    }

    private fun layoutKeys() {
        val n = rows.size
        if (n == 0 || width == 0 || height == 0) return
        val pad = 6 * d
        val rowH = (height - 2 * pad) / n
        val g = 2.5f * d
        val avail = width - 2 * pad
        for ((ri, row) in rows.withIndex()) {
            val total = row.fold(0f) { a, k -> a + k.w }
            var x = pad
            val y = pad + ri * rowH
            for (k in row) {
                val kw = avail * k.w / total
                k.r.set(x + g, y + g, x + kw - g, y + rowH - g)
                x += kw
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    // ---------- нажатия ----------

    private fun combo(name: String): String {
        val sb = StringBuilder()
        if (ctrl) sb.append("ctrl+")
        if (alt) sb.append("alt+")
        if (win) sb.append("win+")
        sb.append(name)
        return sb.toString()
    }

    private fun clearMods() {
        if (ctrl || alt || win) {
            ctrl = false
            alt = false
            win = false
            invalidate()
        }
    }

    private fun modOn(name: String): Boolean = when (name) {
        "ctrl" -> ctrl
        "alt" -> alt
        else -> win
    }

    private fun typeChar(s: String) {
        if (ctrl || alt || win) {
            val ch = s[0]
            val latin = if (ch in 'a'..'z' || ch in '0'..'9') ch.toString() else RU2EN[ch]?.toString()
            if (latin != null) remote.key(combo((if (shift > 0) "shift+" else "") + latin))
            clearMods()
        } else {
            remote.text(if (shift > 0) s.uppercase() else s)
        }
        if (shift == 1) {
            shift = 0
            build()
        }
    }

    private fun press(k: Key) {
        when (k.kind) {
            CHAR -> typeChar(k.value)
            SHIFT -> {
                shift = (shift + 1) % 3
                build()
            }
            PAGE -> {
                symbols = !symbols
                build()
            }
            LANG -> {
                ru = !ru
                build()
            }
            MOD -> {
                when (k.value) {
                    "ctrl" -> ctrl = !ctrl
                    "alt" -> alt = !alt
                    else -> win = !win
                }
                invalidate()
            }
            BKSP, SPACE, ENTER, SPECIAL -> {
                remote.key(combo(k.value))
                clearMods()
            }
            DOCK -> onDock()
            HIDE -> onHide()
        }
    }

    private fun repeatable(k: Key): Boolean =
        k.kind == BKSP || (k.kind == SPECIAL && k.value in REPEATING)

    private fun hit(x: Float, y: Float): Key? {
        val g = 3 * d
        for (row in rows) for (k in row) {
            val r = k.r
            if (x >= r.left - g && x <= r.right + g && y >= r.top - g && y <= r.bottom + g) return k
        }
        return null
    }

    private fun stopRepeat() {
        repeatKey = null
        main.removeCallbacks(repeat)
    }

    /** Вызывается при закрытии экрана: сбросить повторы и нажатые Ctrl/Alt/Win. */
    fun shutdown() {
        stopRepeat()
        down.clear()
        ctrl = false
        alt = false
        win = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = hit(e.getX(i), e.getY(i))
                if (k != null) {
                    down[e.getPointerId(i)] = k
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press(k)
                    if (repeatable(k)) {
                        stopRepeat()
                        repeatKey = k
                        main.postDelayed(repeat, 400)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val k = down.remove(e.getPointerId(e.actionIndex))
                if (k != null && k === repeatKey) stopRepeat()
            }
            MotionEvent.ACTION_CANCEL -> {
                down.clear()
                stopRepeat()
            }
        }
        invalidate()
        return true
    }

    // ---------- рисование ----------

    override fun onDraw(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = 0xAA0E0F13.toInt()
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16 * d, 16 * d, p)
        val pressed = down.values
        for (row in rows) for (k in row) {
            val on = pressed.any { it === k } ||
                (k.kind == SHIFT && shift > 0) ||
                (k.kind == MOD && modOn(k.value))
            p.color = if (on) Colors.ACCENT else 0xCC252833.toInt()
            c.drawRoundRect(k.r, 8 * d, 8 * d, p)
            p.color = Colors.TEXT
            p.textAlign = Paint.Align.CENTER
            val small = k.label.length > 1 && k.label[0].isLetterOrDigit()
            p.textSize = (if (small) 12 else 17) * d
            c.drawText(k.label, k.r.centerX(), k.r.centerY() + p.textSize / 3f, p)
        }
    }

    companion object {
        private const val CHAR = 0
        private const val SHIFT = 1
        private const val BKSP = 2
        private const val ENTER = 3
        private const val SPACE = 4
        private const val MOD = 5
        private const val LANG = 6
        private const val PAGE = 7
        private const val SPECIAL = 8
        private const val HIDE = 9
        private const val DOCK = 10

        private val REPEATING = setOf("left", "right", "up", "down", "delete")

        /** Для Ctrl+буква на русской раскладке: какая это клавиша на английской. */
        private val RU2EN: Map<Char, Char> = mapOf(
            'й' to 'q', 'ц' to 'w', 'у' to 'e', 'к' to 'r', 'е' to 't', 'н' to 'y', 'г' to 'u',
            'ш' to 'i', 'щ' to 'o', 'з' to 'p', 'ф' to 'a', 'ы' to 's', 'в' to 'd', 'а' to 'f',
            'п' to 'g', 'р' to 'h', 'о' to 'j', 'л' to 'k', 'д' to 'l', 'я' to 'z', 'ч' to 'x',
            'с' to 'c', 'м' to 'v', 'и' to 'b', 'т' to 'n', 'ь' to 'm'
        )
    }
}
