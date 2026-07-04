package org.draken.usagi.core.exceptions

import tsuki.model.Manga

class UnsupportedSourceException(
	message: String?,
	val manga: Manga?,
) : IllegalArgumentException(message)
