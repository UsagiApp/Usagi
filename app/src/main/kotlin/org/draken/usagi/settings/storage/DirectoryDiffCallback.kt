package org.draken.usagi.settings.storage

import androidx.recyclerview.widget.DiffUtil.ItemCallback

class DirectoryDiffCallback : ItemCallback<DirectoryModel>() {
	override fun areItemsTheSame(
		oldItem: DirectoryModel,
		newItem: DirectoryModel,
	): Boolean = oldItem.file == newItem.file

	override fun areContentsTheSame(
		oldItem: DirectoryModel,
		newItem: DirectoryModel,
	): Boolean = oldItem == newItem

	override fun getChangePayload(
		oldItem: DirectoryModel,
		newItem: DirectoryModel,
	): Any? =
		if (oldItem.isChecked != newItem.isChecked) {
			Unit
		} else {
			super.getChangePayload(oldItem, newItem)
		}
}
