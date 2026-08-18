package org.draken.usagi.settings.sources.manage.plugins

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.shape.CornerFamily
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceConfigBinding
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem
import com.google.android.material.R as materialR

class PluginManageAdapter(
	onRenameClick: (PluginManageItem.Plugin) -> Unit,
	onUpdateClick: (PluginManageItem.Plugin) -> Unit,
	onTachiyomiRenameClick: (PluginManageItem.Tachiyomi) -> Unit,
	onTachiyomiLongClick: (PluginManageItem.Tachiyomi) -> Unit,
	onTachiyomiClick: (PluginManageItem.Tachiyomi) -> Unit,
	onLongClick: (PluginManageItem.Plugin) -> Unit,
	onClick: (PluginManageItem.Plugin) -> Unit,
	isSelected: (PluginManageItem.Plugin) -> Boolean,
	isTachiyomiSelected: (PluginManageItem.Tachiyomi) -> Boolean,
) : BaseListAdapter<ListModel>() {
	init {
		addDelegate(ListItemType.STATE_LOADING, loadingItemDelegate())
		addDelegate(ListItemType.CHAPTER_LIST, pluginItemDelegate(onRenameClick, onUpdateClick, onLongClick, onClick, isSelected))
		addDelegate(ListItemType.INFO, tachiyomiItemDelegate(onTachiyomiRenameClick, onTachiyomiLongClick, onTachiyomiClick, isTachiyomiSelected))
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
			binding.textViewTitle.background = null
			binding.textViewTitle.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
			binding.textViewDescription.background = null
			binding.textViewDescription.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT

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
			binding.textViewTitle.background = null
			binding.textViewTitle.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
			binding.textViewDescription.background = null
			binding.textViewDescription.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT

			applyRemoteIconShape(binding.imageViewIcon, context)

			if (item.isLocal) {
				val fallback = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.displayName)
				binding.imageViewIcon.errorDrawable = fallback
				binding.imageViewIcon.fallbackDrawable = fallback
				val iconUrl = item.installed.firstOrNull()?.iconUrl
				if (!iconUrl.isNullOrBlank()) {
					binding.imageViewIcon.setImageAsync(iconUrl)
				} else {
					binding.imageViewIcon.setImageDrawable(fallback)
				}
			} else {
				val fallback = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.repositoryLabel)
				binding.imageViewIcon.errorDrawable = fallback
				binding.imageViewIcon.fallbackDrawable = fallback
				val avatarUrl = githubAvatarUrl(item.repositoryLabel)
				binding.imageViewIcon.setImageAsync(avatarUrl)
			}

			binding.imageViewMenu.isVisible = true
			binding.imageViewMenu.setOnClickListener { onRenameClick(item) }

			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				buildList {
					add(item.repositoryLabel)
					add(context.getString(R.string.external_source))
					if (item.hasFailures) add(context.getString(R.string.load_failed))
				}.joinToString(" • ")
		}
	}

	private fun loadingItemDelegate() =
		adapterDelegateViewBinding<PluginManageItem.Loading, ListModel, ItemSourceConfigBinding>(
			{ layoutInflater, parent -> ItemSourceConfigBinding.inflate(layoutInflater, parent, false) },
		) {
			binding.imageViewMenu.isVisible = false
			binding.imageViewRemove.isVisible = false
			binding.imageViewAdd.isVisible = false
			itemView.isClickable = false
			itemView.isFocusable = false

			bind {
				itemView.isSelected = false
				val density = context.resources.displayMetrics.density
				binding.imageViewIcon.setImageDrawable(null)
				binding.imageViewIcon.background = ShimmerPlaceholderDrawable(context, 8f * density)

				binding.textViewTitle.text = " "
				binding.textViewTitle.layoutParams.width = (180 * density).toInt()
				binding.textViewTitle.background = ShimmerPlaceholderDrawable(context, 4f * density)

				binding.textViewDescription.text = " "
				binding.textViewDescription.layoutParams.width = (120 * density).toInt()
				binding.textViewDescription.background = ShimmerPlaceholderDrawable(context, 4f * density)
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

	private class ShimmerPlaceholderDrawable(
		context: Context,
		private val cornerRadius: Float = 8f,
	) : Drawable(),
		Animatable,
		TimeAnimator.TimeListener {
		private val colorLow = context.getThemeColor(materialR.attr.colorSurfaceContainerLowest, Color.DKGRAY)
		private val colorHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, Color.LTGRAY)
		private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		private val timeAnimator = TimeAnimator().apply { setTimeListener(this@ShimmerPlaceholderDrawable) }
		private var phase = 0f

		override fun draw(canvas: Canvas) {
			if (!isRunning) start()
			val bounds = bounds
			if (bounds.width() <= 0 || bounds.height() <= 0) return

			val w = bounds.width().toFloat()
			val h = bounds.height().toFloat()
			val bandWidth = w * 0.7f
			val x = (w + bandWidth * 2) * phase - bandWidth

			paint.shader =
				LinearGradient(
					x,
					0f,
					x + bandWidth,
					0f,
					intArrayOf(colorLow, colorHigh, colorLow),
					floatArrayOf(0f, 0.5f, 1f),
					Shader.TileMode.CLAMP,
				)
			canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, paint)
		}

		override fun onTimeUpdate(
			animation: TimeAnimator?,
			totalTime: Long,
			deltaTime: Long,
		) {
			phase = (totalTime % 1200L) / 1200f
			invalidateSelf()
		}

		override fun start() {
			if (!timeAnimator.isRunning) timeAnimator.start()
		}

		override fun stop() {
			timeAnimator.cancel()
		}

		override fun isRunning(): Boolean = timeAnimator.isRunning

		override fun setAlpha(alpha: Int) {
			paint.alpha = alpha
		}

		override fun setColorFilter(colorFilter: ColorFilter?) {
			paint.colorFilter = colorFilter
		}

		@Deprecated("Deprecated in Java")
		override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
	}

	private fun githubAvatarUrl(repository: String): String? {
		val owner = repositoryLabel(repository).substringBefore('/').trim()
		return owner.takeIf { it.isNotBlank() }?.let { "https://github.com/$it.png" }
	}

	private fun applyRemoteIconShape(
		imageView: org.draken.usagi.core.ui.image.FaviconView,
		context: Context,
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
