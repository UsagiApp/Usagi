package org.draken.usagi.core.parser

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

object PluginFileLoader {

	private const val DIR_NAME = "plugins"
	private const val PARTIAL_SUFFIX = ".partial"

	fun pluginsDir(context: Context): File =
		File(context.filesDir, DIR_NAME).also { it.mkdirs() }

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
				if (!destJar.delete()) throw IOException()
			}
			if (!partial.renameTo(destJar)) {
				partial.copyTo(destJar, overwrite = true)
				if (!partial.delete()) throw IOException()
			}
		} catch (t: Throwable) {
			partial.delete()
			throw t
		}
	}
}
