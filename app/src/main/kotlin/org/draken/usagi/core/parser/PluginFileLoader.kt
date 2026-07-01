package org.draken.usagi.core.parser

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

object PluginFileLoader {

	fun pluginsDir(context: Context): File =
		File(context.filesDir, PLUGIN_DIR).also { it.mkdirs() }

	fun resolve(rawName: String): String {
		val sanitized = rawName
			.trim()
			.replace('/', '_')
			.replace('\\', '_')
			.ifBlank { "plugin_${System.currentTimeMillis()}.jar" }
		return if (sanitized.lowercase(Locale.ROOT).endsWith(".jar")) sanitized else "$sanitized.jar"
	}

	@WorkerThread
	@Throws(IOException::class)
	fun copyFromUri(context: Context, uri: Uri, destJar: File) {
		val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
		copyFromStream(destJar, input)
	}

	@WorkerThread
	@Throws(IOException::class)
	fun copyFromStream(destJar: File, input: InputStream) {
		val dir = destJar.parentFile ?: throw IOException()
		dir.mkdirs()
		val partial = File(dir, destJar.name + PARTIAL_SUFFIX)
		try {
			if (partial.exists() && !partial.delete()) throw IOException()
			input.use { stream ->
				partial.outputStream().use { out ->
					stream.copyTo(out)
					out.flush()
				}
			}
			if (destJar.exists()) {
				destJar.setWritable(true, true)
				if (!destJar.delete()) throw IOException("replace plugin")
			}
			if (!partial.renameTo(destJar)) {
				partial.copyTo(destJar, true)
				if (!partial.delete()) throw IOException("cleanup partial")
			}
		} catch (t: Throwable) {
			partial.delete()
			throw t
		}
	}

	private const val PLUGIN_DIR = "plugins"
	private const val PARTIAL_SUFFIX = ".partial"
	val SUPPORTED_MIME_TYPES = arrayOf(
		"application/java-archive",
		"application/x-java-archive",
		"application/vnd.android.package-archive",
		"application/octet-stream",
	)
}
