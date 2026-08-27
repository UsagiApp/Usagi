package org.draken.usagi.settings.sources.catalog

import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.util.ext.drawableStart
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.core.util.ext.getThemeDimensionPixelOffset
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceCatalogBinding
import org.draken.usagi.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

fun sourceCatalogItemSourceAD(listener: OnListItemClickListener<SourceCatalogItem.Source>) =
	adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
		{ inf, p -> ItemSourceCatalogBinding.inflate(inf, p, false) },
	) {
		binding.imageViewAdd.setOnClickListener { v -> listener.onItemLongClick(item, v) }
		binding.root.setOnClickListener { v -> listener.onItemClick(item, v) }
		val pad =
			context.getThemeDimensionPixelOffset(
				appcompatR.attr.listPreferredItemPaddingEnd,
				binding.root.paddingStart,
			)
		val margin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
		binding.root.updatePaddingRelative(end = (pad - margin).coerceAtLeast(0))
		bind {
			binding.textViewTitle.text = item.source.getTitle(context)
			binding.textViewDescription.text = item.description(context)
			binding.textViewDescription.drawableStart =
				if (item.source.isBroken) {
					ContextCompat.getDrawable(context, R.drawable.ic_off_small)
				} else {
					null
				}
			binding.imageViewIcon.setImageAsync(item.source)
			binding.imageViewAdd.isVisible = true
			binding.imageViewAdd.setImageResource(R.drawable.ic_add)
			binding.imageViewAdd.contentDescription = context.getString(R.string.add)
		}
	}

fun sourceCatalogItemExtensionAD(
	onClick: (SourceCatalogItem.Extension, android.view.View) -> Unit,
	onInstall: (SourceCatalogItem.Extension, android.view.View) -> Unit,
	onUninstall: (SourceCatalogItem.Extension, android.view.View) -> Unit,
	onSideload: (SourceCatalogItem.Extension, android.view.View) -> Unit,
) = adapterDelegateViewBinding<SourceCatalogItem.Extension, ListModel, ItemSourceCatalogBinding>(
	{ inf, p -> ItemSourceCatalogBinding.inflate(inf, p, false) },
) {
	binding.root.setOnClickListener { v -> onClick(item, v) }
	binding.imageViewAdd.setOnClickListener { v ->
		when {
			item.hasUpdate -> onInstall(item, v)
			item.isInstalled || item.isLoaded || item.isPreInstalledApk -> onUninstall(item, v)
			else -> onInstall(item, v)
		}
	}
	binding.imageViewAdd.setOnLongClickListener { v ->
		if (!item.isPreInstalledApk) onSideload(item, v)
		true
	}
	val pad =
		context.getThemeDimensionPixelOffset(
			appcompatR.attr.listPreferredItemPaddingEnd,
			binding.root.paddingStart,
		)
	val margin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
	binding.root.updatePaddingRelative(end = (pad - margin).coerceAtLeast(0))
	onViewRecycled {
		(binding.imageViewAdd.drawable as? Animatable)?.stop()
	}
	bind {
		val size = context.resources.getDimensionPixelSize(R.dimen.card_indicator_size)
		binding.imageViewIcon.layoutParams =
			binding.imageViewIcon.layoutParams.apply {
				width = size
				height = size
			}
		binding.imageViewIcon.scaleType = ImageView.ScaleType.CENTER_CROP
		val fb = FaviconDrawable(context, R.style.FaviconDrawable, item.artifact.packageName)
		binding.imageViewIcon.errorDrawable = fb
		binding.imageViewIcon.fallbackDrawable = fb
		if (item.artifact.iconUrl.isNullOrBlank()) {
			binding.imageViewIcon.setImageDrawable(fb)
		} else {
			binding.imageViewIcon.setImageAsync(item.artifact.iconUrl)
		}
		binding.imageViewIcon.background = null
		binding.textViewTitle.text = item.displayName
		binding.textViewDescription.text = item.description(context)
		binding.textViewDescription.drawableStart = null

		val isInst = item.isInstalled || item.isLoaded || item.isPreInstalledApk
		(binding.imageViewAdd.drawable as? Animatable)?.stop()
		if (item.isInstalling) {
			binding.imageViewAdd.isEnabled = false
			binding.imageViewAdd.setImageDrawable(
				CircularProgressDrawable(context).apply {
					setStyle(CircularProgressDrawable.DEFAULT)
					setColorSchemeColors(context.getThemeColor(appcompatR.attr.colorControlNormal))
					start()
				},
			)
			binding.imageViewAdd.contentDescription = context.getString(R.string.loading_)
		} else {
			binding.imageViewAdd.isEnabled = true
			binding.imageViewAdd.setImageResource(
				when {
					isInst && item.hasUpdate -> R.drawable.ic_updated
					isInst -> R.drawable.ic_delete
					else -> R.drawable.ic_download
				},
			)
			binding.imageViewAdd.contentDescription =
				context.getString(
					when {
						isInst && item.hasUpdate -> R.string.update
						isInst -> R.string.delete
						else -> R.string.add
					},
				)
		}
	}
}

fun sourceCatalogItemHintAD() =
	adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyHintBinding>(
		{ inf, p -> ItemEmptyHintBinding.inflate(inf, p, false) },
	) {
		binding.buttonRetry.isVisible = false
		bind {
			binding.icon.setImageAsync(item.icon)
			binding.textPrimary.setText(item.title)
			binding.textSecondary.setTextAndVisible(item.text)
		}
	}
