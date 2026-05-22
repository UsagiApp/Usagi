package org.draken.usagi.settings.reader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import com.google.android.material.R as materialR

class ReaderPreviewSettingsPreference @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : Preference(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {

	init {
		layoutResource = R.layout.preference_reader_preview
		isSelectable = false
		isPersistent = false
	}

	private var topBar: View? = null
	private var bottomBar: MaterialCardView? = null
	private var bottomTrack: View? = null
	private var barColor: Int = Color.WHITE
	private var fgColor: Int = Color.DKGRAY

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val root = holder.itemView
		topBar = root.findViewById(R.id.preview_top_bar)
		bottomBar = root.findViewById(R.id.preview_bottom_bar)
		bottomTrack = root.findViewById(R.id.preview_bottom_track)
		barColor = MaterialColors.getColor(root, materialR.attr.colorSurfaceContainer, Color.WHITE)
		fgColor = MaterialColors.getColor(root, materialR.attr.colorOnSurface, Color.DKGRAY)
		root.findViewById<View>(R.id.preview_top_title)?.background = pill(fgColor, 0xCC)
		root.findViewById<View>(R.id.preview_top_subtitle)?.background = pill(fgColor, 0x88)
		applyFromPrefs()
	}

	override fun onAttached() {
		super.onAttached()
		preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
	}

	override fun onDetached() {
		preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
		topBar = null
		bottomBar = null
		bottomTrack = null
		super.onDetached()
	}

	override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
		when (key) {
			null, AppSettings.KEY_READER_TOP_BAR_OPACITY,
			AppSettings.KEY_READER_BOTTOM_BAR_OPACITY, AppSettings.KEY_READER_IS_FLOAT_BAR -> applyFromPrefs()
		}
	}

	private fun applyFromPrefs() {
		val prefs = preferenceManager.sharedPreferences ?: return
		val topOpacity = prefs.getInt(AppSettings.KEY_READER_TOP_BAR_OPACITY, 50)
		val bottomOpacity = prefs.getInt(AppSettings.KEY_READER_BOTTOM_BAR_OPACITY, 50)
		applyBar(topBar, topOpacity)
		applyBar(bottomBar, bottomOpacity)

		val bgAlpha = readerBarBgAlpha(bottomOpacity)
		val trackAlpha = if (bgAlpha >= 255) 0x99 else (bgAlpha * 0.6f).toInt()
		bottomTrack?.background = pill(fgColor, trackAlpha)

		val floating = prefs.getBoolean(AppSettings.KEY_READER_IS_FLOAT_BAR, true)
		val bar = bottomBar ?: return
		val h = context.resources.getDimensionPixelSize(R.dimen.reader_toolbar_float_margin_h)
		val gap = context.resources.getDimensionPixelSize(R.dimen.reader_toolbar_float_gap)
		(bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
			if (floating) {
				lp.leftMargin = h
				lp.rightMargin = h
				lp.bottomMargin = gap
				bar.radius = context.resources.getDimension(R.dimen.reader_toolbar_corner_radius)
				bar.elevation = if (bgAlpha >= 255) {
					context.resources.getDimension(R.dimen.reader_toolbar_float_elevation)
				} else { 0f }
			} else {
				lp.leftMargin = 0
				lp.rightMargin = 0
				lp.bottomMargin = 0
				bar.radius = 0f
				bar.elevation = 0f
			}
			bar.layoutParams = lp
		}
	}

	private fun applyBar(bar: View?, transparency: Int) {
		bar ?: return
		val color = ColorUtils.setAlphaComponent(barColor, readerBarBgAlpha(transparency))
		when (bar) {
			is MaterialCardView -> bar.setCardBackgroundColor(color)
			else -> bar.setBackgroundColor(color)
		}
	}

	private fun pill(color: Int, alpha: Int) = GradientDrawable().apply {
		shape = GradientDrawable.RECTANGLE
		cornerRadius = 100f
		setColor(ColorUtils.setAlphaComponent(color, alpha))
	}

	private fun readerBarBgAlpha(transparency: Int): Int {
		val t = transparency.coerceIn(50, 100)
		return (255 - (t - 50) * 127f / 50f).toInt()
	}
}
