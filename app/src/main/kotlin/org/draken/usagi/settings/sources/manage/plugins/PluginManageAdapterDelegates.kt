package org.draken.usagi.settings.sources.manage.plugins

import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceConfigBinding

fun pluginItemDelegate(
	onDeleteClick: (PluginManageItem.Plugin) -> Unit,
	onUpdateClick: (PluginManageItem.Plugin) -> Unit,
) = adapterDelegateViewBinding<PluginManageItem.Plugin, PluginManageItem, ItemSourceConfigBinding>(
	{ layoutInflater, parent -> ItemSourceConfigBinding.inflate(layoutInflater, parent, false) },
) {

	binding.imageViewIcon.setImageResource(R.drawable.ic_services)
	binding.imageViewIcon.background = null
	binding.imageViewMenu.isGone = true
	binding.imageViewRemove.isVisible = true
	binding.imageViewRemove.setImageResource(R.drawable.ic_delete)
	binding.imageViewRemove.contentDescription = context.getString(R.string.delete)
	binding.imageViewAdd.setImageResource(R.drawable.ic_download)
	binding.imageViewAdd.contentDescription = context.getString(R.string.update)

	bind {
		binding.textViewTitle.text = item.displayName
		
		val parts = ArrayList<String>(3)
		item.repository?.takeIf { it.isNotBlank() }?.let(parts::add)
		item.installedTag?.takeIf { it.isNotBlank() }?.let(parts::add)
		if (item.hasUpdate) {
			item.latestTag?.takeIf { it.isNotBlank() }?.let { parts.add("-> $it") }
		}
		binding.textViewDescription.text = if (parts.isEmpty()) item.jarName else parts.joinToString(" • ")

		binding.imageViewRemove.setOnClickListener { onDeleteClick(item) }
		binding.imageViewAdd.isVisible = item.hasUpdate
		binding.imageViewAdd.setOnClickListener(
			if (item.hasUpdate) View.OnClickListener { onUpdateClick(item) } else null,
		)
	}
}

fun pluginPlaceholderDelegate() = adapterDelegateViewBinding<PluginManageItem.Placeholder, PluginManageItem, ItemEmptyHintBinding>(
	{ layoutInflater, parent -> ItemEmptyHintBinding.inflate(layoutInflater, parent, false) },
) {
	binding.icon.setImageResource(R.drawable.ic_empty_feed)

	bind {
		binding.textPrimary.setText(item.titleResId)
		binding.textSecondary.setTextAndVisible(item.summaryResId ?: 0)
	}
}
