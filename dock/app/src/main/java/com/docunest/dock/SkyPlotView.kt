package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws the satellites ([GpsDoctor.sats]) on a sky circle: centre = straight
 * up, rim = horizon, north up. Colour = system, filled + white edge = used in
 * the fix, dashed outline = heard but not used, brightness = signal.
 */
object SkyPainter {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(140, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [cx], [cy] centre, [r] horizon radius, [density] for line widths, [rotationDeg] map rotation. */
    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, density: Float, sats: List<Sky.Sat>, background: Boolean, rotationDeg: Float = 0f) {
        if (background) {
            shade.color = Color.argb(150, 11, 18, 32)
            c.drawCircle(cx, cy, r, shade)
        }
        ring.strokeWidth = 1.2f * density
        for (el in listOf(0f, 30f, 60f)) c.drawCircle(cx, cy, r * (90f - el) / 90f, ring)
        text.textSize = 11f * density
        for ((label, az) in listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)) {
            val (x, y) = Sky.toXY(az, -8f, r, rotationDeg)
            c.drawText(label, cx + x - text.measureText(label) / 2, cy + y + text.textSize / 3, text)
        }
        // Weak first, used last: the used ones stay on top.
        for (s in sats.sortedWith(compareBy({ it.used }, { it.cn0 }))) {
            val (x, y) = Sky.toXY(s.az, s.el, r, rotationDeg)
            val size = (3.5f + (s.cn0.coerceIn(10f, 45f) - 10f) / 35f * 4.5f) * density
            val alpha = (90 + (s.cn0.coerceIn(10f, 45f) - 10f) / 35f * 165).toInt()
            val col = Sky.color(s.system)
            if (s.used) {
                dot.color = Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col))
                c.drawCircle(cx + x, cy + y, size, dot)
                edge.color = Color.WHITE
                edge.pathEffect = null
                edge.strokeWidth = 1.5f * density
                c.drawCircle(cx + x, cy + y, size, edge)
            } else {
                edge.color = Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col))
                edge.pathEffect = DashPathEffect(floatArrayOf(2f * density, 2f * density), 0f)
                edge.strokeWidth = 1.8f * density
                c.drawCircle(cx + x, cy + y, size, edge)
            }
            if (s.strong) {
                text.textSize = 9f * density
                c.drawText("${s.svid}", cx + x + size + 1.5f * density, cy + y + 3f * density, text)
            }
        }
    }
}

/** The sky plot on the GPS doctor screen. */
class SkyPlotView(context: Context) : View(context) {
    var sats: List<Sky.Sat> = emptyList()
        set(value) { field = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = minOf(w, (resources.displayMetrics.density * 260).toInt())
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = width / 2f - 14f * d
        SkyPainter.draw(canvas, width / 2f, height / 2f, r, d, sats, background = true)
    }
}

/**
 * The same satellites drawn on the dock's map around the van (north-up like the map), so you see
 * which part of the sky the good ones are in — and which side is blocked.
 */
class SkyMapOverlay(private val where: () -> org.osmdroid.util.GeoPoint?) : Overlay() {
    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val sats = GpsDoctor.sats
        val at = where() ?: return
        if (sats.isEmpty()) return
        val p = map.projection.toPixels(at, null)
        val d = map.context.resources.displayMetrics.density
        val r = minOf(map.width, map.height) * 0.26f
        SkyPainter.draw(c, p.x.toFloat(), p.y.toFloat(), r, d, sats, background = false, rotationDeg = map.mapOrientation)
    }
}
