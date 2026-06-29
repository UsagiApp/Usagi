package org.draken.usagi.favourites.ui.list

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.list.domain.ListSortOrder

class FavouritesTouchListener(
	private val sortOrder: () -> ListSortOrder?,
	private val reorderHelper: () -> ItemTouchHelper?,
	private val canDrag: () -> Boolean = { true }
) : RecyclerView.OnItemTouchListener {

	private var dX = 0f
	private var dY = 0f
	private var downTime = 0L
	private var downHolder: RecyclerView.ViewHolder? = null
	private var slopSq = 0

	override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
		if (sortOrder() != ListSortOrder.NEWEST || !canDrag()) return false
		when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				dX = e.x; dY = e.y
				downTime = System.currentTimeMillis()
				slopSq = ViewConfiguration.get(rv.context).scaledTouchSlop.let { it * it }
				downHolder = rv.findChildViewUnder(e.x, e.y)?.let(rv::getChildViewHolder)
			}
			MotionEvent.ACTION_MOVE -> {
				val holder = downHolder ?: return false
				val x = e.x - dX
				val y = e.y - dY
				if (x * x + y * y > slopSq) {
					val elapsed = System.currentTimeMillis() - downTime
					if (elapsed in 150..499) reorderHelper()?.startDrag(holder)
					downHolder = null
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downHolder = null
		}
		return false
	}

	override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

	override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
}
