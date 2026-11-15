package org.draken.usagi.reader.ui.pager.doublepage

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DoublePageLayoutManager(
	context: Context,
	attrs: AttributeSet?,
	defStyleAttr: Int,
	defStyleRes: Int,
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {
	/**
	 * Lay out one more spread on each side (4 pages instead of 2 half pages). The page curl draws the
	 * neighbouring spread live, so its pages have to exist even though they are off screen.
	 */
	var prefetchFullSpread = false

	override fun checkLayoutParams(lp: RecyclerView.LayoutParams?): Boolean {
		lp?.width = (width + 1) / 2
		return super.checkLayoutParams(lp)
	}

	override fun calculateExtraLayoutSpace(
		state: RecyclerView.State,
		extraLayoutSpace: IntArray,
	) {
		val offscreenSpace = if (prefetchFullSpread) (width + 1) / 2 * 2 else (width + 1) / 2
		extraLayoutSpace[0] = offscreenSpace
		extraLayoutSpace[1] = offscreenSpace
	}
}
