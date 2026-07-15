package org.draken.usagi.favourites.ui.list

import android.graphics.Canvas
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.core.util.ext.getItem
import org.draken.usagi.list.domain.ListSortOrder
import org.draken.usagi.list.ui.adapter.MangaListAdapter
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.MangaListModel

class FavouritesReorderCallback(
	private val sortOrder: () -> ListSortOrder?,
	private val getAdapter: () -> MangaListAdapter?,
	private val getSelectedItemsIds: () -> Set<Long>,
	private val saveMangaOrder: (List<ListModel>) -> Unit,
	private val onDragStateChanged: (isDragging: Boolean) -> Unit,
	private val canDrag: () -> Boolean = { true }
) : ItemTouchHelper.SimpleCallback(ARS, 0) {

	private val listSelect = mutableListOf<ListModel>()

	override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
		if (sortOrder() != ListSortOrder.NEWEST || !canDrag()) return 0
		val p = viewHolder.bindingAdapterPosition
		return if (p != RecyclerView.NO_POSITION && getAdapter()?.items?.getOrNull(p) is MangaListModel) ARS else 0
	}

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	): Boolean {
		if (viewHolder.itemViewType != target.itemViewType) return false
		val from = viewHolder.bindingAdapterPosition
		val to = target.bindingAdapterPosition
		if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

		val adapter = getAdapter() ?: return false
		val items = adapter.items ?: return false
		val used = getSelectedItemsIds()
		val moved = viewHolder.getItem(MangaListModel::class.java)?.id
		if (moved == null || moved !in used) {
			adapter.reorderItems(from, to)
			return true
		}
		val start = items.indexOfFirst { (it as? MangaListModel)?.id in used }
		val end = items.indexOfLast { (it as? MangaListModel)?.id in used }
		if (start == -1 || end == -1) return false
		if (to in start..end) return false
		if (to > end) {
			adapter.reorderItems(to, start)
			return true
		}
		adapter.reorderItems(to, end)
		return true
	}

	override fun canDropOver(
		recyclerView: RecyclerView,
		current: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	) = current.itemViewType == target.itemViewType

	override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
		super.onSelectedChanged(viewHolder, actionState)
		onDragStateChanged(actionState != ItemTouchHelper.ACTION_STATE_IDLE)
		if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || viewHolder == null) return
		listSelect.clear()
		val rv = viewHolder.itemView.parent as? RecyclerView ?: return
		val used = getSelectedItemsIds()
		val moved = viewHolder.getItem(MangaListModel::class.java)?.id ?: return
		val adapter = getAdapter() ?: return
		val items = adapter.items?.toMutableList() ?: return
		val i = items.indexOfFirst { (it as? MangaListModel)?.id == moved }
		if (i != -1) {
			val selected = items.filter { (it as? MangaListModel)?.id in used }
			val rest = items.filterNot { (it as? MangaListModel)?.id in used }.toMutableList()
			val insert = items.take(i).count { it !is MangaListModel || it.id !in used }
			rest.addAll(insert, selected)
			listSelect.addAll(selected)
			adapter.updateItems(rest)
		}
		for (i in 0 until rv.childCount) {
			val child = rv.getChildAt(i)
			TooltipCompat.setTooltipText(child, null)
		}
	}

	override fun onChildDraw(
		c: Canvas, rV: RecyclerView, viewHolder: RecyclerView.ViewHolder,
		dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
	) {
		super.onChildDraw(c, rV, viewHolder, dX, dY, actionState, isCurrentlyActive)
		if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return
		val moved = viewHolder.getItem(MangaListModel::class.java)?.id ?: return
		val used = getSelectedItemsIds()
		if (moved !in used) return
		for (i in 0 until rV.childCount) {
			val child = rV.getChildAt(i)
			val holder = rV.getChildViewHolder(child)
			if (holder == viewHolder) continue
			val item = holder.getItem(MangaListModel::class.java) ?: continue
			if (item.id !in used) continue
			child.translationX = dX; child.translationY = dY
		}
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		super.clearView(recyclerView, viewHolder)
		val used = getSelectedItemsIds()
		for (i in 0 until recyclerView.childCount) {
			val child = recyclerView.getChildAt(i)
			child.translationX = 0f; child.translationY = 0f
			val item = recyclerView.getChildViewHolder(child).getItem(MangaListModel::class.java)
			TooltipCompat.setTooltipText(child, item?.getSummary(recyclerView.context))
		}
		val adapter = getAdapter() ?: run { listSelect.clear(); return }
		val items = adapter.items?.toMutableList() ?: run { listSelect.clear(); return }
		val moved = viewHolder.getItem(MangaListModel::class.java)?.id?.takeIf { it in used }
		if (moved != null) {
			val i = items.indexOfFirst { (it as? MangaListModel)?.id == moved }
			if (i != -1) {
				val selected = listSelect.ifEmpty { items.filter { (it as? MangaListModel)?.id in used } }
				val rest = items.filterNot { (it as? MangaListModel)?.id in used }.toMutableList()
				val insert = items.take(i).count { it !is MangaListModel || it.id !in used }
				rest.addAll(insert, selected)
				adapter.updateItems(rest)
				saveMangaOrder(rest)
				listSelect.clear()
				return
			}
		}
		saveMangaOrder(items)
		listSelect.clear()
	}

	override fun isLongPressDragEnabled() = false

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

	companion object {
		private const val ARS = ItemTouchHelper.DOWN or ItemTouchHelper.UP or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
	}
}
