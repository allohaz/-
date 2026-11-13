package ru.pult.pc

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Слой с видео экрана ПК. Лежит под остальными элементами; положение и размер картинки задаёт place().
 * attach() включает приём видео (компьютер переходит на H.264), detach() выключает (возвращается JPEG).
 */
class VideoLayer(
    context: Context,
    private val remote: WifiRemote,
    private val onFail: (String) -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {

    var videoW = 0
        private set
    var videoH = 0
        private set
    var onVideoSize: ((Int, Int) -> Unit)? = null
    val frames = AtomicInteger()

    private val main = Handler(Looper.getMainLooper())
    private val player = VideoPlayer({ msg -> main.post { onFail(msg) } }, { remote.requestKey() })
    private var surface: Surface? = null

    init {
        surfaceTextureListener = this
    }

    fun attach() {
        remote.onVideo = { data, key, w, h ->
            if (w != videoW || h != videoH) {
                videoW = w
                videoH = h
                main.post { onVideoSize?.invoke(w, h) }
            }
            frames.incrementAndGet()
            player.submit(data, key, w, h)
        }
    }

    fun detach() {
        remote.onVideo = null
        videoW = 0
        videoH = 0
    }

    /** Разместить картинку в прямоугольнике dst (координаты этого слоя). */
    private val placed = RectF()

    fun place(dst: RectF) {
        if (width == 0 || height == 0) return
        if (placed == dst) return            // не дёргаем преобразование, пока положение не изменилось
        placed.set(dst)
        val m = Matrix()
        m.setRectToRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dst, Matrix.ScaleToFit.FILL)
        setTransform(m)
    }

    fun shutdown() {
        remote.onVideo = null
        player.stop()
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val s = Surface(st)
        surface = s
        player.setSurface(s)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        player.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
