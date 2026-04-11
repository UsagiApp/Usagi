package org.draken.usagi.settings.sources.manage.plugins

import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.databinding.ItemSourceConfigBinding

class PluginViewHolder(
	private val binding: ItemSourceConfigBinding,
	private val onDeleteClick: (PluginManageItem.Plugin) -> Unit,
	private val onUpdateClick: (PluginManageItem.Plugin) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

	init {
		with(binding) {
			imageViewIcon.setImageResource(R.drawable.ic_services)
			imageViewIcon.background = null
			imageViewMenu.isGone = true
			imageViewRemove.isVisible = true
			imageViewRemove.setImageResource(R.drawable.ic_delete)
			imageViewRemove.contentDescription = root.context.getString(R.string.delete)
			imageViewAdd.setImageResource(R.drawable.ic_download)
			imageViewAdd.contentDescription = root.context.getString(R.string.update)
		}
	}

	fun bind(item: PluginManageItem.Plugin) {
		with(binding) {
			textViewTitle.text = item.displayName
			textViewDescription.text = buildSummary(item)
			imageViewRemove.setOnClickListener { onDeleteClick(item) }
			imageViewAdd.isVisible = item.hasUpdate
			imageViewAdd.setOnClickListener(
				if (item.hasUpdate) View.OnClickListener { onUpdateClick(item) } else null,
			)
		}
	}

	private fun buildSummary(item: PluginManageItem.Plugin): String {
		val parts = ArrayList<String>(3)
		item.repository?.takeIf { it.isNotBlank() }?.let(parts::add)
		item.installedTag?.takeIf { it.isNotBlank() }?.let(parts::add)
		if (item.hasUpdate) {
			item.latestTag?.takeIf { it.isNotBlank() }?.let { parts.add("-> $it") }
		}
		return if (parts.isEmpty()) item.jarName else parts.joinToString(" • ")
	}
}
