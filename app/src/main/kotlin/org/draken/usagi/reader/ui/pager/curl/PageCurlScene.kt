package org.draken.usagi.reader.ui.pager.curl

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.withSave

/**
 * What a page turn is made of; views are drawn live in host coordinates.
 *
 * The sheet is always the page that turns forward (free edge right / bottom, hinged on the opposite side).
 * Turning backward plays the same sheet in reverse. `dir`: +1 = next page in layout order, -1 = previous.
 */
interface PageCurlScene {
	val isVertical: Boolean

	/** Sheet bound to a spine (book): the crease must never reach it. */
	val isAttachedToSpine: Boolean

	/** Only a thin shadow along the crease: no tint, no wide shading (real back face). */
	val creaseShadowOnly: Boolean

	/** ARGB tint over the back face, alpha 0 when the back face is real content. */
	val backTint: Int

	/** Sheet movement per unit of finger travel when turning backward (forward is always 1:1). */
	val backwardGain: Float

	/** Looks up the views and fills [sheet] with the resting place of the turned sheet. */
	fun prepare(
		dir: Int,
		sheet: RectF,
	): CurlAvailability

	/** Everything under the sheet. */
	fun drawBase(canvas: Canvas)

	/** Front face of the sheet, flat, at its resting place. */
	fun drawFront(canvas: Canvas)

	/** Back face of the sheet drawn flat over its resting place (already mirrored). */
	fun drawBack(canvas: Canvas)

	fun commit(dir: Int)

	fun release()
}

internal fun Canvas.drawView(
	view: View?,
	x: Float = 0f,
) {
	view ?: return
	withSave {
		translate(x, 0f)
		view.draw(this)
	}
}
