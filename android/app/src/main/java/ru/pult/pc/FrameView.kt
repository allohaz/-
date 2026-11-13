package ru.pult.pc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Показывает картинку экрана компьютера (с сохранением пропорций) и точку-курсор.
 * fullscreenStyle: без скруглений и рамок (режим «на весь экран»).
 * zoom > 1: картинка увеличивается и едет за курсором.
 * insetBottom: оставить снизу место (например, под клавиатуру), картинка вписывается выше.
 */
open class FrameView(context: Context) : View(context) {

    protected val density = resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    protected val dst = RectF()
    private val path = Path()

    var frame: Bitmap? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }
    var cursorX = -1f
    var cursorY = -1f

    // видео: пока videoW > 0 и нет JPEG-кадра, картинку рисует слой с видео под этим view, тут только курсор
    var videoW = 0
    var videoH = 0
    var onDst: ((RectF) -> Unit)? = null     // сообщает, в каком прямоугольнике показана картинка (для видео)

    var fullscreenStyle = false
    var caption: String? = null        // если задана, вместо подсказки пишется эта строка
    var insetBottom = 0
        set(v) {
            field = v
            invalidate()
        }
    var zoom = 1f
        set(v) {
            val z = if (v < 1.05f) 1f else v.coerceAtMost(MAX_ZOOM)
            if (z > 1f && field <= 1f) {
                panX = if (cursorX >= 0f) cursorX else 0.5f
                panY = if (cursorY >= 0f) cursorY else 0.5f
            }
            val changed = z != field
            field = z
            invalidate()
            if (changed) onZoomChange?.invoke(z)
        }
    var onZoomChange: ((Float) -> Unit)? = null

    /** Зум щипком: при первом увеличении картинка приближается к точке между пальцами (координаты на экране). */
    fun zoomAround(z: Float, fx: Float, fy: Float) {
        if (z > 1f && zoom <= 1f && frame != null) {
            computeDst()
            panX = ((fx - dst.left) / dst.width()).coerceIn(0f, 1f)
            panY = ((fy - dst.top) / dst.height()).coerceIn(0f, 1f)
        }
        zoom = z
    }
    private var panX = 0.5f
    private var panY = 0.5f

    protected open fun bgColor(): Int = Colors.SURFACE
    protected open fun hintLines(): List<String> = listOf("Ждём картинку с компьютера…")

    private fun availH(): Float = (height - insetBottom).toFloat().coerceAtLeast(1f)

    /** Сдвигает центр увеличенной картинки, только если курсор подошёл к краю (так картинка не дёргается). */
    private fun follow(hx: Float, hy: Float) {
        val dx = hx * 0.6f
        val dy = hy * 0.6f
        if (cursorX > panX + dx) panX = cursorX - dx else if (cursorX < panX - dx) panX = cursorX + dx
        if (cursorY > panY + dy) panY = cursorY - dy else if (cursorY < panY - dy) panY = cursorY + dy
        panX = panX.coerceIn(hx, 1f - hx)
        panY = panY.coerceIn(hy, 1f - hy)
    }

    protected fun computeDst() {
        val f = frame
        val ah = availH()
        val fw = f?.width ?: videoW
        val fh = f?.height ?: videoH
        if (fw <= 0 || fh <= 0) {
            dst.set(0f, 0f, width.toFloat(), ah)
            return
        }
        val s = minOf(width.toFloat() / fw, ah / fh)
        val dw = fw * s * zoom
        val dh = fh * s * zoom
        if (zoom > 1f && cursorX >= 0f && cursorY >= 0f) {
            follow(minOf(1f, width / dw) / 2f, minOf(1f, ah / dh) / 2f)
        }
        val left = if (dw <= width) (width - dw) / 2f else (width / 2f - panX * dw).coerceIn(width - dw, 0f)
        val top = if (dh <= ah) (ah - dh) / 2f else (ah / 2f - panY * dh).coerceIn(ah - dh, 0f)
        dst.set(left, top, left + dw, top + dh)
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame
        val video = f == null && videoW > 0
        if (!video) {
            val r = if (fullscreenStyle) 0f else 22 * density
            paint.style = Paint.Style.FILL
            paint.color = bgColor()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        }

        if (f != null || video) {
            computeDst()
            if (f != null) {
                canvas.save()
                path.reset()
                val cr = if (fullscreenStyle) 0f else 14 * density
                path.addRoundRect(dst, cr, cr, Path.Direction.CW)
                canvas.clipPath(path)
                canvas.drawBitmap(f, null, dst, bmpPaint)
                canvas.restore()
            }
            onDst?.invoke(dst)
            if (cursorX >= 0f && cursorY >= 0f) {
                val x = dst.left + cursorX * dst.width()
                val y = dst.top + cursorY * dst.height()
                paint.style = Paint.Style.FILL
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(x, y, 6 * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * density
                paint.color = Colors.ACCENT
                canvas.drawCircle(x, y, 6 * density, paint)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Colors.MUTED
            paint.textSize = 14 * density
            paint.textAlign = Paint.Align.CENTER
            val cap = caption
            val lines = if (cap != null) listOf(cap) else hintLines()
            var y = availH() / 2f - (lines.size - 1) * 11 * density
            for (l in lines) {
                canvas.drawText(l, width / 2f, y, paint)
                y += 22 * density
            }
        }
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}
