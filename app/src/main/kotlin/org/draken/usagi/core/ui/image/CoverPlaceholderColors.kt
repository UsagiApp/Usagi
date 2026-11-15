package org.draken.usagi.core.ui.image

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.res.use
import com.google.android.material.R as materialR

class CoverPlaceholderColors(
	context: Context,
) {
	private val backgrounds = context.resolveColors(BACKGROUND_ATTRS)
	private val contents = context.resolveColors(CONTENT_ATTRS)

	@ColorInt
	fun background(mangaId: Long): Int = backgrounds[index(mangaId)]

	@ColorInt
	fun content(mangaId: Long): Int = contents[index(mangaId)]

	private fun index(mangaId: Long): Int {
		val seed = (mangaId xor (mangaId ushr 32)).toInt() * SEED_MULTIPLIER
		return Math.floorMod(seed, backgrounds.size)
	}

	private fun Context.resolveColors(attrs: IntArray): IntArray =
		obtainStyledAttributes(attrs).use { ta ->
			IntArray(attrs.size) { ta.getColor(it, 0) }
		}

	private companion object {
		const val SEED_MULTIPLIER = -0x61c88647

		val BACKGROUND_ATTRS =
			intArrayOf(
				materialR.attr.colorPrimaryContainer,
				materialR.attr.colorSecondaryContainer,
				materialR.attr.colorTertiaryContainer,
				materialR.attr.colorPrimaryFixedDim,
				materialR.attr.colorSecondaryFixedDim,
				materialR.attr.colorTertiaryFixedDim,
				materialR.attr.colorSurfaceContainerHighest,
			)

		val CONTENT_ATTRS =
			intArrayOf(
				materialR.attr.colorOnPrimaryContainer,
				materialR.attr.colorOnSecondaryContainer,
				materialR.attr.colorOnTertiaryContainer,
				materialR.attr.colorOnPrimaryFixedVariant,
				materialR.attr.colorOnSecondaryFixedVariant,
				materialR.attr.colorOnTertiaryFixedVariant,
				materialR.attr.colorOnSurface,
			)
	}
}
