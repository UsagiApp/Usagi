package org.draken.usagi.list.ui

import androidx.recyclerview.widget.DiffUtil
import org.draken.usagi.list.ui.model.ListModel

open class ListModelDiffCallback<T : ListModel> : DiffUtil.ItemCallback<T>() {
	override fun areItemsTheSame(
		oldItem: T,
		newItem: T,
	): Boolean = oldItem.areItemsTheSame(newItem)

	override fun areContentsTheSame(
		oldItem: T,
		newItem: T,
	): Boolean = oldItem == newItem

	override fun getChangePayload(
		oldItem: T,
		newItem: T,
	): Any? = newItem.getChangePayload(oldItem)

	companion object : ListModelDiffCallback<ListModel>() {
		val PAYLOAD_CHECKED_CHANGED = Any()
		val PAYLOAD_NESTED_LIST_CHANGED = Any()
		val PAYLOAD_PROGRESS_CHANGED = Any()
		val PAYLOAD_ANYTHING_CHANGED = Any()
	}
}
