package org.draken.usagi.settings.sources.manage.plugins

import android.annotation.SuppressLint
import android.view.View
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceConfigBinding
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem

class PluginManageAdapter(
	onRenameClick: (PluginManageItem.Plugin) -> Unit,
	onUpdateClick: (PluginManageItem.Plugin) -> Unit,
	onTachiyomiClick: (PluginManageItem.Tachiyomi) -> Unit,
	onTachiyomiRemoveClick: (PluginManageItem.Tachiyomi) -> Unit,
	onLongClick: (PluginManageItem.Plugin) -> Unit,
	onClick: (PluginManageItem.Plugin) -> Unit,
	isSelected: (PluginManageItem.Plugin) -> Boolean,
) : BaseListAdapter<ListModel>() {
	init {
		addDelegate(ListItemType.CHAPTER_LIST, pluginItemDelegate(onRenameClick, onUpdateClick, onLongClick, onClick, isSelected))
		addDelegate(ListItemType.INFO, tachiyomiItemDelegate(onTachiyomiClick, onTachiyomiRemoveClick))
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
		binding.imageViewRemove.isVisible = false
		binding.imageViewAdd.setImageResource(R.drawable.ic_download)
		binding.imageViewAdd.contentDescription = context.getString(R.string.update)

		itemView.setOnLongClickListener {
			onLongClick(item)
			true
		}
		itemView.setOnClickListener { onClick(item) }

		bind {
			itemView.isSelected = isSelected(item)
			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				buildList {
					item.repository?.takeIf { it.isNotBlank() }?.let(::add)
					item.installedTag?.takeIf { it.isNotBlank() }?.let(::add)
				}.ifEmpty { listOf(item.name) }.joinToString(" • ")
			binding.imageViewAdd.isVisible = item.hasUpdate
			binding.imageViewAdd.setOnClickListener(if (item.hasUpdate) View.OnClickListener { onUpdateClick(item) } else null)
		}
	}

	private fun tachiyomiItemDelegate(
		onClick: (PluginManageItem.Tachiyomi) -> Unit,
		onRemoveClick: (PluginManageItem.Tachiyomi) -> Unit,
	) = adapterDelegateViewBinding<PluginManageItem.Tachiyomi, ListModel, ItemSourceConfigBinding>(
		{ layoutInflater, parent -> ItemSourceConfigBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.imageViewIcon.background = null
		binding.imageViewMenu.setImageResource(R.drawable.ic_delete)
		binding.imageViewMenu.contentDescription = context.getString(R.string.delete)
		binding.imageViewAdd.setImageResource(R.drawable.ic_download)
		binding.imageViewRemove.isVisible = false
		itemView.setOnLongClickListener(null)
		itemView.setOnClickListener(null)

		bind {
			val fallback = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.artifact.packageName)
			binding.imageViewIcon.errorDrawable = fallback
			binding.imageViewIcon.fallbackDrawable = fallback
			if (item.artifact.iconUrl.isNullOrBlank()) {
				binding.imageViewIcon.setImageDrawable(fallback)
			} else {
				binding.imageViewIcon.setImageAsync(item.artifact.iconUrl)
			}
			binding.imageViewMenu.isVisible = true
			binding.imageViewMenu.setOnClickListener { onRemoveClick(item) }
			binding.imageViewAdd.isVisible = item.isCompatible && (!item.isInstalled || item.hasUpdate)
			binding.imageViewAdd.contentDescription = context.getString(if (item.hasUpdate) R.string.update else R.string.add)
			binding.imageViewAdd.setOnClickListener(if (binding.imageViewAdd.isVisible) View.OnClickListener { onClick(item) } else null)
			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				buildList {
					add(item.sourceSummary.ifBlank { context.getString(R.string.load_failed) })
					add(item.artifact.packageName)
					if (!item.isCompatible) add("incompatible")
					item.errorMessage?.let(::add)
				}.joinToString(" • ")
		}
	}

	private fun pluginPlaceholderDelegate() =
		adapterDelegateViewBinding<PluginManageItem.Placeholder, ListModel, ItemEmptyHintBinding>(
			{ layoutInflater, parent -> ItemEmptyHintBinding.inflate(layoutInflater, parent, false) },
		) {
			binding.icon.setImageResource(R.drawable.ic_empty_feed)
			bind {
				binding.textPrimary.setText(item.titleResId)
				binding.textSecondary.setTextAndVisible(item.summaryResId ?: 0)
			}
		}
}
