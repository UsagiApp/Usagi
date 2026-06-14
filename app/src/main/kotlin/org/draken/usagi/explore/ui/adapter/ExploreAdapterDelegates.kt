package org.draken.usagi.explore.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.HeroCarouselStrategy
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.model.getSummary
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.AdapterDelegateClickListenerAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.util.ext.drawableStart
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.core.util.ext.setProgressIcon
import org.draken.usagi.core.util.ext.setTooltipCompat
import org.draken.usagi.core.util.ext.textAndVisible
import org.draken.usagi.databinding.ItemExploreButtonsBinding
import org.draken.usagi.databinding.ItemExploreSourceGridBinding
import org.draken.usagi.databinding.ItemExploreSourceListBinding
import org.draken.usagi.databinding.ItemRecommendationBinding
import org.draken.usagi.databinding.ItemRecommendationMangaBinding
import org.draken.usagi.explore.ui.model.ExploreButtons
import org.draken.usagi.explore.ui.model.MangaSourceItem
import org.draken.usagi.explore.ui.model.RecommendationsItem
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.abs

fun exploreButtonsAD(
	clickListener: View.OnClickListener,
) = adapterDelegateViewBinding<ExploreButtons, ListModel, ItemExploreButtonsBinding>(
	{ layoutInflater, parent -> ItemExploreButtonsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.buttonBookmarks.setOnClickListener(clickListener)
	binding.buttonDownloads.setOnClickListener(clickListener)
	binding.buttonLocal.setOnClickListener(clickListener)
	binding.buttonRandom.setOnClickListener(clickListener)

	bind {
		if (item.isRandomLoading) {
			binding.buttonRandom.setProgressIcon()
		} else {
			binding.buttonRandom.setIconResource(R.drawable.ic_dice)
		}
		binding.buttonRandom.isClickable = !item.isRandomLoading
	}
}

fun exploreRecommendationItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<RecommendationsItem, ListModel, ItemRecommendationBinding>(
	{ layoutInflater, parent -> ItemRecommendationBinding.inflate(layoutInflater, parent, false) },
) {

	val adapter = BaseListAdapter<MangaCompactListModel>().addDelegate(ListItemType.MANGA_LIST, recommendationMangaItemAD(itemClickListener))
	binding.recyclerViewCarousel.apply {
		layoutManager = CarouselLayoutManager(HeroCarouselStrategy()).apply {
			carouselAlignment = CarouselLayoutManager.ALIGNMENT_CENTER
		}
		this.adapter = adapter
		isNestedScrollingEnabled = false
		CarouselSnapHelper().attachToRecyclerView(this)
		setChildDrawingOrderCallback { n, i ->
			val c = width / 2
			(0 until n).sortedByDescending {
				getChildAt(it)?.run {abs((left + right) / 2 - c) } ?: 0
			}.getOrElse(i) { i }
		}
		binding.dots.bindToRecyclerView(this)
	}

	bind {
		adapter.items = item.manga
	}
}

fun recommendationMangaItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<MangaCompactListModel, MangaCompactListModel, ItemRecommendationMangaBinding>(
	{ layoutInflater, parent -> ItemRecommendationMangaBinding.inflate(layoutInflater, parent, false) },
) {
	binding.root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
	val bgColor = context.getThemeColor(android.R.attr.colorBackground)
	val alpha = { a: Int -> ColorUtils.setAlphaComponent(bgColor, a) }
	binding.gradientOverlay.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
		intArrayOf(Color.TRANSPARENT, alpha(25), alpha(70), alpha(130), alpha(190), alpha(230), bgColor)
	)

	binding.root.setOnClickListener { v ->
		itemClickListener.onItemClick(item.manga, v)
	}
	bind {
		binding.textViewTitle.text = item.manga.title
		binding.textViewSubtitle.textAndVisible = item.subtitle
		binding.imageViewCover.setImageAsync(item.manga.coverUrl, item.manga.source)
	}
}


fun exploreSourceListItemAD(
	listener: OnListItemClickListener<MangaSourceItem>,
) = adapterDelegateViewBinding<MangaSourceItem, ListModel, ItemExploreSourceListBinding>(
	{ layoutInflater, parent ->
		ItemExploreSourceListBinding.inflate(
			layoutInflater,
			parent,
			false,
		)
	},
	on = { item, _, _ -> item is MangaSourceItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, listener).attach(itemView)
	val iconPinned = ContextCompat.getDrawable(context, R.drawable.ic_pin_small)

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewSubtitle.text = item.source.getSummary(context)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

fun exploreSourceGridItemAD(
	listener: OnListItemClickListener<MangaSourceItem>,
) = adapterDelegateViewBinding<MangaSourceItem, ListModel, ItemExploreSourceGridBinding>(
	{ layoutInflater, parent ->
		ItemExploreSourceGridBinding.inflate(
			layoutInflater,
			parent,
			false,
		)
	},
	on = { item, _, _ -> item is MangaSourceItem && item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, listener).attach(itemView)
	val iconPinned = ContextCompat.getDrawable(context, R.drawable.ic_pin_small)

	bind {
		val title = item.source.getTitle(context)
		itemView.setTooltipCompat(
			buildSpannedString {
				bold {
					append(title)
				}
				appendLine()
				append(item.source.getSummary(context))
			},
		)
		binding.textViewTitle.text = title
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.imageViewIcon.setImageAsync(item.source)
	}
}
