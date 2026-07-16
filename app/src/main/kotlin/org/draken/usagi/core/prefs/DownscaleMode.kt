package org.draken.usagi.core.prefs

import androidx.annotation.Keep

@Keep
enum class DownscaleMode(val value: Int) {
	OFF(1),
	X2(2),
	X4(4),
	X8(8);
}
