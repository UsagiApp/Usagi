package org.draken.usagi.core.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.draken.usagi.core.model.unwrap
import org.draken.usagi.core.util.ext.mangaSourceKey
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource as External
import coil3.Uri as CoilUri

class ExternalSourceFetcher(
	private val httpSource: HttpSource,
	private val url: String,
	private val options: Options,
) : Fetcher {

	override suspend fun fetch(): FetchResult {
		val response = withContext(Dispatchers.IO) {
			httpSource.client.newCall(
				Request.Builder()
					.url(url)
					.headers(httpSource.headers)
					.build(),
			).awaitSuccess()
		}
		return SourceFetchResult(
			source = ImageSource(response.body.source(), options.fileSystem),
			mimeType = response.body.contentType()?.toString(),
			dataSource = DataSource.NETWORK,
		)
	}

	class Factory : Fetcher.Factory<CoilUri> {

		override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
			if (data.scheme != "http" && data.scheme != "https") return null
			val source = options.extras[mangaSourceKey]?.unwrap() as? External ?: return null
			val httpSource = source.catalogueSource as? HttpSource ?: return null
			return ExternalSourceFetcher(httpSource, data.toString(), options)
		}
	}
}
