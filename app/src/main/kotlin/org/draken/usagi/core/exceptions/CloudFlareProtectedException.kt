package org.draken.usagi.core.exceptions

import okhttp3.Headers
import org.draken.usagi.core.model.UnknownMangaSource
import tsuki.model.MangaSource
import tsuki.network.CloudFlareHelper

class CloudFlareProtectedException(
	override val url: String,
	source: MangaSource?,
	@Transient val headers: Headers,
) : CloudFlareException("Protected by CloudFlare", CloudFlareHelper.PROTECTION_CAPTCHA) {

	override val source: MangaSource = source ?: UnknownMangaSource
}
