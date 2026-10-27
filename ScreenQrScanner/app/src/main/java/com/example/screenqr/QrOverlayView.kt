package com.example.screenqr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 红色画框View：识别到二维码后在周围画红框。
 */
class QrOverlayView(context: Context) : View(context) {

    private val borderPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private var rect: RectF? = null

    fun setRect(left: Float, top: Float, right: Float, bottom: Float) {
        rect = RectF(left, top, right, bottom)
        invalidate()
    }

    fun clearRect() {
        rect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect?.let {
            canvas.drawRect(it, borderPaint)
        }
    }
}
