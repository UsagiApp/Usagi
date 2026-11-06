package org.draken.usagi.reader.ui.pager.curl

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Spread of two pages (items start, start + 1). The sheet is the right page, hinged on the spine, its back is the
 * left page of the next spread. Going back plays the turn of the previous spread in reverse.
 * Needs the neighbouring spread laid out, see DoublePageLayoutManager.prefetchFullSpread.
 */
class DoublePageCurlScene(
	private val rv: RecyclerView,
	private val scrollTo: (Int) -> Unit,
) : PageCurlScene {
	private var start = 0
	private var pageWidth = 0f
	private var baseLeft: View? = null
	private var baseRight: View? = null
	private var front: View? = null
	private var back: View? = null

	override val isVertical = false
	override val isAttachedToSpine = true
	override val creaseShadowOnly = true
	override val backTint = Color.TRANSPARENT

	// the sheet is half a screen wide: 1:1 keeps backward as sensitive as forward
	override val backwardGain = 1f

	override fun prepare(
		dir: Int,
		sheet: RectF,
	): CurlAvailability {
		val lm = rv.layoutManager as? LinearLayoutManager ?: return CurlAvailability.NONE
		val count = rv.adapter?.itemCount ?: return CurlAvailability.NONE
		val first = lm.findFirstVisibleItemPosition()
		if (first == RecyclerView.NO_POSITION) return CurlAvailability.BUSY
		val start = first and 1.inv()
		if (dir > 0 && start + 2 >= count) return CurlAvailability.NONE
		if (dir < 0 && start < 2) return CurlAvailability.NONE
		if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return CurlAvailability.BUSY
		val startView = lm.findViewByPosition(start) ?: return CurlAvailability.BUSY
		if (startView.left != 0) {
			// interrupted scroll: rest on the nearest spread, otherwise every gesture would be refused
			scrollTo(if (-startView.left > startView.width && start + 2 < count) start + 2 else start)
			return CurlAvailability.BUSY
		}
		// the turned sheet always belongs to spread s0 -> s0 + 2: front = s0 + 1, back = s0 + 2
		val s0 = if (dir > 0) start else start - 2
		val left = view(s0, count)
		val sheetFront = view(s0 + 1, count)
		val sheetBack = view(s0 + 2, count)
		if (left == null || sheetFront == null || sheetBack == null) return CurlAvailability.BUSY
		this.start = start
		pageWidth = startView.width.toFloat()
		baseLeft = left
		baseRight = view(s0 + 3, count)
		front = sheetFront
		back = sheetBack
		sheet.set(pageWidth, 0f, pageWidth * 2f, rv.height.toFloat())
		return CurlAvailability.OK
	}

	override fun drawBase(canvas: Canvas) {
		canvas.drawView(baseLeft)
		canvas.drawView(baseRight, pageWidth)
	}

	override fun drawFront(canvas: Canvas) = canvas.drawView(front, pageWidth)

	override fun drawBack(canvas: Canvas) {
		// the real back of the sheet: the left page of the next spread, mirrored over the sheet
		canvas.save()
		canvas.translate(pageWidth * 2f, 0f)
		canvas.scale(-1f, 1f)
		back?.draw(canvas)
		canvas.restore()
	}

	override fun commit(dir: Int) = scrollTo(start + 2 * dir)

	override fun release() {
		baseLeft = null
		baseRight = null
		front = null
		back = null
	}

	private fun view(
		position: Int,
		count: Int,
	): View? = if (position in 0 until count) rv.findViewHolderForAdapterPosition(position)?.itemView else null
}
