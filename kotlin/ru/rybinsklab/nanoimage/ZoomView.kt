package ru.rybinsklab.nanoimage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * Просмотр результата с щипковым масштабированием (1x…6x).
 *
 * Обычный ImageView не даёт приблизить, а на 1024px картинке мелкую деталь
 * или артефакт генерации иначе не разглядеть.
 */
@SuppressLint("ViewConstructor")
class ZoomView(ctx: Context) : ImageView(ctx) {

    private val matrix = Matrix()
    private var scaleFactor = 1f
    private var lastTouch: PointF? = null

    private val scaleDetector = ScaleGestureDetector(ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleFactor *= d.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1f, 6f)
                matrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
                imageMatrix = matrix
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    /** Сброс при новой картинке: иначе зум накапливается и изображение уезжает. */
    fun resetZoom() {
        val d = drawable ?: return
        val vw = width.takeIf { it > 0 } ?: 1
        val vh = height.takeIf { it > 0 } ?: 1
        val dw = d.intrinsicWidth.coerceAtLeast(1)
        val dh = d.intrinsicHeight.coerceAtLeast(1)
        val s = minOf(vw.toFloat() / dw, vh.toFloat() / dh)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
        scaleFactor = 1f
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        // Перетаскивание двумя пальцами, чтобы осмотреть углы.
        if (event.pointerCount == 2) {
            val x = (event.getX(0) + event.getX(1)) / 2f
            val y = (event.getY(0) + event.getY(1)) / 2f
            lastTouch?.let {
                matrix.postTranslate(x - it.x, y - it.y)
                imageMatrix = matrix
            }
            lastTouch = PointF(x, y)
        } else {
            lastTouch = null
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) resetZoom()
    }
}
