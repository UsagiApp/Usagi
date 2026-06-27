package org.draken.usagi.settings.sources.manage.plugins

import android.annotation.SuppressLint
import android.view.View
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceConfigBinding
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem

class PluginManageAdapter(
	onRenameClick: (PluginManageItem.Plugin) -> Unit,
	onUpdateClick: (PluginManageItem.Plugin) -> Unit,
	onLongClick: (PluginManageItem.Plugin) -> Unit,
	onClick: (PluginManageItem.Plugin) -> Unit,
	isSelected: (PluginManageItem.Plugin) -> Boolean,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(
			ListItemType.CHAPTER_LIST,
			pluginItemDelegate(onRenameClick, onUpdateClick, onLongClick, onClick, isSelected)
		)
		addDelegate(ListItemType.HINT_EMPTY, pluginPlaceholderDelegate())
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun pluginItemDelegate(
		onRenameClick: (PluginManageItem.Plugin) -> Unit,
		onUpdateClick: (PluginManageItem.Plugin) -> Unit,
		onLongClick: (PluginManageItem.Plugin) -> Unit,
		onClick: (PluginManageItem.Plugin) -> Unit,
		isSelected: (PluginManageItem.Plugin) -> Boolean,
	) = adapterDelegateViewBinding<PluginManageItem.Plugin, ListModel, ItemSourceConfigBinding>(
		{ layoutInflater, parent -> ItemSourceConfigBinding.inflate(layoutInflater, parent, false) },
	) {

		binding.imageViewIcon.setImageResource(R.drawable.ic_services)
		binding.imageViewIcon.background = null
		binding.imageViewMenu.isVisible = true
		binding.imageViewMenu.setImageResource(R.drawable.ic_edit)
		binding.imageViewMenu.contentDescription = context.getString(R.string.rename)
		binding.imageViewMenu.setOnClickListener { onRenameClick(item) }
		binding.imageViewMenu.setOnTouchListener(null)
		binding.imageViewRemove.isVisible = false
		binding.imageViewRemove.setOnTouchListener(null)
		binding.imageViewRemove.setOnClickListener(null)
		binding.imageViewAdd.setImageResource(R.drawable.ic_download)
		binding.imageViewAdd.contentDescription = context.getString(R.string.update)

		itemView.setOnLongClickListener {
			onLongClick(item)
			true
		}
		itemView.setOnClickListener {
			onClick(item)
		}

		bind {
			itemView.isSelected = isSelected(item)
			binding.textViewTitle.text = item.displayName
			val parts = ArrayList<String>(3)
			item.repository?.takeIf { it.isNotBlank() }?.let(parts::add)
			item.installedTag?.takeIf { it.isNotBlank() }?.let(parts::add)
			binding.textViewDescription.text = if (parts.isEmpty()) item.name else parts.joinToString(" • ")
			binding.imageViewAdd.isVisible = item.hasUpdate
			binding.imageViewAdd.setOnClickListener(
				if (item.hasUpdate) View.OnClickListener { onUpdateClick(item) } else null,
			)
		}
	}

	private fun pluginPlaceholderDelegate() = adapterDelegateViewBinding<PluginManageItem.Placeholder, ListModel, ItemEmptyHintBinding>(
		{ layoutInflater, parent -> ItemEmptyHintBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.icon.setImageResource(R.drawable.ic_empty_feed)
		bind {
			binding.textPrimary.setText(item.titleResId)
			binding.textSecondary.setTextAndVisible(item.summaryResId ?: 0)
		}
	}
}
