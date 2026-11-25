/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

private const val TEXT_SIZE_DP = 9f
private const val PILL_HEIGHT_PER_TEXT = 1.35f

/**
 * An extension's icon with its badge text over the bottom end, the way its toolbar button showed it.
 *
 * The menu cannot show the badge as a separate end icon: in browser-menu2 160 a start and an end icon share one view
 * id, and the row's label then collapses to nothing.
 */
class BadgedIcon(
    private val icon: Drawable,
    private val text: String,
    @ColorInt textColor: Int,
    @ColorInt backgroundColor: Int,
    density: Float,
) : Drawable() {
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = TEXT_SIZE_DP * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val pill = RectF()

    override fun draw(canvas: Canvas) {
        icon.bounds = bounds
        icon.draw(canvas)

        val height = textPaint.textSize * PILL_HEIGHT_PER_TEXT
        val width = maxOf(height, textPaint.measureText(text) + height / 2).coerceAtMost(bounds.width().toFloat())
        pill.set(bounds.right - width, bounds.bottom - height, bounds.right.toFloat(), bounds.bottom.toFloat())
        canvas.drawRoundRect(pill, height / 2, height / 2, backgroundPaint)
        canvas.drawText(
            text,
            pill.centerX(),
            pill.centerY() - (textPaint.descent() + textPaint.ascent()) / 2,
            textPaint,
        )
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        textPaint.alpha = alpha
        backgroundPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    override fun getIntrinsicWidth() = icon.intrinsicWidth

    override fun getIntrinsicHeight() = icon.intrinsicHeight

    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
