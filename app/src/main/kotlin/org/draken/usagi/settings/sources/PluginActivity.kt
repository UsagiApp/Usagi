package org.draken.usagi.settings.sources

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.util.ext.getParcelableExtraCompat
import java.io.File
import java.util.Locale

class PluginActivity : AppCompatActivity() {

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
					importJar(uri)
				}.isSuccess
			}
			finishWithResult(isSuccess)
		}
	}

	private fun importJar(uri: Uri) {
		val pluginsDir = PluginFileLoader.pluginsDir(this)
		val outFile = File(pluginsDir, resolveOutputFileName(uri))
		PluginFileLoader.copyFromUri(this, uri, outFile)
		DynamicParserManager.loadParsersFromDirectory(this, pluginsDir)
	}

	private fun resolveOutputFileName(uri: Uri): String {
		val originalName = DocumentFile.fromSingleUri(this, uri)?.name
			?: uri.lastPathSegment?.substringAfterLast('/')
			?: "plugin_${System.currentTimeMillis()}.jar"
		val safeName = originalName
			.replace('/', '_')
			.replace('\\', '_')
			.ifBlank { "plugin_${System.currentTimeMillis()}.jar" }
		return if (safeName.lowercase(Locale.ROOT).endsWith(".jar")) safeName else "$safeName.jar"
	}

	private fun isSupported(uri: Uri): Boolean {
		val type = intent.type?.lowercase(Locale.ROOT)
		if (type in SUPPORTED_MIME_TYPES) {
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

	private companion object {
		val SUPPORTED_MIME_TYPES = setOf(
			"application/java-archive",
			"application/x-java-archive",
			"application/vnd.android.package-archive",
			"application/octet-stream",
		)
	}
}
