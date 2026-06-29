package org.draken.usagi.core.ui.list

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.core.ui.list.decor.AbstractSelectionItemDecoration
import org.draken.usagi.core.util.ext.toSet

class DragSelectionListener(
	private val controller: ListSelectionController,
	private val decoration: AbstractSelectionItemDecoration,
) : RecyclerView.OnItemTouchListener {

	private var start = RecyclerView.NO_POSITION
	private var current = RecyclerView.NO_POSITION
	private var min = RecyclerView.NO_POSITION
	private var max = RecyclerView.NO_POSITION
	private var x = 0f
	private var y = 0f
	private var scrollSpeed = 0
	private var isDragging = false
	private var isSelecting = true
	private var init = emptySet<Long>()
	private var recyclerView: RecyclerView? = null

	override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
		recyclerView = rv
		return when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> { x = e.x; y = e.y; isDragging = false; false }
			MotionEvent.ACTION_MOVE -> {
				x = e.x; y = e.y
				isDragging.also { if (it) handle(rv, e.x, e.y) }
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging.also { if (it) stop() }
			else -> false
		}
	}

	override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
		recyclerView = rv
		when (e.actionMasked) {
			MotionEvent.ACTION_MOVE -> {
				x = e.x; y = e.y
				if (isDragging) handle(rv, e.x, e.y)
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
		}
	}

	override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

	fun attach(rv: RecyclerView) {
		rv.addOnItemTouchListener(this)
		rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(v: View) = Unit
			override fun onViewDetachedFromWindow(v: View) = destroy()
		})
	}

	fun onSelectionStarted(view: View, isSelecting: Boolean = true) {
		val rv = recyclerView ?: return
		val pos = rv.findContainingItemView(view) ?.let { rv.getChildAdapterPosition(it) }
			?.takeIf { it != RecyclerView.NO_POSITION } ?: return
		isDragging = true
		start = pos; current = pos; min = pos; max = pos
		this.isSelecting = isSelecting; init = decoration.checkedItemsIds.toSet()
		rv.parent?.requestDisallowInterceptTouchEvent(true)
	}

	fun destroy() {
		recyclerView?.removeCallbacks(scrollRunnable)
		recyclerView = null
	}

	private fun handle(rv: RecyclerView, x: Float, y: Float) {
		val threshold = rv.resources.getDimensionPixelSize(R.dimen.list_spacing_normal) * 6
		scrollSpeed = when {
			y < threshold -> (-35f * (threshold - y) / threshold).toInt().coerceIn(-35, 0)
			y > rv.height - threshold -> (35f * (y - rv.height + threshold) / threshold).toInt().coerceIn(0, 35)
			else -> 0
		}
		rv.removeCallbacks(scrollRunnable)
		if (scrollSpeed != 0) rv.postOnAnimation(scrollRunnable)
		update(rv, x, y)
	}

	private fun update(rv: RecyclerView, x: Float, y: Float) {
		val pos = rv.findChildViewUnder(x, y)
			?.let { rv.getChildAdapterPosition(it) }
			?.takeIf { it != RecyclerView.NO_POSITION && it != current } ?: return

		current = pos
		min = minOf(min, pos); max = maxOf(max, pos)
		val adapter = rv.adapter?.takeIf { it.itemCount > 0 } ?: return
		val itemsList = adapter.getItemsList()
		val touch = minOf(start, current).coerceIn(0, adapter.itemCount - 1)
		val end = maxOf(start, current).coerceIn(0, adapter.itemCount - 1)
		for (i in min..max) {
			val id = getItem(rv, itemsList, i).takeIf { it != RecyclerView.NO_ID } ?: continue
			decoration.setItemIsChecked(id, if (i in touch..end) isSelecting else id in init)
		}
		controller.notifySelectionChanged()
	}

	private fun getItem(rv: RecyclerView, itemsList: List<*>?, position: Int): Long =
		rv.findViewHolderForAdapterPosition(position)
			?.let { decoration.getItemId(rv, it.itemView) }
			?.takeIf { it != RecyclerView.NO_ID }
			?: itemsList?.getOrNull(position)?.let { getModel(it) }
			?: RecyclerView.NO_ID

	private fun getModel(item: Any?): Long {
		item ?: return RecyclerView.NO_ID
		arrayOf("getId", "getMangaId", "getPageId")
			.firstNotNullOfOrNull { item.tryInvoke(it)?.asId() }?.let { return it }
		arrayOf("getChapter", "getCategory", "getPage", "getManga")
			.firstNotNullOfOrNull { name -> item.tryInvoke(name)?.let { it.tryInvoke("getId")?.asId() } }
			?.let { return it }
		return (item.tryInvoke("getName") as? String)?.hashCode()?.toLong() ?: RecyclerView.NO_ID
	}

	private fun stop() {
		isDragging = false
		recyclerView?.removeCallbacks(scrollRunnable)
		start = RecyclerView.NO_POSITION
		current = RecyclerView.NO_POSITION
	}

	private fun RecyclerView.Adapter<*>.getItemsList(): List<*>? = runCatching {
		javaClass.methods.firstOrNull { it.name == "getItems" && it.parameterCount == 0 }?.invoke(this) as? List<*>
	}.getOrNull()

	private fun Any.tryInvoke(name: String): Any? = runCatching {
		javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(this)
	}.getOrNull()

	private fun Any.asId(): Long? = when (this) {
		is Long -> this
		is Int -> toLong()
		is java.util.UUID -> mostSignificantBits
		else -> null
	}

	private val scrollRunnable = object : Runnable {
		override fun run() {
			val rv = recyclerView?.takeIf { isDragging && scrollSpeed != 0 } ?: return
			rv.scrollBy(0, scrollSpeed)
			update(rv, x, y)
			rv.postOnAnimation(this)
		}
	}
}
