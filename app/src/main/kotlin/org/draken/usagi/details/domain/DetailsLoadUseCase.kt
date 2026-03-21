package org.draken.usagi.details.domain

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import coil3.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import org.draken.usagi.core.model.isLocal
import org.draken.usagi.core.nav.MangaIntent
import org.draken.usagi.core.os.NetworkState
import org.draken.usagi.core.parser.CachingMangaRepository
import org.draken.usagi.core.parser.MangaDataRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.ui.model.MangaOverride
import org.draken.usagi.core.util.ext.sanitize
import org.draken.usagi.details.data.MangaDetails
import org.draken.usagi.explore.domain.RecoverMangaUseCase
import org.draken.usagi.local.data.LocalMangaRepository
import org.draken.usagi.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.recoverNotNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

class DetailsLoadUseCase @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val recoverUseCase: RecoverMangaUseCase,
	private val imageGetter: Html.ImageGetter,
	private val networkState: NetworkState,
) {

	operator fun invoke(intent: MangaIntent, force: Boolean): Flow<MangaDetails> = flow {
		val manga = requireNotNull(mangaDataRepository.resolveIntent(intent, withChapters = true)) {
			"Cannot resolve intent $intent"
		}
		val override = mangaDataRepository.getOverride(manga.id)
		emit(
			MangaDetails(
				manga = manga,
				localManga = null,
				override = override,
				description = manga.description?.parseAsHtml(withImages = false),
				isLoaded = false,
			),
		)
		if (manga.isLocal) {
			loadLocal(manga, override, force)
		} else {
			loadRemote(manga, override, force)
		}
	}.distinctUntilChanged()
		.flowOn(Dispatchers.Default)

	/**
	 * Load local manga + try to load the linked remote one if network is not restricted
	 * Suppress any network errors
	 */
	private suspend fun FlowCollector<MangaDetails>.loadLocal(manga: Manga, override: MangaOverride?, force: Boolean) {
		val skipNetworkLoad = !force && networkState.isOfflineOrRestricted()
		val localDetails = localMangaRepository.getDetails(manga)
		emit(
			MangaDetails(
				manga = localDetails,
				localManga = null,
				override = override,
				description = localDetails.description?.parseAsHtml(withImages = false),
				isLoaded = skipNetworkLoad,
			),
		)
		if (skipNetworkLoad) {
			return
		}
		val remoteManga = localMangaRepository.getRemoteManga(manga)
		if (remoteManga == null) {
			emit(
				MangaDetails(
					manga = localDetails,
					localManga = null,
					override = override,
					description = localDetails.description?.parseAsHtml(withImages = true),
					isLoaded = true,
				),
			)
		} else {
			val remoteDetails = getDetails(remoteManga, force).getOrNull()
			val mangaDetails = MangaDetails(
				manga = remoteDetails ?: remoteManga,
				localManga = LocalManga(localDetails),
				override = override,
				description = (remoteDetails ?: localDetails).description?.parseAsHtml(withImages = true),
				isLoaded = true,
			)
			emit(mangaDetails)
			if (remoteDetails != null) {
				mangaDataRepository.updateChapters(mangaDetails.toManga())
			}
		}
	}

	/**
	 * Load remote manga + saved one if available
	 * Throw network errors after loading local manga only
	 */
	private suspend fun FlowCollector<MangaDetails>.loadRemote(
		manga: Manga,
		override: MangaOverride?,
		force: Boolean
	) = coroutineScope {
		val remoteDeferred = async {
			getDetails(manga, force)
		}
		val localManga = localMangaRepository.findSavedManga(manga, withDetails = true)
		if (localManga != null) {
			emit(
				MangaDetails(
					manga = manga,
					localManga = localManga,
					override = override,
					description = localManga.manga.description?.parseAsHtml(withImages = true),
					isLoaded = false,
				),
			)
		}
		val remoteDetails = remoteDeferred.await().getOrThrow()
		val mangaDetails = MangaDetails(
			manga = remoteDetails,
			localManga = localManga,
			override = override,
			description = (remoteDetails.description
				?: localManga?.manga?.description)?.parseAsHtml(withImages = true),
			isLoaded = true,
		)
		emit(mangaDetails)
		mangaDataRepository.updateChapters(mangaDetails.toManga())
	}

	private suspend fun getDetails(seed: Manga, force: Boolean) = runCatchingCancellable {
		val repository = mangaRepositoryFactory.create(seed.source)
		if (repository is CachingMangaRepository) {
			repository.getDetails(seed, if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
		} else {
			repository.getDetails(seed)
		}
	}.recoverNotNull { e ->
		if (e is NotFoundException) {
			recoverUseCase(seed)
		} else {
			null
		}
	}

	private suspend fun String.parseAsHtml(withImages: Boolean): CharSequence? = if (withImages) {
		runInterruptible(Dispatchers.IO) {
			parseAsHtml(imageGetter = imageGetter)
		}.filterSpans()
	} else {
		runInterruptible(Dispatchers.Default) {
			parseAsHtml()
		}.filterSpans().sanitize()
	}.trim().nullIfEmpty()

	private fun Spanned.filterSpans(): Spanned {
		val spannable = SpannableString.valueOf(this)
		val spans = spannable.getSpans<ForegroundColorSpan>()
		for (span in spans) {
			spannable.removeSpan(span)
		}
		return spannable
	}
}
