package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** The floating button: dark circle, 2×2 "apps" glyph, amber badge for new places. */
class BubbleView(context: Context) : View(context) {
    var badge: Int = 0
        set(value) { field = value; invalidate() }
    var pressedLook: Boolean = false
        set(value) { field = value; invalidate() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.AMBER }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val r = w / 2f
        bg.color = if (pressedLook) Palette.BLUE else Color.argb(235, 17, 24, 39)
        canvas.drawCircle(r, r, r - 1, bg)
        ring.strokeWidth = w * 0.04f
        canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
        val s = w * 0.15f
        val gap = w * 0.06f
        for (dx in listOf(-1, 1)) for (dy in listOf(-1, 1)) {
            val cx = r + dx * (s / 2 + gap / 2)
            val cy = r + dy * (s / 2 + gap / 2)
            canvas.drawRoundRect(RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), s * 0.25f, s * 0.25f, glyph)
        }
        if (badge > 0) {
            val br = w * 0.2f
            canvas.drawCircle(w - br, br, br, badgePaint)
            badgeText.textSize = br * 1.3f
            canvas.drawText(if (badge > 9) "9+" else badge.toString(), w - br, br + badgeText.textSize * 0.36f, badgeText)
        }
    }
}
