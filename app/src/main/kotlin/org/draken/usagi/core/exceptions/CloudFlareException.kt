package org.draken.usagi.core.exceptions

import okio.IOException
import tsuki.model.MangaSource

abstract class CloudFlareException(
	message: String,
	val state: Int,
) : IOException(message) {

	abstract val url: String

	abstract val source: MangaSource
}
