package org.draken.usagi.core.prefs

import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import org.draken.usagi.R
import tsuki.util.find

@Keep
enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
) {
	DEFAULT(R.style.ThemeOverlay_Usagi_Totoro, R.string.theme_name_totoro),
	MONET(R.style.ThemeOverlay_Usagi_Monet, R.string.theme_name_dynamic),
	CUSTOM(R.style.ThemeOverlay_Usagi_Custom, R.string.theme_name_custom),
	MIKU(R.style.ThemeOverlay_Usagi_Miku, R.string.theme_name_miku),
	RENA(R.style.ThemeOverlay_Usagi_Asuka, R.string.theme_name_asuka),
	FROG(R.style.ThemeOverlay_Usagi_Mion, R.string.theme_name_mion),
	BLUEBERRY(R.style.ThemeOverlay_Usagi_Rikka, R.string.theme_name_rikka),
	SAKURA(R.style.ThemeOverlay_Usagi_Sakura, R.string.theme_name_sakura),
	MAMIMI(R.style.ThemeOverlay_Usagi_Mamimi, R.string.theme_name_mamimi),
	KANADE(R.style.ThemeOverlay_Usagi_Kanade, R.string.theme_name_kanade),
	ITSUKA(R.style.ThemeOverlay_Usagi_Itsuka, R.string.theme_name_itsuka),
	;

	companion object {
		val default: ColorScheme
			get() =
				if (DynamicColors.isDynamicColorAvailable()) {
					MONET
				} else {
					DEFAULT
				}

		fun getAvailableList(): List<ColorScheme> {
			val list = ColorScheme.entries.toMutableList()
			if (!DynamicColors.isDynamicColorAvailable()) {
				list.remove(MONET)
			}
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
				list.remove(CUSTOM)
			}
			return list
		}

		fun safeValueOf(name: String): ColorScheme? = ColorScheme.entries.find(name)
	}
}
