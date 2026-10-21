package org.draken.usagi.core.ui.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.annotation.StyleRes
import androidx.core.content.getSystemService
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.MaterialColorUtilitiesHelper
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeFruitSalad
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeRainbow
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import org.draken.usagi.core.prefs.ColorScheme
import org.draken.usagi.core.prefs.PaletteStyle

fun Context.getPaletteSeed(
	scheme: ColorScheme,
	style: PaletteStyle,
	@ColorInt customColor: Int,
): Int? =
	when {
		scheme == ColorScheme.CUSTOM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
			customColor
		}

		scheme == ColorScheme.MONET && style != PaletteStyle.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			getColor(android.R.color.system_accent1_500)
		}

		else -> {
			null
		}
	}

@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.R)
fun Activity.applyPalette(
	@ColorInt seed: Int,
	style: PaletteStyle,
) {
	ColorResourcesOverride.getInstance()?.applyIfPossible(this, createPaletteColors(seed, style))
}

@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.R)
fun Context.createPaletteContext(
	@StyleRes themeResId: Int,
	@ColorInt seed: Int,
	style: PaletteStyle,
): Context {
	val colors = createPaletteColors(seed, style)
	val context = ContextThemeWrapper(this, themeResId)
	context.applyOverrideConfiguration(Configuration())
	context.theme
	ColorResourcesOverride.getInstance()?.applyIfPossible(context, colors)
	return context
}

@SuppressLint("RestrictedApi")
private fun Context.createPaletteColors(
	@ColorInt seed: Int,
	style: PaletteStyle,
): Map<Int, Int> {
	val source = Hct.fromInt(seed)
	val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
	val contrast =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			getSystemService<UiModeManager>()?.contrast?.toDouble() ?: 0.0
		} else {
			0.0
		}
	val scheme =
		when (style) {
			PaletteStyle.SYSTEM,
			PaletteStyle.TONAL_SPOT,
			-> SchemeTonalSpot(source, isDark, contrast)

			PaletteStyle.NEUTRAL -> SchemeNeutral(source, isDark, contrast)

			PaletteStyle.VIBRANT -> SchemeVibrant(source, isDark, contrast)

			PaletteStyle.EXPRESSIVE -> SchemeExpressive(source, isDark, contrast)

			PaletteStyle.FIDELITY -> SchemeFidelity(source, isDark, contrast)

			PaletteStyle.CONTENT -> SchemeContent(source, isDark, contrast)

			PaletteStyle.RAINBOW -> SchemeRainbow(source, isDark, contrast)

			PaletteStyle.FRUIT_SALAD -> SchemeFruitSalad(source, isDark, contrast)

			PaletteStyle.MONOCHROME -> SchemeMonochrome(source, isDark, contrast)
		}
	return MaterialColorUtilitiesHelper.createColorResourcesIdsToColorValues(scheme)
}
