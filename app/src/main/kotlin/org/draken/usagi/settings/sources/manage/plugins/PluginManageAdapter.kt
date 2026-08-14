package org.draken.usagi.settings.sources.manage.plugins

import android.annotation.SuppressLint
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.shape.CornerFamily
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
	onTachiyomiRenameClick: (PluginManageItem.Tachiyomi) -> Unit,
	onTachiyomiLongClick: (PluginManageItem.Tachiyomi) -> Unit,
	onTachiyomiClick: (PluginManageItem.Tachiyomi) -> Unit,
	onPreInstalledTachiyomiClick: (PluginManageItem.PreInstalledTachiyomi) -> Unit,
	onLongClick: (PluginManageItem.Plugin) -> Unit,
	onClick: (PluginManageItem.Plugin) -> Unit,
	isSelected: (PluginManageItem.Plugin) -> Boolean,
	isTachiyomiSelected: (PluginManageItem.Tachiyomi) -> Boolean,
) : BaseListAdapter<ListModel>() {
	init {
		addDelegate(ListItemType.CHAPTER_LIST, pluginItemDelegate(onRenameClick, onUpdateClick, onLongClick, onClick, isSelected))
		addDelegate(ListItemType.INFO, tachiyomiItemDelegate(onTachiyomiRenameClick, onTachiyomiLongClick, onTachiyomiClick, isTachiyomiSelected))
		addDelegate(ListItemType.TIP, preInstalledTachiyomiDelegate(onPreInstalledTachiyomiClick))
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
			val avatarUrl = item.repository?.takeIf { it.isNotBlank() }?.let(::githubAvatarUrl)
			val fallback = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.displayName)
			binding.imageViewIcon.errorDrawable = fallback
			binding.imageViewIcon.fallbackDrawable = fallback
			if (avatarUrl.isNullOrBlank()) {
				binding.imageViewIcon.setImageResource(R.drawable.ic_services)
			} else {
				applyRemoteIconShape(binding.imageViewIcon, context)
				binding.imageViewIcon.setImageAsync(avatarUrl)
			}
			binding.textViewTitle.text = item.displayName

			binding.textViewDescription.text =
				buildList {
					item.repository?.takeIf { it.isNotBlank() }?.let { add(repositoryLabel(it)) } ?: add(item.name)
					item.installedTag?.takeIf { it.isNotBlank() }?.let(::add)
				}.joinToString(" • ")
			binding.imageViewAdd.isVisible = item.hasUpdate
			binding.imageViewAdd.setOnClickListener(if (item.hasUpdate) View.OnClickListener { onUpdateClick(item) } else null)
		}
	}

	private fun tachiyomiItemDelegate(
		onRenameClick: (PluginManageItem.Tachiyomi) -> Unit,
		onLongClick: (PluginManageItem.Tachiyomi) -> Unit,
		onClick: (PluginManageItem.Tachiyomi) -> Unit,
		isSelected: (PluginManageItem.Tachiyomi) -> Boolean,
	) = adapterDelegateViewBinding<PluginManageItem.Tachiyomi, ListModel, ItemSourceConfigBinding>(
		{ layoutInflater, parent -> ItemSourceConfigBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.imageViewIcon.background = null
		binding.imageViewMenu.setImageResource(R.drawable.ic_edit)
		binding.imageViewMenu.contentDescription = context.getString(R.string.rename)

		binding.imageViewRemove.isVisible = false
		binding.imageViewAdd.isVisible = false
		itemView.setOnLongClickListener {
			onLongClick(item)
			true
		}
		itemView.setOnClickListener { onClick(item) }

		bind {
			itemView.isSelected = isSelected(item)
			applyRemoteIconShape(binding.imageViewIcon, context)
			val fallback = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.repositoryLabel)

			binding.imageViewIcon.errorDrawable = fallback
			binding.imageViewIcon.fallbackDrawable = fallback
			val avatarUrl = githubAvatarUrl(item.repositoryLabel)
			binding.imageViewIcon.setImageAsync(avatarUrl)

			binding.imageViewMenu.isVisible = true
			binding.imageViewMenu.setOnClickListener { onRenameClick(item) }

			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				buildList {
					add(item.repositoryLabel)
					add(context.getString(R.string.external_source))
					add(context.getString(R.string.tachiyomi_repository_extension_count, item.extensionCount, item.installedCount))
					if (item.hasFailures) add(context.getString(R.string.load_failed))
				}.joinToString(" • ")
		}
	}

	private fun preInstalledTachiyomiDelegate(
		onClick: (PluginManageItem.PreInstalledTachiyomi) -> Unit,
	) = adapterDelegateViewBinding<PluginManageItem.PreInstalledTachiyomi, ListModel, ItemSourceConfigBinding>(
		{ layoutInflater, parent -> ItemSourceConfigBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.imageViewIcon.background = null
		binding.imageViewMenu.isVisible = false
		binding.imageViewRemove.isVisible = false
		binding.imageViewAdd.isVisible = false
		itemView.setOnLongClickListener(null)
		itemView.setOnClickListener { onClick(item) }

		bind {
			itemView.isSelected = false
			binding.imageViewIcon.setImageResource(R.drawable.ic_tachiyomi_extension_package)
			binding.textViewTitle.setText(R.string.tachiyomi_preinstalled_extension)
			binding.textViewDescription.text =
				context.resources.getQuantityString(
					R.plurals.tachiyomi_preinstalled_extension_summary,
					item.extensionCount,
					item.extensionCount,
				)
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

	private fun githubAvatarUrl(repository: String): String? {
		val owner = repositoryLabel(repository).substringBefore('/').trim()
		return owner.takeIf { it.isNotBlank() }?.let { "https://github.com/$it.png" }
	}

	private fun applyRemoteIconShape(
		imageView: org.draken.usagi.core.ui.image.FaviconView,
		context: android.content.Context,
	) {
		imageView.shapeAppearanceModel =
			imageView.shapeAppearanceModel
				.toBuilder()
				.setAllCorners(CornerFamily.ROUNDED, context.resources.getDimension(R.dimen.margin_small))
				.build()
	}

	private fun repositoryLabel(repository: String): String =
		repository
			.trim()
			.removeSuffix("/")
			.removePrefix("https://github.com/")
			.removePrefix("http://github.com/")
			.removePrefix("github.com/")
}
