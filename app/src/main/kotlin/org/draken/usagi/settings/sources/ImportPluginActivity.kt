package org.draken.usagi.settings.sources

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.settings.sources.manage.plugins.UpdatePluginsProvider
import javax.inject.Inject

/**
 * Transparent Activity that handles shared GitHub release URLs for plugin import.
 *
 * When a user shares a URL like:
 *   https://github.com/owner/repo/releases/download/tag/plugin.jar
 * with Usagi, this Activity shows a confirmation dialog and downloads the plugin.
 */
@AndroidEntryPoint
class ImportPluginActivity : AppCompatActivity() {

	@Inject
	lateinit var updatePluginsProvider: UpdatePluginsProvider

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) {
			finish()
			return
		}
		val url = extractUrl()
		if (url == null) {
			Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
			finish()
			return
		}
		showConfirmDialog(url)
	}

	private fun extractUrl(): String? {
		return when (intent.action) {
			Intent.ACTION_SEND -> {
				intent.getStringExtra(Intent.EXTRA_TEXT)
					?: intent.getStringExtra(Intent.EXTRA_STREAM)
			}
			else -> null
		}
	}

	private fun showConfirmDialog(url: String) {
		val message = getString(R.string.import_plugin_confirm, url)
		buildAlertDialog(this) {
			setTitle(R.string.confirm)
			setMessage(Html.fromHtml(message))
			setPositiveButton(android.R.string.ok) { _, _ ->
				importPlugin(url)
			}
			setNegativeButton(android.R.string.cancel) { _, _ ->
				finish()
			}
			setOnCancelListener {
				finish()
			}
		}.show()
	}

	private fun importPlugin(url: String) {
		lifecycleScope.launch {
			val success = withContext(Dispatchers.IO) {
				updatePluginsProvider.importFromUrl(url)
			}
			Toast.makeText(
				this@ImportPluginActivity,
				if (success) R.string.load_success else R.string.load_failed,
				Toast.LENGTH_SHORT,
			).show()
			startActivity(
				AppRouter.sourcesSettingsIntent(this@ImportPluginActivity)
					.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
			)
			finish()
		}
	}
}
