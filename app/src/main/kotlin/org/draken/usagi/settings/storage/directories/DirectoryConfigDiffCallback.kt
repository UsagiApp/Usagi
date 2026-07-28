package org.draken.usagi.settings.storage.directories

import androidx.recyclerview.widget.DiffUtil.ItemCallback

class DirectoryConfigDiffCallback : ItemCallback<DirectoryConfigModel>() {
	override fun areItemsTheSame(
		oldItem: DirectoryConfigModel,
		newItem: DirectoryConfigModel,
	): Boolean = oldItem.path == newItem.path

	override fun areContentsTheSame(
		oldItem: DirectoryConfigModel,
		newItem: DirectoryConfigModel,
	): Boolean = oldItem == newItem

	override fun getChangePayload(
		oldItem: DirectoryConfigModel,
		newItem: DirectoryConfigModel,
	): Any? =
		if (oldItem.isDefault != newItem.isDefault) {
			Unit
		} else {
			super.getChangePayload(oldItem, newItem)
		}
}
