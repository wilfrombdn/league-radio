package com.leagueradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small drawn down-chevron used as the stream picker's open button. */
class Chevron(ctx: Context, color: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 2 * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val w = 6 * d
        path.reset()
        path.moveTo(cx - w, cy - w / 2)
        path.lineTo(cx, cy + w / 2)
        path.lineTo(cx + w, cy - w / 2)
        c.drawPath(path, paint)
    }
}
