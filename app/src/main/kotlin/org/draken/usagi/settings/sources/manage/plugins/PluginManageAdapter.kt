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
	onExtRenameClick: (PluginManageItem.Extension) -> Unit,
	onExtLongClick: (PluginManageItem.Extension) -> Unit,
	onExtClick: (PluginManageItem.Extension) -> Unit,
	onLongClick: (PluginManageItem.Plugin) -> Unit,
	onClick: (PluginManageItem.Plugin) -> Unit,
	isSelected: (PluginManageItem.Plugin) -> Boolean,
	isExtSelected: (PluginManageItem.Extension) -> Boolean,
) : BaseListAdapter<ListModel>() {
	init {
		addDelegate(ListItemType.STATE_LOADING, loadingAD())
		addDelegate(ListItemType.CHAPTER_LIST, pluginAD(onRenameClick, onUpdateClick, onLongClick, onClick, isSelected))
		addDelegate(ListItemType.INFO, extAD(onExtRenameClick, onExtLongClick, onExtClick, isExtSelected))
		addDelegate(ListItemType.HINT_EMPTY, placeholderAD())
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun pluginAD(
		onRename: (PluginManageItem.Plugin) -> Unit,
		onUpdate: (PluginManageItem.Plugin) -> Unit,
		onLong: (PluginManageItem.Plugin) -> Unit,
		onClick: (PluginManageItem.Plugin) -> Unit,
		selected: (PluginManageItem.Plugin) -> Boolean,
	) = adapterDelegateViewBinding<PluginManageItem.Plugin, ListModel, ItemSourceConfigBinding>(
		{ inf, p -> ItemSourceConfigBinding.inflate(inf, p, false) },
	) {
		binding.imageViewIcon.background = null
		binding.imageViewMenu.isVisible = true
		binding.imageViewMenu.setImageResource(R.drawable.ic_edit)
		binding.imageViewMenu.setOnClickListener { onRename(item) }
		binding.imageViewRemove.isVisible = false
		binding.imageViewAdd.setImageResource(R.drawable.ic_download)
		itemView.setOnLongClickListener {
			onLong(item)
			true
		}
		itemView.setOnClickListener { onClick(item) }

		bind {
			itemView.isSelected = selected(item)
			resetViews(binding)
			val avatar = item.repository?.takeIf { it.isNotBlank() }?.let(::githubAvatarUrl)
			val fb = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.displayName)
			binding.imageViewIcon.errorDrawable = fb
			binding.imageViewIcon.fallbackDrawable = fb
			if (avatar.isNullOrBlank()) {
				binding.imageViewIcon.setImageResource(R.drawable.ic_services)
			} else {
				applyIconShape(binding.imageViewIcon, context)
				binding.imageViewIcon.setImageAsync(avatar)
			}
			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				listOfNotNull(
					item.repository?.takeIf { it.isNotBlank() }?.let(::repoLabel) ?: item.name,
					item.installedTag?.takeIf { it.isNotBlank() },
				).joinToString(" • ")
			binding.imageViewAdd.isVisible = item.hasUpdate
			binding.imageViewAdd.setOnClickListener(if (item.hasUpdate) View.OnClickListener { onUpdate(item) } else null)
		}
	}

	private fun extAD(
		onRename: (PluginManageItem.Extension) -> Unit,
		onLong: (PluginManageItem.Extension) -> Unit,
		onClick: (PluginManageItem.Extension) -> Unit,
		selected: (PluginManageItem.Extension) -> Boolean,
	) = adapterDelegateViewBinding<PluginManageItem.Extension, ListModel, ItemSourceConfigBinding>(
		{ inf, p -> ItemSourceConfigBinding.inflate(inf, p, false) },
	) {
		binding.imageViewIcon.background = null
		binding.imageViewMenu.isVisible = true
		binding.imageViewMenu.setImageResource(R.drawable.ic_edit)
		binding.imageViewRemove.isVisible = false
		binding.imageViewAdd.isVisible = false
		itemView.setOnLongClickListener {
			onLong(item)
			true
		}
		itemView.setOnClickListener { onClick(item) }

		bind {
			itemView.isSelected = selected(item)
			resetViews(binding)
			applyIconShape(binding.imageViewIcon, context)
			val iconLabel = if (item.isLocal) item.displayName else item.repositoryLabel
			val fb = FaviconDrawable(context, R.style.FaviconDrawable_Small, iconLabel)
			binding.imageViewIcon.errorDrawable = fb
			binding.imageViewIcon.fallbackDrawable = fb
			val icon = if (item.isLocal) item.installed.firstOrNull()?.iconUrl else githubAvatarUrl(item.repositoryLabel)
			if (icon.isNullOrBlank()) {
				binding.imageViewIcon.setImageDrawable(fb)
			} else {
				binding.imageViewIcon.setImageAsync(icon)
			}

			binding.imageViewMenu.setOnClickListener { onRename(item) }
			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				buildList {
					add(item.repositoryLabel)
					add(context.getString(R.string.external_source))
					if (item.hasFailures) add(context.getString(R.string.load_failed))
				}.joinToString(" • ")
		}
	}

	private fun loadingAD() =
		adapterDelegateViewBinding<PluginManageItem.Loading, ListModel, ItemSourceConfigBinding>(
			{ inf, p -> ItemSourceConfigBinding.inflate(inf, p, false) },
		) {
			binding.imageViewMenu.isVisible = false
			binding.imageViewRemove.isVisible = false
			binding.imageViewAdd.isVisible = false
			itemView.isClickable = false
			onViewRecycled {
				(binding.imageViewIcon.background as? Animatable)?.stop()
				(binding.textViewTitle.background as? Animatable)?.stop()
				(binding.textViewDescription.background as? Animatable)?.stop()
			}
			bind {
				itemView.isSelected = false
				val d = context.resources.displayMetrics.density
				binding.imageViewIcon.setImageDrawable(null)
				binding.imageViewIcon.background = ShimmerDrawable(context, 8f * d)
				binding.textViewTitle.text = " "
				binding.textViewTitle.layoutParams.width = (180 * d).toInt()
				binding.textViewTitle.background = ShimmerDrawable(context, 4f * d)
				binding.textViewDescription.text = " "
				binding.textViewDescription.layoutParams.width = (120 * d).toInt()
				binding.textViewDescription.background = ShimmerDrawable(context, 4f * d)
			}
		}

	private fun placeholderAD() =
		adapterDelegateViewBinding<PluginManageItem.Placeholder, ListModel, ItemEmptyHintBinding>(
			{ inf, p -> ItemEmptyHintBinding.inflate(inf, p, false) },
		) {
			binding.icon.setImageResource(R.drawable.ic_empty_feed)
			bind {
				binding.textPrimary.setText(item.titleResId)
				binding.textSecondary.setTextAndVisible(item.summaryResId ?: 0)
			}
		}

	private class ShimmerDrawable(
		context: Context,
		private val radius: Float = 8f,
	) : Drawable(),
		Animatable,
		TimeAnimator.TimeListener {
		private val c1 = context.getThemeColor(materialR.attr.colorSurfaceContainerLowest, Color.DKGRAY)
		private val c2 = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, Color.LTGRAY)
		private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		private val anim = TimeAnimator().apply { setTimeListener(this@ShimmerDrawable) }
		private var phase = 0f

		override fun draw(canvas: Canvas) {
			if (!isRunning) start()
			val b = bounds
			if (b.width() <= 0 || b.height() <= 0) return
			val w = b.width().toFloat()
			val bw = w * 0.7f
			val x = (w + bw * 2) * phase - bw
			paint.shader =
				LinearGradient(
					x,
					0f,
					x + bw,
					0f,
					intArrayOf(c1, c2, c1),
					floatArrayOf(0f, 0.5f, 1f),
					Shader.TileMode.CLAMP,
				)
			canvas.drawRoundRect(0f, 0f, w, b.height().toFloat(), radius, radius, paint)
		}

		override fun onTimeUpdate(
			a: TimeAnimator?,
			total: Long,
			dt: Long,
		) {
			phase = (total % 1200L) / 1200f
			invalidateSelf()
		}

		override fun start() {
			if (!anim.isRunning) anim.start()
		}

		override fun stop() {
			anim.cancel()
		}

		override fun isRunning() = anim.isRunning

		override fun setVisible(
			v: Boolean,
			r: Boolean,
		): Boolean {
			val changed = super.setVisible(v, r)
			if (v) start() else stop()
			return changed
		}

		override fun setAlpha(alpha: Int) {
			paint.alpha = alpha
		}

		override fun setColorFilter(cf: ColorFilter?) {
			paint.colorFilter = cf
		}

		@Deprecated("Deprecated in Java")
		override fun getOpacity() = PixelFormat.TRANSLUCENT
	}

	companion object {
		private fun resetViews(b: ItemSourceConfigBinding) {
			b.textViewTitle.background = null
			b.textViewTitle.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
			b.textViewDescription.background = null
			b.textViewDescription.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
		}

		fun githubAvatarUrl(repo: String): String? =
			repoLabel(repo)
				.substringBefore('/')
				.trim()
				.takeIf { it.isNotBlank() }
				?.let { "https://github.com/$it.png" }

		fun applyIconShape(
			iv: org.draken.usagi.core.ui.image.FaviconView,
			ctx: Context,
		) {
			iv.shapeAppearanceModel =
				iv.shapeAppearanceModel
					.toBuilder()
					.setAllCorners(CornerFamily.ROUNDED, ctx.resources.getDimension(R.dimen.margin_small))
					.build()
		}

		fun repoLabel(repo: String): String =
			repo
				.trim()
				.removeSuffix("/")
				.removePrefix("https://github.com/")
				.removePrefix("http://github.com/")
				.removePrefix("github.com/")
	}
}
