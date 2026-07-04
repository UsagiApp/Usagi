package org.draken.usagi.core.exceptions

import org.draken.usagi.core.model.UnknownMangaSource
import tsuki.model.MangaSource
import tsuki.network.CloudFlareHelper

class CloudFlareBlockedException(
	override val url: String,
	source: MangaSource?,
) : CloudFlareException("Blocked by CloudFlare", CloudFlareHelper.PROTECTION_BLOCKED) {

	override val source: MangaSource = source ?: UnknownMangaSource
}
