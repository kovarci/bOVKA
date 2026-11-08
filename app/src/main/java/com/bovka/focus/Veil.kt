package com.bovka.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Calque plein écran, transparent, qui peint en noir opaque les rectangles demandés.
 * Il laisse passer les touches : messages, stories et défilement restent utilisables.
 */
class VeilView(ctx: Context) : View(ctx) {
    private val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private var rects: List<Rect> = emptyList()

    /** Remplace les rectangles ; ne redessine que si quelque chose a changé. */
    fun setRects(next: List<Rect>) {
        if (next == rects) return
        rects = ArrayList(next)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (r in rects) canvas.drawRect(r, paint)
    }
}
