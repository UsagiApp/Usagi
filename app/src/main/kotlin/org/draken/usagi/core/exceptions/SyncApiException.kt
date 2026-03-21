package org.draken.usagi.core.exceptions

class SyncApiException(
	message: String,
	val code: Int,
) : RuntimeException(message)
