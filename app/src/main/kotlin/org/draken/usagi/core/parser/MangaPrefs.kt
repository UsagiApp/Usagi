package org.draken.usagi.core.parser

import org.draken.usagi.core.prefs.DownscaleMode
import org.draken.usagi.reader.domain.ReaderColorFilter

data class MangaPrefs(
	val colorFilter: ReaderColorFilter?,
	val downscaleMode: DownscaleMode?,
) {
	companion object {
		val DEFAULT = MangaPrefs(null, null)
	}
}
