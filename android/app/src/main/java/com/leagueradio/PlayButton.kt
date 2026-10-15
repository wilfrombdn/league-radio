package com.leagueradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Round anti-aliased play/stop button drawn with vector shapes, with a smooth press animation. */
class PlayButton(ctx: Context) : View(ctx) {
    var playing = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#101010") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * d
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; strokeWidth = 4 * d
    }
    private val path = Path()

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        if (playing) {
            val h = r * 0.26f
            c.drawRoundRect(RectF(cx - h, cy - h, cx + h, cy + h), 3 * d, 3 * d, icon)
        } else {
            val h = r * 0.30f
            val x0 = cx - r * 0.16f
            path.reset()
            path.moveTo(x0, cy - h)
            path.lineTo(x0, cy + h)
            path.lineTo(x0 + r * 0.50f, cy)
            path.close()
            c.drawPath(path, icon)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
