package org.draken.usagi.settings.appearance

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.transition.TransitionManager
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.DetailsUiMode
import org.draken.usagi.details.ui.BackdropController.Companion.blurRadius

class PreviewSettingsPreference @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : Preference(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {

	init {
		layoutResource = R.layout.preference_details_preview
		isSelectable = false
		isPersistent = false
	}

	private var backdropView: ImageView? = null
	private var gradientView: View? = null
	private var titleBar: View? = null
	private var subtitleBar: View? = null
	private var infoColumn: LinearLayout? = null
	private var lastMode: DetailsUiMode? = null
	private var cachedBgColor: Int = Color.WHITE
	private var cachedFgColor: Int = Color.DKGRAY

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val root = holder.itemView
		backdropView = root.findViewById(R.id.preview_backdrop)
		gradientView = root.findViewById(R.id.preview_gradient)
		titleBar = root.findViewById(R.id.preview_title)
		subtitleBar = root.findViewById(R.id.preview_subtitle)
		infoColumn = root.findViewById(R.id.preview_info_column)
		lastMode = null
		cachedBgColor = com.google.android.material.color.MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface, Color.WHITE)
		cachedFgColor = obtainAttrColor(android.R.attr.colorForeground, Color.DKGRAY)
		applyStaticDecor()
		applyAllFromPrefs()
	}

	override fun onAttached() {
		super.onAttached()
		preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
	}

	override fun onDetached() {
		preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
		backdropView = null
		gradientView = null
		titleBar = null
		subtitleBar = null
		infoColumn = null
		lastMode = null
		super.onDetached()
	}

	override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT ->
				applyPreviewBlur(sp?.getInt(key, 60) ?: 60)

			AppSettings.KEY_DETAILS_BACKDROP ->
				backdropView?.visibility =
					if (sp?.getBoolean(key, true) != false) View.VISIBLE else View.INVISIBLE

			AppSettings.KEY_DETAILS_UI ->
				rebuildInfoColumn(parseMode(sp?.getString(key, null)))
		}
	}

	private fun applyAllFromPrefs() {
		val prefs = preferenceManager.sharedPreferences ?: return
		val backdropOn = prefs.getBoolean(AppSettings.KEY_DETAILS_BACKDROP, true)
		val blur = prefs.getInt(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT, 60)
		val mode = parseMode(prefs.getString(AppSettings.KEY_DETAILS_UI, null))

		backdropView?.visibility = if (backdropOn) View.VISIBLE else View.INVISIBLE
		applyPreviewBlur(blur)
		rebuildInfoColumn(mode)
	}

	private fun applyStaticDecor() {
		gradientView?.background = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(
				Color.TRANSPARENT,
				ColorUtils.setAlphaComponent(cachedBgColor, 30),
				ColorUtils.setAlphaComponent(cachedBgColor, 140),
				cachedBgColor,
			),
		)
		titleBar?.background = roundedBar(cachedFgColor, 0xCC)
		subtitleBar?.background = roundedBar(cachedFgColor, 0x88)
	}

	@Suppress("DEPRECATION")
	private fun applyPreviewBlur(amount: Int) {
		val view = backdropView ?: return
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			view.setRenderEffect(
				if (amount <= 0) null
				else android.graphics.RenderEffect.createBlurEffect(
					blurRadius(amount, MAX_PREVIEW_BLUR_RADIUS),
					blurRadius(amount, MAX_PREVIEW_BLUR_RADIUS),
					android.graphics.Shader.TileMode.CLAMP,
				),
			)
		} else {
			view.alpha = if (amount <= 0) 0.9f else 0.5f + (1f - amount / 100f) * 0.4f
		}
	}

	private fun rebuildInfoColumn(mode: DetailsUiMode) {
		if (mode == lastMode) return
		lastMode = mode
		val col = infoColumn ?: return
		(col.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it) }
		col.removeAllViews()
		when (mode) {
			DetailsUiMode.CLASSIC -> buildClassicContent(col)
			DetailsUiMode.MODERN -> buildModernContent(col)
		}
	}

	private fun buildClassicContent(parent: LinearLayout) {
		val ctx = parent.context
		val gap = ctx.dp(6)
		val chipH = ctx.dp(22)
		val rows = listOf(
			listOf(ctx.dp(80), ctx.dp(54), ctx.dp(60)),
			listOf(ctx.dp(50), ctx.dp(66)),
		)
		rows.forEachIndexed { ri, widths ->
			val row = LinearLayout(ctx).apply {
				orientation = LinearLayout.HORIZONTAL
				layoutParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
				).also { if (ri > 0) it.topMargin = gap }
			}
			widths.forEachIndexed { ci, w ->
				row.addView(View(ctx).apply {
					layoutParams = LinearLayout.LayoutParams(w, chipH).also {
						if (ci > 0) it.marginStart = gap
					}
					background = GradientDrawable().apply {
						shape = GradientDrawable.RECTANGLE
						cornerRadius = chipH / 2f
						setColor(ColorUtils.setAlphaComponent(cachedFgColor, 0x22))
						setStroke(ctx.dp(1), ColorUtils.setAlphaComponent(cachedFgColor, 0x55))
					}
				})
			}
			parent.addView(row)
		}
	}

	private fun buildModernContent(parent: LinearLayout) {
		val ctx = parent.context
		val card = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			background = GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				cornerRadius = ctx.dp(10).toFloat()
				setColor(ColorUtils.setAlphaComponent(cachedBgColor, 60))
				setStroke(1, ColorUtils.setAlphaComponent(cachedFgColor, 40))
			}
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)
			setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
		}

		val labelWidth = ctx.dp(44)
		val valueWidths = listOf(ctx.dp(72), ctx.dp(56), ctx.dp(44), ctx.dp(36))
		valueWidths.forEachIndexed { i, valWidth ->
			val row = LinearLayout(ctx).apply {
				orientation = LinearLayout.HORIZONTAL
				layoutParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
				).also { if (i > 0) it.topMargin = ctx.dp(4) }
			}
			row.addView(View(ctx).apply {
				layoutParams = LinearLayout.LayoutParams(labelWidth, ctx.dp(6))
				background = roundedBar(cachedFgColor, 0x70)
			})
			row.addView(View(ctx).apply {
				layoutParams = LinearLayout.LayoutParams(valWidth, ctx.dp(6)).also {
					it.marginStart = ctx.dp(6)
				}
				background = roundedBar(cachedFgColor, 0xAA)
			})
			card.addView(row)
		}
		parent.addView(card)
	}

	private fun roundedBar(color: Int, alpha: Int): GradientDrawable =
		GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = 100f
			setColor(ColorUtils.setAlphaComponent(color, alpha))
		}

	private fun obtainAttrColor(@AttrRes attr: Int, default: Int): Int =
		runCatching {
			context.obtainStyledAttributes(intArrayOf(attr)).run {
				getColor(0, default).also { recycle() }
			}
		}.getOrDefault(default)

	private fun Context.dp(value: Int): Int =
		TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
		).toInt()

	private fun parseMode(name: String?): DetailsUiMode =
		DetailsUiMode.entries.firstOrNull { it.name == name } ?: DetailsUiMode.MODERN

	companion object {
		private const val MAX_PREVIEW_BLUR_RADIUS = 20f
	}
}
