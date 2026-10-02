package com.focusledger.timer

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.LineBackgroundSpan

/**
 * A slim progress bar under a task's figures line: time spent against the
 * estimate.
 *
 *   blue    fills by the share of the estimate used
 *   yellow  full width once past the estimate \u2014 the schedule may need moving
 *   red     full width when there's no estimate to measure against
 *
 * **Under the text, never behind it.** Tinting the whole line was tried on
 * paper first: even at 30% strength, yellow behind the amber "doing" text
 * fell to 1.85 contrast and blue behind mint to 2.2 \u2014 unreadable in daylight.
 * A strip below the glyphs leaves the text exactly as legible as before.
 *
 * **On a dark track.** Drawn straight onto a green row bar, blue and red
 * managed only 2\u20132.7 contrast. Drawn on a dark track first they reach 7.6
 * and 5.9, whatever the row beneath is doing.
 *
 * It's a paragraph span, so it must cover whole paragraphs: on the main row
 * that's the marker-and-figures line up to and including its newline.
 */
class TaskBarSpan(
    private val fraction: Float,
    private val fillColour: Int,
    private val heightPx: Float
) : LineBackgroundSpan {

    override fun drawBackground(
        canvas: Canvas, paint: Paint,
        left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val oldColour = paint.color
        val oldStyle = paint.style
        paint.style = Paint.Style.FILL

        val y1 = bottom.toFloat()
        val y0 = y1 - heightPx

        paint.color = TRACK
        canvas.drawRect(left.toFloat(), y0, right.toFloat(), y1, paint)

        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            paint.color = fillColour
            canvas.drawRect(left.toFloat(), y0, left + (right - left) * f, y1, paint)
        }

        paint.color = oldColour
        paint.style = oldStyle
    }

    companion object {
        const val TRACK = 0xFF0B1513.toInt()
        const val BLUE = 0xFF6FAFC4.toInt()
        const val YELLOW = 0xFFF2D94E.toInt()
        const val RED = 0xFFE0705F.toInt()

        /** The bar for a task: how far through its estimate it is. */
        fun forTask(actualMinutes: Int, estimateMinutes: Int, heightPx: Float): TaskBarSpan =
            when {
                estimateMinutes <= 0 -> TaskBarSpan(1f, RED, heightPx)
                actualMinutes > estimateMinutes -> TaskBarSpan(1f, YELLOW, heightPx)
                else -> TaskBarSpan(actualMinutes.toFloat() / estimateMinutes, BLUE, heightPx)
            }
    }
}
