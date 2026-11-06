package org.draken.usagi.reader.ui.pager.curl

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/** One page per ViewPager2 item: the turned page is the sheet, its neighbour lies underneath. */
class PagerCurlScene(
	private val pager: ViewPager2,
) : PageCurlScene {
	private var front: View? = null
	private var under: View? = null

	override val isVertical get() = pager.orientation == ViewPager2.ORIENTATION_VERTICAL
	override val isAttachedToSpine = false
	override val creaseShadowOnly = false

	// the back of a single page is a faded copy of its front, like print showing through paper
	override val backTint = Color.argb(176, 242, 239, 230)
	override val backwardGain = 2f

	override fun prepare(
		dir: Int,
		sheet: RectF,
	): CurlAvailability {
		val count = pager.adapter?.itemCount ?: return CurlAvailability.NONE
		val page = pager.currentItem
		if (page + dir !in 0 until count) return CurlAvailability.NONE
		if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return CurlAvailability.BUSY
		val rv = pager.getChildAt(0) as? RecyclerView ?: return CurlAvailability.BUSY
		val now = rv.findViewHolderForAdapterPosition(page)?.itemView
		val other = rv.findViewHolderForAdapterPosition(page + dir)?.itemView
		if (now == null || other == null) return CurlAvailability.BUSY
		// going back: the previous page is the sheet and comes back over the current one
		front = if (dir > 0) now else other
		under = if (dir > 0) other else now
		sheet.set(0f, 0f, pager.width.toFloat(), pager.height.toFloat())
		return CurlAvailability.OK
	}

	override fun drawBase(canvas: Canvas) = canvas.drawView(under)

	override fun drawFront(canvas: Canvas) = canvas.drawView(front)

	override fun drawBack(canvas: Canvas) = canvas.drawView(front)

	override fun commit(dir: Int) = pager.setCurrentItem(pager.currentItem + dir, false)

	override fun release() {
		front = null
		under = null
	}
}
