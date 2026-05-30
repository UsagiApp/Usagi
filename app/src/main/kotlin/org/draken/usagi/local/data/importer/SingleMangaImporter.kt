package org.draken.usagi.local.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.draken.usagi.core.exceptions.UnsupportedFileException
import org.draken.usagi.core.util.ext.openSource
import org.draken.usagi.core.util.ext.resolveName
import org.draken.usagi.core.util.ext.writeAllCancellable
import org.draken.usagi.local.data.LocalStorageChanges
import org.draken.usagi.local.data.LocalStorageManager
import org.draken.usagi.local.data.hasPdfExtension
import org.draken.usagi.local.data.hasZipExtension
import org.draken.usagi.local.data.input.LocalMangaParser
import org.draken.usagi.local.data.isPdfUri
import org.draken.usagi.local.domain.model.LocalManga
import java.io.File
import java.io.IOException
import javax.inject.Inject

@Reusable
class SingleMangaImporter @Inject constructor(
	@param:ApplicationContext private val context: Context,
	private val storageManager: LocalStorageManager,
	@param:LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

	private val contentResolver = context.contentResolver

	suspend fun import(uri: Uri): LocalManga {
		val result = if (isDirectory(uri) || isPdfUri(contentResolver, uri)) {
			importDirectory(uri)
		} else {
			importFile(uri)
		}
		localStorageChanges.emit(result)
		return result
	}

	private suspend fun importFile(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val contentResolver = storageManager.contentResolver
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		if (!hasZipExtension(name)) {
			throw UnsupportedFileException("Unsupported file $name on $uri")
		}
		val dest = File(getOutputDir(), name)
		runInterruptible {
			contentResolver.openSource(uri)
		}.use { source ->
			dest.sink().buffer().use { output ->
				output.writeAllCancellable(source)
			}
		}
		parseManga(dest)
	}

	private suspend fun importDirectory(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		val dest = when {
			hasPdfExtension(name) || isPdfUri(contentResolver, uri) -> importPdf(uri, name)
			else -> importDocumentTree(uri)
		}
		parseManga(dest)
	}

	private suspend fun importPdf(uri: Uri, name: String): File {
		val outDir = File(getOutputDir(), name.substringBeforeLast('.')).also { it.mkdirs() }
		contentResolver.openFileDescriptor(uri, "r")
			?.use { pdf ->
				PdfRenderer(pdf).use { renderer ->
					repeat(renderer.pageCount) { i ->
						renderer.openPage(i).use { page -> renderPdfPage(page, i, outDir) }
						if (i == 0) localStorageChanges.emit(parseManga(outDir))
					}
				}
			} ?: throw IOException("Cannot open descriptor: $uri")
		return outDir
	}

	private fun renderPdfPage(page: PdfRenderer.Page, index: Int, outDir: File) {
		val scale = (TARGET_PAGE_WIDTH / page.width.toFloat()).coerceIn(1f, MAX_SCALE)
		val bitmap = createBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
			.also { it.eraseColor(Color.WHITE) } // clear white bg
		try {
			val matrix = Matrix().apply { setScale(scale, scale) }
			page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
			File(outDir, "%04d.jpg".format(index + 1)).outputStream()
				.use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
		} finally { bitmap.recycle() }
	}

	private suspend fun importDocumentTree(uri: Uri): File {
		val root = DocumentFile.fromTreeUri(context, uri)
			?: throw IllegalArgumentException("Provided uri $uri is not a tree")
		return File(getOutputDir(), root.requireName())
			.also { dest ->
				dest.mkdir()
				root.listFiles().forEach { it.copyTo(dest) }
			}
	}

	private suspend fun parseManga(file: File): LocalManga = LocalMangaParser(file).getManga(withDetails = false)

	private suspend fun DocumentFile.copyTo(destDir: File) {
		if (isDirectory) {
			val subDir = File(destDir, requireName())
			subDir.mkdir()
			for (docFile in listFiles()) {
				docFile.copyTo(subDir)
			}
		} else {
			source().use { input ->
				File(destDir, requireName()).sink().buffer().use { output ->
					output.writeAllCancellable(input)
				}
			}
		}
	}

	private suspend fun getOutputDir(): File {
		return storageManager.getDefaultWriteableDir() ?: throw IOException("External files dir unavailable")
	}

	private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
		contentResolver.openSource(uri)
	}

	private fun DocumentFile.requireName(): String {
		return name ?: throw IOException("Cannot fetch name from uri: $uri")
	}

	private fun isDirectory(uri: Uri): Boolean {
		return runCatching {
			DocumentFile.fromTreeUri(context, uri)
		}.isSuccess
	}

	companion object {
		private const val TARGET_PAGE_WIDTH = 1200
		private const val MAX_SCALE = 2f
		private const val JPEG_QUALITY = 85
	}
}
