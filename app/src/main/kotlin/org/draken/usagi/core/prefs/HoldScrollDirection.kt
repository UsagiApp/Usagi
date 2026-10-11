package org.draken.usagi.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.draken.usagi.R

@Keep
enum class HoldScrollDirection(
	@StringRes val labelResId: Int,
	val delta: Int,
) {
	NONE(R.string.none, 0),
	UP(R.string.scroll_up, -1),
	DOWN(R.string.scroll_down, 1),
}
