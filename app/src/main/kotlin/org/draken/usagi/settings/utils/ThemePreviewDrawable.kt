package org.draken.usagi.settings.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import org.draken.usagi.R
import org.draken.usagi.core.prefs.ListMode
import org.draken.usagi.core.prefs.NavItem
import org.draken.usagi.core.ui.image.CoverPlaceholderColors
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.list.domain.ReadingProgress
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class ThemePreviewDrawable(
	private val context: Context,
) : Drawable() {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val textPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			isSubpixelText = true
		}
	private val rect = RectF()
	private val regular = Typeface.DEFAULT
	private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
	private val density = context.resources.displayMetrics.density
	private val listPadding = dimenDp(R.dimen.list_spacing_normal)
	private val preferredGridWidth = dimenDp(R.dimen.preferred_grid_width)
	private val smallGridWidth = dimenDp(R.dimen.small_grid_width)
	private val gridSpacing = dimenDp(R.dimen.grid_spacing)
	private val indicatorOffset = dimenDp(R.dimen.card_indicator_offset)

	private val surfaceContainer = context.getThemeColor(materialR.attr.colorSurfaceContainer)
	private val surfaceContainerHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHigh)
	private val onSurface = context.getThemeColor(materialR.attr.colorOnSurface)
	private val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)
	private val outline = context.getThemeColor(materialR.attr.colorOutline)
	private val primaryContainer = context.getThemeColor(materialR.attr.colorPrimaryContainer)
	private val onPrimaryContainer = context.getThemeColor(materialR.attr.colorOnPrimaryContainer)
	private val secondaryContainer = context.getThemeColor(materialR.attr.colorSecondaryContainer)
	private val onSecondaryContainer = context.getThemeColor(materialR.attr.colorOnSecondaryContainer)
	private val primary = context.getThemeColor(appcompatR.attr.colorPrimary)
	private val onPrimary = context.getThemeColor(materialR.attr.colorOnPrimary)
	private val surfaceVariant = context.getThemeColor(materialR.attr.colorSurfaceVariant)

	private val placeholderColors = CoverPlaceholderColors(context)
	private val checkIcon = context.icon(R.drawable.ic_check, onPrimary)

	private val searchHint = context.getString(R.string.theme_preview_search)
	private val searchIcon = context.icon(appcompatR.drawable.abc_ic_search_api_material, onSurface)
	private val overflowIcon = context.icon(appcompatR.drawable.abc_ic_menu_overflow_material, onSurfaceVariant)
	private val chips =
		listOf(
			label(R.string.theme_preview_on_device, R.drawable.ic_storage, onSurfaceVariant),
			label(R.string.theme_preview_new_chapters, R.drawable.ic_updated, onSurfaceVariant),
			label(R.string.theme_preview_completed, R.drawable.ic_state_finished, onSurfaceVariant),
			label(R.string.theme_preview_favourites, R.drawable.ic_heart_outline, onSurfaceVariant),
		)
	private val fab = label(R.string.theme_preview_continue, R.drawable.ic_read, onPrimaryContainer)

	private var drawableAlpha = 255
	private var config = Config()
	private var navItems = createNavItems(config.navItems)
	private var history: List<Group> = emptyList()

	private val activeNavItem: Int
		get() = config.navItems.indexOf(NavItem.HISTORY).coerceAtLeast(0)

	fun setConfig(value: Config) {
		if (value == config) {
			return
		}
		if (value.navItems != config.navItems) {
			navItems = createNavItems(value.navItems)
		}
		config = value
		invalidateSelf()
	}

	fun setHistory(groups: List<Group>) {
		if (groups !== history) {
			history = groups
			invalidateSelf()
		}
	}

	override fun draw(canvas: Canvas) {
		val bounds = bounds
		if (bounds.isEmpty) {
			return
		}
		canvas.withSave {
			translate(bounds.left.toFloat(), bounds.top.toFloat())
			scale(bounds.width() / config.width, bounds.height() / config.height)
			clipRect(0f, 0f, config.width, config.height)
			drawStatusBar(this)
			drawSearchBar(this)
			drawList(this)
			drawFab(this)
			drawNavigation(this)
		}
	}

	override fun setAlpha(alpha: Int) {
		drawableAlpha = alpha
		invalidateSelf()
	}

	override fun getAlpha(): Int = drawableAlpha

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
		textPaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	private val navigationTop: Float
		get() = config.height - config.bottomInset - NAVIGATION_HEIGHT

	private fun drawStatusBar(canvas: Canvas) {
		val height = config.statusBar
		val center = height * 0.58f
		drawText(canvas, STATUS_TIME, 29f, center + height * 0.13f, height * 0.36f, onSurface, medium)
		val iconSize = height * 0.3f
		val right = config.width - 27f
		val bottom = center + iconSize / 2f
		val batteryLeft = right - iconSize * 2.2f
		paint.style = Paint.Style.STROKE
		paint.strokeWidth = iconSize * 0.1f
		paint.color = onSurface.withAlpha()
		rect.set(batteryLeft, bottom - iconSize * 0.9f, right - iconSize * 0.2f, bottom)
		canvas.drawRoundRect(rect, iconSize * 0.2f, iconSize * 0.2f, paint)
		paint.style = Paint.Style.FILL
		val inset = iconSize * 0.15f
		val battery = RectF(rect)
		fillRect(
			canvas,
			battery.left + inset,
			battery.top + inset,
			battery.right - iconSize * 0.6f,
			battery.bottom - inset,
			inset,
			onSurface,
		)
		val tip = iconSize * 0.2f
		fillRect(canvas, right - iconSize * 0.15f, battery.centerY() - tip, right, battery.centerY() + tip, 1f, onSurface)
		val signalRight = batteryLeft - iconSize * 0.5f
		val barWidth = iconSize * 0.22f
		for (i in 0..3) {
			val left = signalRight - (4 - i) * barWidth * 1.4f
			val top = bottom - iconSize * (0.3f + 0.23f * i)
			fillRect(canvas, left, top, left + barWidth, bottom, barWidth / 3f, onSurface)
		}
		val wifiCenter = signalRight - barWidth * 5.6f - iconSize * 0.9f
		paint.color = onSurface.withAlpha()
		rect.set(wifiCenter - iconSize, bottom - iconSize, wifiCenter + iconSize, bottom + iconSize)
		canvas.drawArc(rect, 225f, 90f, true, paint)
	}

	private fun drawSearchBar(canvas: Canvas) {
		val top = config.statusBar + 16f
		fillRect(canvas, 16f, top, config.width - 16f, top + 56f, 28f, surfaceContainerHigh)
		drawIcon(canvas, searchIcon, 32f, top + 16f, 24f)
		drawText(canvas, searchHint, 68f, top + 33.5f, 16f, onSurfaceVariant, regular)
		drawIcon(canvas, overflowIcon, config.width - 56f, top + 16f, 24f)
	}

	private fun drawChips(
		canvas: Canvas,
		top: Float,
	) {
		var left = 16f
		for (chip in chips) {
			val right = left + chip.textWidth(textPaint, 14f, medium) + 54f
			rect.set(left, top, right, top + 32f)
			paint.style = Paint.Style.STROKE
			paint.strokeWidth = 1f
			paint.color = outline.withAlpha()
			canvas.drawRoundRect(rect, 8f, 8f, paint)
			paint.style = Paint.Style.FILL
			drawIcon(canvas, chip.icon, left + 8f, top + 7f, 18f)
			drawText(canvas, chip.title, left + 37f, top + 21f, 14f, onSurfaceVariant, medium)
			left = right + 8f
		}
	}

	private fun drawList(canvas: Canvas) {
		var top = config.statusBar + 88f + listPadding
		if (config.showQuickFilters) {
			drawChips(canvas, top + 2f)
			top += QUICK_FILTER_HEIGHT
		}
		for (group in history) {
			if (top >= navigationTop) {
				break
			}
			if (group.title != null) {
				drawText(canvas, group.title, 16f, top + 26.7f, 14f, onSurface, medium)
				top += HEADER_HEIGHT
			}
			var position = 0
			while (position < group.items.size && top < navigationTop) {
				val count: Int
				val height: Float
				when (config.listMode) {
					ListMode.GRID, ListMode.COVER_ONLY -> {
						val withTitles = config.listMode == ListMode.GRID
						count = drawGridRow(canvas, top, group.items, position, withTitles)
						height = gridRowHeight(withTitles)
					}

					ListMode.LIST -> {
						count = 1
						height = drawCompactItem(canvas, top, group.items[position])
					}

					ListMode.DETAILED_LIST -> {
						count = 1
						height = drawDetailedItem(canvas, top, group.items[position])
					}
				}
				position += count
				top += height
			}
		}
	}

	private val listWidth: Float
		get() = config.width - listPadding * 2f

	private val span: Int
		get() =
			(listWidth / (preferredGridWidth * config.gridScale + gridSpacing))
				.roundToInt()
				.coerceAtLeast(2)

	private val gridTextSize: Float
		get() = if (preferredGridWidth * config.gridScale < smallGridWidth) 12f else 14f

	private fun gridCoverHeight() = (listWidth / span - 16f) * 18f / 13f

	private fun gridRowHeight(withTitles: Boolean): Float {
		val titlesHeight = if (withTitles) 8f + gridTextSize * (2f * LINE_HEIGHT + FONT_PADDING) else 0f
		return 16f + gridCoverHeight() + titlesHeight
	}

	private fun drawGridRow(
		canvas: Canvas,
		top: Float,
		items: List<Item>,
		from: Int,
		withTitles: Boolean,
	): Int {
		val columnWidth = listWidth / span
		val coverWidth = columnWidth - 16f
		val coverHeight = gridCoverHeight()
		val textSize = gridTextSize
		val indicatorSize = (coverWidth * 0.36f).coerceIn(24f, 48f)
		val count = minOf(span, items.size - from)
		for (column in 0 until count) {
			val item = items[from + column]
			val left = listPadding + column * columnWidth + 8f
			val coverTop = top + 8f
			fillRect(canvas, left, coverTop, left + coverWidth, coverTop + coverHeight, 12f, coverColor(item))
			drawProgress(canvas, left + coverWidth, coverTop + coverHeight, indicatorSize, item)
			if (withTitles) {
				drawTitle(canvas, left, coverTop + coverHeight + 4f, coverWidth, item.titleLength, textSize, 2)
			}
		}
		return count
	}

	private fun drawCompactItem(
		canvas: Canvas,
		top: Float,
		item: Item,
	): Float {
		val left = listPadding + 8f
		fillRect(canvas, left, top + 8f, left + 40f, top + 48f, 4f, coverColor(item))
		val textLeft = left + 56f
		val textWidth = config.width - listPadding - 8f - textLeft
		drawTitle(canvas, textLeft, top + 9f, textWidth, item.titleLength, 14f, 1)
		textBar(canvas, textLeft, top + 38f, textWidth * 0.3f, 12f, onSurfaceVariant)
		return 56f
	}

	private fun drawDetailedItem(
		canvas: Canvas,
		top: Float,
		item: Item,
	): Float {
		val left = listPadding + 8f
		val coverTop = top + 8f
		val coverWidth = 118f * 2f / 3f
		fillRect(canvas, left, coverTop, left + coverWidth, coverTop + 118f, 8f, coverColor(item))
		drawProgress(canvas, left + coverWidth, coverTop + 118f, 32f, item)
		val textLeft = left + coverWidth + 16f
		val textWidth = config.width - listPadding - 8f - 12f - textLeft
		val lines = drawTitle(canvas, textLeft, coverTop, textWidth, item.titleLength, 16f, 3)
		var y = coverTop + 16f * (lines * LINE_HEIGHT + FONT_PADDING) + 4f
		textBar(canvas, textLeft, y + 14f * (LINE_HEIGHT + FONT_PADDING) / 2f, textWidth * 0.35f, 14f, onSurfaceVariant)
		y += 14f * (LINE_HEIGHT + FONT_PADDING) + 4f
		val tagLine = 12f * LINE_HEIGHT
		textBar(canvas, textLeft, y + 12f * FONT_PADDING / 2f + tagLine / 2f, textWidth, 12f, onSurfaceVariant)
		textBar(canvas, textLeft, y + 12f * FONT_PADDING / 2f + tagLine * 1.5f, textWidth * 0.6f, 12f, onSurfaceVariant)
		return 118f + 16f
	}

	private fun drawProgress(
		canvas: Canvas,
		coverRight: Float,
		coverBottom: Float,
		size: Float,
		item: Item,
	) {
		if (item.progress < 0f) {
			return
		}
		val radius = size / 2f
		val cx = coverRight - indicatorOffset - radius
		val cy = coverBottom - indicatorOffset - radius
		paint.color = primary.withAlpha()
		canvas.drawCircle(cx, cy, radius, paint)
		val innerRadius = radius - PROGRESS_STROKE / 2f
		paint.style = Paint.Style.STROKE
		paint.strokeWidth = PROGRESS_STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.color = surfaceVariant.withAlpha()
		rect.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
		canvas.drawArc(rect, -90f, 360f * item.progress, false, paint)
		paint.style = Paint.Style.FILL
		paint.strokeCap = Paint.Cap.BUTT
		if (ReadingProgress.isCompleted(item.progress)) {
			val iconSize = size * 0.6f
			drawIcon(canvas, checkIcon, cx - iconSize / 2f, cy - iconSize / 2f, iconSize)
		} else if (item.progressText.isNotEmpty()) {
			textPaint.typeface = regular
			textPaint.textSize = 48f
			val fitSize = 48f * (size - PROGRESS_STROKE * 2f) / textPaint.measureText(PROGRESS_FIT_TEXT)
			textPaint.textSize = fitSize
			textPaint.color = onPrimary.withAlpha()
			val width = textPaint.measureText(item.progressText)
			canvas.drawText(item.progressText, cx - width / 2f, cy + fitSize * 0.35f, textPaint)
		}
	}

	private fun drawTitle(
		canvas: Canvas,
		left: Float,
		top: Float,
		width: Float,
		length: Int,
		textSize: Float,
		maxLines: Int,
	): Int {
		val perLine = (width / (textSize * CHAR_WIDTH)).coerceAtLeast(1f)
		var remaining = length.toFloat()
		var line = 0
		while (remaining > 0f && line < maxLines) {
			val isWrapped = remaining > perLine && line < maxLines - 1
			val fill = if (isWrapped) 0.88f else (remaining / perLine).coerceAtMost(1f)
			val center = top + textSize * (FONT_PADDING / 2f + LINE_HEIGHT * (line + 0.5f))
			textBar(canvas, left, center, width * fill, textSize, onSurface)
			remaining -= perLine * fill
			line++
		}
		return line.coerceAtLeast(1)
	}

	private fun textBar(
		canvas: Canvas,
		left: Float,
		center: Float,
		width: Float,
		textSize: Float,
		@ColorInt color: Int,
	) {
		val half = textSize / 4f
		fillRect(canvas, left, center - half, left + width, center + half, half, color)
	}

	private fun coverColor(item: Item): Int = placeholderColors.background(item.mangaId)

	private fun drawFab(canvas: Canvas) {
		val right = config.width - 16f
		val left = right - (16f + 24f + 12f + fab.textWidth(textPaint, 14f, medium) + 20f)
		val bottom = navigationTop - FAB_BOTTOM_MARGIN
		fillRect(canvas, left, bottom - 56f, right, bottom, 16f, primaryContainer)
		drawIcon(canvas, fab.icon, left + 16f, bottom - 40f, 24f)
		drawText(canvas, fab.title, left + 16f + 24f + 12f, bottom - 23f, 14f, onPrimaryContainer, medium)
	}

	private fun drawNavigation(canvas: Canvas) {
		val top = navigationTop
		fillRect(canvas, 0f, top, config.width, config.height, 0f, surfaceContainer)
		val itemWidth = config.width / navItems.size.coerceAtLeast(1)
		navItems.forEachIndexed { i, item ->
			val center = itemWidth * (i + 0.5f)
			val isActive = i == activeNavItem
			if (isActive) {
				fillRect(canvas, center - 32f, top + 12f, center + 32f, top + 44f, 16f, secondaryContainer)
			}
			drawIcon(canvas, item.icon, center - 12f, top + 16f, 24f)
			val typeface = if (isActive) medium else regular
			val width = item.textWidth(textPaint, 12f, typeface)
			val color = if (isActive) onSurface else onSurfaceVariant
			drawText(canvas, item.title, center - width / 2f, top + 60f, 12f, color, typeface)
		}
	}

	private fun fillRect(
		canvas: Canvas,
		left: Float,
		top: Float,
		right: Float,
		bottom: Float,
		radius: Float,
		@ColorInt color: Int,
	) {
		paint.color = color.withAlpha()
		rect.set(left, top, right, bottom)
		canvas.drawRoundRect(rect, radius, radius, paint)
	}

	private fun drawText(
		canvas: Canvas,
		text: String,
		x: Float,
		baseline: Float,
		size: Float,
		@ColorInt color: Int,
		typeface: Typeface,
	) {
		textPaint.textSize = size
		textPaint.typeface = typeface
		textPaint.color = color.withAlpha()
		canvas.drawText(text, x, baseline, textPaint)
	}

	private fun drawIcon(
		canvas: Canvas,
		icon: Drawable?,
		left: Float,
		top: Float,
		size: Float,
	) {
		icon ?: return
		canvas.withSave {
			translate(left, top)
			icon.setBounds(0, 0, size.toInt(), size.toInt())
			icon.alpha = drawableAlpha
			icon.colorFilter = paint.colorFilter
			icon.draw(this)
		}
	}

	private fun Int.withAlpha(): Int = ColorUtils.setAlphaComponent(this, Color.alpha(this) * drawableAlpha / 255)

	private fun dimenDp(
		@DimenRes resId: Int,
	): Float = context.resources.getDimension(resId) / density

	private fun createNavItems(items: List<NavItem>): List<Label> {
		val active = items.indexOf(NavItem.HISTORY).coerceAtLeast(0)
		return items.mapIndexed { i, item ->
			val isActive = i == active
			val icon =
				context.icon(item.icon, if (isActive) onSecondaryContainer else onSurfaceVariant)?.apply {
					state = if (isActive) intArrayOf(android.R.attr.state_checked) else intArrayOf()
					jumpToCurrentState()
				}
			Label(context.getString(item.title), icon)
		}
	}

	private fun label(
		@StringRes titleResId: Int,
		@DrawableRes iconResId: Int,
		@ColorInt iconColor: Int,
	) = Label(context.getString(titleResId), context.icon(iconResId, iconColor))

	data class Config(
		val width: Float = 411f,
		val height: Float = 891f,
		val statusBar: Float = 24f,
		val bottomInset: Float = 0f,
		val showQuickFilters: Boolean = true,
		val navItems: List<NavItem> = listOf(NavItem.HISTORY, NavItem.FAVORITES, NavItem.EXPLORE),
		val listMode: ListMode = ListMode.GRID,
		val gridScale: Float = 1f,
	)

	class Group(
		val title: String?,
		val items: List<Item>,
	)

	class Item(
		val mangaId: Long,
		val titleLength: Int,
		val progress: Float,
		val progressText: String,
	)

	private class Label(
		val title: String,
		val icon: Drawable?,
	) {
		fun textWidth(
			paint: Paint,
			size: Float,
			typeface: Typeface,
		): Float {
			paint.textSize = size
			paint.typeface = typeface
			return paint.measureText(title)
		}
	}

	private companion object {
		const val STATUS_TIME = "12:30"
		const val NAVIGATION_HEIGHT = 80f
		const val FAB_BOTTOM_MARGIN = 27.7f
		const val QUICK_FILTER_HEIGHT = 40.2f
		const val HEADER_HEIGHT = 41.9f
		const val PROGRESS_STROKE = 4f
		const val PROGRESS_FIT_TEXT = "100%"

		const val LINE_HEIGHT = 1.172f
		const val FONT_PADDING = 0.42f
		const val CHAR_WIDTH = 0.52f

		fun Context.icon(
			@DrawableRes resId: Int,
			@ColorInt color: Int,
		): Drawable? =
			AppCompatResources.getDrawable(this, resId)?.mutate()?.also {
				it.setTint(color)
			}
	}
}
