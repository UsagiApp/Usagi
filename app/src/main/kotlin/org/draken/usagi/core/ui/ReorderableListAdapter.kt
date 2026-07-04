package org.draken.usagi.core.ui

import androidx.recyclerview.widget.AsyncListDiffer.ListListener
import androidx.recyclerview.widget.DiffUtil
import com.hannesdorfmann.adapterdelegates4.AdapterDelegate
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withContext
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.model.ListHeader
import org.draken.usagi.list.ui.model.ListModel
import tsuki.util.move
import java.util.LinkedList

open class ReorderableListAdapter<T : ListModel> : ListDelegationAdapter<List<T>>(), FlowCollector<List<T>?> {

	private val listListeners = LinkedList<ListListener<T>>()

	override suspend fun emit(value: List<T>?) {
		val oldList = items.orEmpty()
		val newList = value.orEmpty()
		val diffResult = withContext(Dispatchers.Default) {
			val diffCallback = DiffCallback(oldList, newList)
			DiffUtil.calculateDiff(diffCallback)
		}
		super.setItems(newList)
		diffResult.dispatchUpdatesTo(this)
		listListeners.forEach { it.onCurrentListChanged(oldList, newList) }
	}

	@Deprecated(
		message = "Use emit() to dispatch list updates",
		level = DeprecationLevel.ERROR,
		replaceWith = ReplaceWith("emit(items)"),
	)
	override fun setItems(items: List<T>?) = super.setItems(items)

	fun reorderItems(oldPos: Int, newPos: Int) {
		val reordered = items?.toMutableList() ?: return
		reordered.move(oldPos, newPos)
		super.setItems(reordered)
		notifyItemMoved(oldPos, newPos)
	}

	fun updateItems(new: List<T>) {
		val old = items.orEmpty()
		val callback = DiffCallback(old, new)
		val result = DiffUtil.calculateDiff(callback)
		super.setItems(new)
		result.dispatchUpdatesTo(this)
		listListeners.forEach { it.onCurrentListChanged(old, new) }
	}

	fun addDelegate(type: ListItemType, delegate: AdapterDelegate<List<T>>): ReorderableListAdapter<T> {
		delegatesManager.addDelegate(type.ordinal, delegate)
		return this
	}

	fun findHeader(position: Int): ListHeader? {
		for (i in (0..position).reversed()) {
			val item = items.orEmpty().getOrNull(i) ?: continue
			if (item is ListHeader) return item
		}; return null
	}

	protected class DiffCallback<T : ListModel>(
		private val oldList: List<T>,
		private val newList: List<T>,
	) : DiffUtil.Callback() {

		override fun getOldListSize(): Int = oldList.size

		override fun getNewListSize(): Int = newList.size

		override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
			val oldItem = oldList[oldItemPosition]
			val newItem = newList[newItemPosition]
			return newItem.areItemsTheSame(oldItem)
		}

		override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
			val oldItem = oldList[oldItemPosition]
			val newItem = newList[newItemPosition]
			return newItem == oldItem
		}

		override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
			val oldItem = oldList[oldItemPosition]
			val newItem = newList[newItemPosition]
			return newItem.getChangePayload(oldItem)
		}
	}
}
