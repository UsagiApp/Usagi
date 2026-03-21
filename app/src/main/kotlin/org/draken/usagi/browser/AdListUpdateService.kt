package org.draken.usagi.browser

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.core.network.webview.adblock.AdBlock
import org.draken.usagi.core.ui.CoroutineIntentService
import javax.inject.Inject

@AndroidEntryPoint
class AdListUpdateService : CoroutineIntentService() {

	@Inject
	lateinit var updater: AdBlock.Updater

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		updater.updateList()
	}

	override fun IntentJobContext.onError(error: Throwable) = Unit
}
