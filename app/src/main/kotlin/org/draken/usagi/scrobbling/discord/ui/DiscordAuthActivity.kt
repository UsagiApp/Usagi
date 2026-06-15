package org.draken.usagi.scrobbling.discord.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.scrobbling.discord.data.DiscordRepository
import javax.inject.Inject
import androidx.core.net.toUri

@AndroidEntryPoint
class DiscordAuthActivity : ComponentActivity() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var repository: DiscordRepository

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		handleIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent) {
		val data = intent.data
		if (data != null && (data.scheme == "usagi" || data.scheme == "kotatsu") && data.host == "discord-auth") {
			val code = data.getQueryParameter("code")
			if (code != null) {
				lifecycleScope.launch {
					try {
						repository.authorize(code)
						setResult(RESULT_OK)
						finish()
					} catch (e: Exception) {
						e.printStackTrace()
						startAuth()
					}
				}
			} else { finish() }
		} else { startAuth() }
	}

	private fun startAuth() {
		val discordUri = repository.oauthUrl.toUri()
		val intent = Intent(Intent.ACTION_VIEW, discordUri).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}

		try {
			startActivity(intent)
		} catch (_: Exception) {
			intent.data = repository.oauthFallbackUrl.toUri()
			try { startActivity(intent) } catch (e: Exception) {
				e.printStackTrace()
				finish()
			}
		}
	}
}
