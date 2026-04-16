package org.draken.usagi.settings.sources.catalog

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.model.getSummary
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.model.titleResId
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.util.ext.drawableStart
import org.draken.usagi.core.util.ext.getDisplayName
import org.draken.usagi.core.util.ext.getThemeDimensionPixelOffset
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.core.util.ext.toLocaleOrNull
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceCatalogBinding
import org.draken.usagi.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

fun sourceCatalogItemSourceAD(
	listener: OnListItemClickListener<SourceCatalogItem.Source>
) = adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemLongClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		val installable = item.installableRepoSource
		binding.textViewDescription.text = if (installable == null) {
			item.source.getSummary(context)
		} else {
			buildString {
				append(context.getString(item.source.contentType.titleResId))
				if (installable.sourceLang.isNotBlank()) {
					append(", ").append(
						installable.sourceLang.toLocaleOrNull()?.getDisplayName(context) ?: installable.sourceLang,
					)
				}
				append(" • ").append(installable.repoOwnerTag)
			}
		}
		binding.textViewDescription.drawableStart = if (installable == null && item.source.isBroken) {
			ContextCompat.getDrawable(context, R.drawable.ic_off_small)
		} else {
			null
		}
		FaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyHintBinding>(
	{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}
