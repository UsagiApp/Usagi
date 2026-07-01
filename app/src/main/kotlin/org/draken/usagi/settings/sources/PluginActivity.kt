package org.draken.usagi.settings.sources

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.PluginKeyResolver
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.util.ext.getParcelableExtraCompat
import org.draken.usagi.filter.data.SavedFiltersRepository
import java.io.File
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PluginActivity : AppCompatActivity() {

	@Inject
	lateinit var mangaDynamicRepository: MangaDynamicRepository

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var savedFiltersRepository: SavedFiltersRepository

	@Inject
	lateinit var pluginKeyResolver: PluginKeyResolver

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) {
			finish()
			return
		}
		val uri = intent.extractInputUri()
		if (uri == null || !isSupported(uri)) {
			finishWithResult(false)
			return
		}
		lifecycleScope.launch {
			val isSuccess = withContext(Dispatchers.IO) {
				runCatching {
					import(uri)
				}.isSuccess
			}
			finishWithResult(isSuccess)
		}
	}

	private suspend fun import(uri: Uri) {
		val pluginsDir = mangaDynamicRepository.getDir()
		val outFile = File(pluginsDir, resolve(uri))
		PluginFileLoader.copyFromUri(this, uri, outFile)
		mangaDynamicRepository.load(pluginsDir)
		withContext(Dispatchers.Default) {
			pluginKeyResolver.normalize(database, savedFiltersRepository)
		}
	}

	private fun resolve(uri: Uri): String {
		val originalName = DocumentFile.fromSingleUri(this, uri)?.name
			?: uri.lastPathSegment?.substringAfterLast('/')
			?: "plugin_${System.currentTimeMillis()}.jar"
		return PluginFileLoader.resolve(originalName)
	}

	private fun isSupported(uri: Uri): Boolean {
		val type = intent.type?.lowercase(Locale.ROOT)
		if (type in PluginFileLoader.SUPPORTED_MIME_TYPES) {
			return true
		}
		val name = DocumentFile.fromSingleUri(this, uri)?.name
			?: uri.lastPathSegment
			?: return false
		return name.lowercase(Locale.ROOT).endsWith(".jar")
	}

	private fun finishWithResult(isSuccess: Boolean) {
		Toast.makeText(
			applicationContext,
			if (isSuccess) R.string.load_success else R.string.load_failed,
			Toast.LENGTH_LONG,
		).show()
		startActivity(
			AppRouter.sourcesSettingsIntent(this)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
		finish()
	}

	private fun Intent.extractInputUri(): Uri? = when (action) {
		Intent.ACTION_VIEW -> data
		Intent.ACTION_SEND -> getParcelableExtraCompat(Intent.EXTRA_STREAM)
		else -> data ?: getParcelableExtraCompat(Intent.EXTRA_STREAM)
	}
}
