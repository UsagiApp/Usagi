package org.draken.usagi.settings.sources

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.settings.sources.manage.plugins.UpdatePluginsProvider
import java.io.File
import javax.inject.Inject

/**
 * Transparent Activity that handles shared GitHub release URLs for plugin import.
 *
 * When a user shares a URL like:
 *   https://github.com/user_or_orgs/repository/releases/download/tag/plugin_name.jar
 * with Usagi, this Activity shows a confirmation dialog and downloads the plugin.
 */
@AndroidEntryPoint
class ImportPluginActivity : AppCompatActivity() {

	@Inject
	lateinit var mangaDynamicRepository: MangaDynamicRepository

	@Inject
	lateinit var updatePluginsProvider: UpdatePluginsProvider

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) {
			finish()
			return
		}
		val url = extractUrl()
		if (url == null || !isPluginDownloadUrl(url)) {
			Toast.makeText(this, R.string.import_plugin_url_invalid, Toast.LENGTH_SHORT).show()
			finish()
			return
		}
		val parsed = parsePluginUrl(url)
		if (parsed == null) {
			Toast.makeText(this, R.string.import_plugin_url_invalid, Toast.LENGTH_SHORT).show()
			finish()
			return
		}
		showConfirmDialog(parsed, url)
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

	private fun isPluginDownloadUrl(url: String): Boolean {
		val trimmed = url.trim()
		if (!trimmed.contains("github.com", ignoreCase = true)) return false
		if (!trimmed.contains("releases", ignoreCase = true)) return false
		if (!trimmed.endsWith(".jar", ignoreCase = true)) return false
		return true
	}

	private fun parsePluginUrl(url: String): PluginUrlInfo? {
		val regex = Regex(
			"""https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+\.jar)""",
			RegexOption.IGNORE_CASE,
		)
		val match = regex.find(url.trim()) ?: return null
		val (owner, repo, tag, fileName) = match.destructured
		if (owner.isBlank() || repo.isBlank() || tag.isBlank() || fileName.isBlank()) return null
		return PluginUrlInfo(
			owner = owner,
			repo = repo,
			tag = tag,
			fileName = fileName,
			repository = "$owner/$repo",
		)
	}

	private fun showConfirmDialog(info: PluginUrlInfo, url: String) {
		val message = getString(
			R.string.import_plugin_confirm,
			info.fileName,
			info.repository,
		)
		AlertDialog.Builder(this)
			.setTitle(R.string.import_plugin_from_url)
			.setMessage(Html.fromHtml(message))
			.setPositiveButton(R.string.ok) { _, _ ->
				downloadAndInstall(url, info)
			}
			.setNegativeButton(R.string.cancel) { _, _ ->
				finish()
			}
			.setOnCancelListener {
				finish()
			}
			.show()
	}

	private fun downloadAndInstall(url: String, info: PluginUrlInfo) {
		lifecycleScope.launch {
			val success = withContext(Dispatchers.IO) {
				runCatching {
					val pluginsDir = mangaDynamicRepository.getDir()
					val outFile = File(pluginsDir, info.fileName)
					val downloaded = updatePluginsProvider.replacePlugin(url, outFile)
					if (!downloaded) return@runCatching false
					updatePluginsProvider.saveDto(info.fileName, info.repository, info.tag)
					mangaDynamicRepository.load(pluginsDir)
					true
				}.getOrDefault(false)
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

	private data class PluginUrlInfo(
		val owner: String,
		val repo: String,
		val tag: String,
		val fileName: String,
		val repository: String,
	)
}
