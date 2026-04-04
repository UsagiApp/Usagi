package org.draken.usagi.core.parser

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object PluginFileLoader {

	private const val DIR_NAME = "plugins"
	private const val PARTIAL_SUFFIX = ".partial"

	fun pluginsDir(context: Context): File =
		File(context.filesDir, DIR_NAME).also { it.mkdirs() }

	@WorkerThread
	@Throws(IOException::class)
	fun copyFromUri(context: Context, uri: Uri, destJar: File) {
		val dir = destJar.parentFile ?: throw IOException("no parent dir")
		dir.mkdirs()
		val partial = File(dir, destJar.name + PARTIAL_SUFFIX)
		try {
			if (partial.exists() && !partial.delete()) throw IOException("stale partial")
			val input = context.contentResolver.openInputStream(uri)
				?: throw FileNotFoundException(uri.toString())
			input.use { stream ->
				partial.outputStream().use { out -> stream.copyTo(out) }
			}
			if (destJar.exists()) {
				destJar.setWritable(true, true)
				if (!destJar.delete()) throw IOException("replace plugin")
			}
			if (!partial.renameTo(destJar)) {
				partial.copyTo(destJar, overwrite = true)
				if (!partial.delete()) throw IOException("cleanup partial")
			}
		} catch (t: Throwable) {
			partial.delete()
			throw t
		}
	}
}
