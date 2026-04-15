package org.draken.usagi.core.parser.favicon

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import coil3.ColorImage
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri
import coil3.toBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toOkioPath
import org.draken.usagi.R
import org.draken.usagi.core.exceptions.CloudFlareProtectedException
import org.draken.usagi.core.model.TachiyomiPluginSource
import org.draken.usagi.core.model.unwrap
import org.draken.usagi.core.parser.EmptyMangaRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.parser.ParserMangaRepository
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.parser.external.ExternalMangaRepository
import org.draken.usagi.core.util.MimeTypes
import org.draken.usagi.core.util.ext.fetch
import org.draken.usagi.core.util.ext.mangaSourceKey
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.toMimeTypeOrNull
import org.draken.usagi.local.data.FaviconCache
import org.draken.usagi.local.data.LocalMangaRepository
import org.draken.usagi.local.data.LocalStorageCache
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import coil3.Uri as CoilUri

class FaviconFetcher(
	private val uri: Uri,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localStorageCache: LocalStorageCache,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		val mangaSource = options.extras[mangaSourceKey] ?: org.draken.usagi.core.model.MangaSource(uri.schemeSpecificPart)

		return when (val repo = mangaRepositoryFactory.create(mangaSource)) {
			is ParserMangaRepository -> fetchParserFavicon(repo)
			is ExternalMangaRepository -> fetchPluginIcon(repo)
			is EmptyMangaRepository -> ImageFetchResult(
				image = ColorImage(Color.WHITE),
				isSampled = false,
				dataSource = DataSource.MEMORY,
			)

			is LocalMangaRepository -> imageLoader.fetch(R.drawable.ic_storage, options)

			else -> throw IllegalArgumentException("Unsupported repo ${repo.javaClass.simpleName}")
		}
	}

	private suspend fun fetchParserFavicon(repository: ParserMangaRepository): FetchResult {
		fetchTachiyomiApkIcon(repository.source)?.let { return it }
		val sizePx = maxOf(
			options.size.width.pxOrElse { FALLBACK_SIZE },
			options.size.height.pxOrElse { FALLBACK_SIZE },
		)
		val cacheKey = options.diskCacheKey ?: "${repository.source.name}_$sizePx"
		if (options.diskCachePolicy.readEnabled) {
			localStorageCache[cacheKey]?.let { file ->
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
					mimeType = MimeTypes.probeMimeType(file)?.toString(),
					dataSource = DataSource.DISK,
				)
			}
		}
		var favicons = repository.getFavicons()
		var lastError: Exception? = null
		while (favicons.isNotEmpty()) {
			currentCoroutineContext().ensureActive()
			val icon = favicons.find(sizePx) ?: throwNSEE(lastError)
			try {
				val result = imageLoader.fetch(icon.url, options)
				if (result != null) {
					return if (options.diskCachePolicy.writeEnabled) {
						writeToCache(cacheKey, result)
					} else {
						result
					}
				} else {
					favicons -= icon
				}
			} catch (e: CloudFlareProtectedException) {
				throw e
			} catch (e: IOException) {
				lastError = e
				favicons -= icon
			}
		}
		throwNSEE(lastError)
	}

	private suspend fun fetchTachiyomiApkIcon(source: MangaSource): FetchResult? {
		val pluginSource = when (val unwrapped = source.unwrap()) {
			is TachiyomiPluginSource -> unwrapped
			else -> null
		} ?: return null
		val pluginFile = File(PluginFileLoader.pluginsDir(options.context), pluginSource.pluginFileName)
		if (!pluginFile.exists()) return null
		val icon = runCatchingCancellable {
			runInterruptible {
				val pm = options.context.packageManager
				val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					pm.getPackageArchiveInfo(
						pluginFile.absolutePath,
						PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
					)
				} else {
					@Suppress("DEPRECATION")
					pm.getPackageArchiveInfo(pluginFile.absolutePath, PackageManager.GET_META_DATA)
				}
				val appInfo = packageInfo?.applicationInfo ?: return@runInterruptible null
				appInfo.sourceDir = pluginFile.absolutePath
				appInfo.publicSourceDir = pluginFile.absolutePath
				appInfo.loadIcon(pm)
			}
		}.getOrNull() ?: return null
		return ImageFetchResult(
			image = icon.nonAdaptive().asImage(),
			isSampled = false,
			dataSource = DataSource.DISK,
		)
	}

	private suspend fun fetchPluginIcon(repository: ExternalMangaRepository): FetchResult {
		val source = repository.source
		val pm = options.context.packageManager
		val icon = runInterruptible {
			val provider = pm.resolveContentProvider(source.authority, 0)
			provider?.loadIcon(pm) ?: pm.getApplicationIcon(source.packageName)
		}
		return ImageFetchResult(
			image = icon.nonAdaptive().asImage(),
			isSampled = false,
			dataSource = DataSource.DISK,
		)
	}

	private suspend fun writeToCache(key: String, result: FetchResult): FetchResult = runCatchingCancellable {
		when (result) {
			is ImageFetchResult -> {
				if (result.dataSource == DataSource.NETWORK) {
					localStorageCache.set(key, result.image.toBitmap()).asFetchResult()
				} else {
					result
				}
			}

			is SourceFetchResult -> {
				if (result.dataSource == DataSource.NETWORK) {
					result.source.source().use {
						localStorageCache.set(key, it, result.mimeType?.toMimeTypeOrNull()).asFetchResult()
					}
				} else {
					result
				}
			}
		}
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(result)

	private fun File.asFetchResult() = SourceFetchResult(
		source = ImageSource(toOkioPath(), FileSystem.SYSTEM),
		mimeType = MimeTypes.probeMimeType(this)?.toString(),
		dataSource = DataSource.DISK,
	)

	class Factory @Inject constructor(
		private val mangaRepositoryFactory: MangaRepository.Factory,
		@FaviconCache private val faviconCache: LocalStorageCache,
	) : Fetcher.Factory<CoilUri> {

		override fun create(
			data: CoilUri,
			options: Options,
			imageLoader: ImageLoader
		): Fetcher? = if (data.scheme == URI_SCHEME_FAVICON) {
			FaviconFetcher(data.toAndroidUri(), options, imageLoader, mangaRepositoryFactory, faviconCache)
		} else {
			null
		}
	}

	private companion object {

		const val FALLBACK_SIZE = 9999 // largest icon

		private fun throwNSEE(lastError: Exception?): Nothing {
			if (lastError != null) {
				throw lastError
			} else {
				throw NoSuchElementException("No favicons found")
			}
		}

		private fun Drawable.nonAdaptive() =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this is AdaptiveIconDrawable) {
				LayerDrawable(arrayOf(background, foreground))
			} else {
				this
			}

	}
}
