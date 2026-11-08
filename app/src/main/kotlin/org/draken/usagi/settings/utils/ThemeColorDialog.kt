package org.draken.usagi.settings.utils

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isNotEmpty
import androidx.core.view.updateMarginsRelative
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.AlertDialogFragment
import org.draken.usagi.core.util.ext.toHexColor
import org.draken.usagi.databinding.DialogThemeColorBinding
import javax.inject.Inject

@AndroidEntryPoint
class ThemeColorDialog : AlertDialogFragment<DialogThemeColorBinding>() {
	@Inject
	lateinit var settings: AppSettings

	private var color = Color.BLACK

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = DialogThemeColorBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: DialogThemeColorBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		color = savedInstanceState?.getInt(KEY_COLOR) ?: settings.customThemeColor
		val swatchSize = resources.getDimensionPixelSize(R.dimen.color_swatch_size)
		val spacing = resources.getDimensionPixelOffset(R.dimen.list_spacing_small)
		val seeds =
			resources.obtainTypedArray(R.array.theme_seed_colors).use { array ->
				IntArray(array.length()) { array.getColor(it, Color.BLACK) }
			}
		for (seed in seeds) {
			val swatch =
				ImageView(binding.root.context).apply {
					tag = seed
					contentDescription = seed.toHexColor()
					scaleType = ImageView.ScaleType.CENTER
					setBackgroundResource(R.drawable.bg_color_swatch)
					ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(seed))
					imageTintList = ColorStateList.valueOf(if (isLight(seed)) Color.BLACK else Color.WHITE)
					setOnClickListener { setColor(seed) }
				}
			binding.layoutSwatches.addView(
				swatch,
				LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
					if (binding.layoutSwatches.isNotEmpty()) {
						updateMarginsRelative(start = spacing)
					}
				},
			)
		}
		binding.picker.onColorChangeListener = ::setColor
		binding.editHex.doAfterTextChanged { text ->
			val hex = text?.toString().orEmpty()
			if (hex.length == HEX_LENGTH) {
				setColor("#$hex".toColorInt())
			}
		}
		setColor(color)
	}

	override fun onBuildDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder =
		super
			.onBuildDialog(builder)
			.setTitle(R.string.theme_color)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				settings.customThemeColor = color
			}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putInt(KEY_COLOR, color)
	}

	private fun setColor(value: Int) {
		color = ColorUtils.setAlphaComponent(value, 0xFF)
		val binding = viewBinding ?: return
		if (binding.picker.color != color) {
			binding.picker.color = color
		}
		val hex = color.toHexColor().removePrefix("#")
		if (!binding.editHex.text.contentEquals(hex, ignoreCase = true)) {
			binding.editHex.setText(hex)
			binding.editHex.setSelection(hex.length)
		}
		binding.viewPreview.setBackgroundColor(color)
		for (swatch in binding.layoutSwatches.children) {
			(swatch as ImageView).setImageResource(if (swatch.tag == color) R.drawable.ic_check else 0)
		}
	}

	private fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.5

	private companion object {
		const val KEY_COLOR = "color"
		const val HEX_LENGTH = 6
	}
}
