package org.draken.usagi.alternatives.domain

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.draken.usagi.core.LocalizedAppContext
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.util.ext.toLocale
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.search.domain.SearchKind
import org.draken.usagi.search.domain.SearchV2Helper
import tsuki.model.Manga
import tsuki.model.MangaSource
import tsuki.util.runCatchingCancellable
import java.util.Locale
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

class AlternativesUseCase
	@Inject
	constructor(
		@LocalizedAppContext private val context: Context,
		private val sourcesRepository: MangaSourcesRepository,
		private val searchHelperFactory: SearchV2Helper.Factory,
		private val mangaRepositoryFactory: MangaRepository.Factory,
		private val settings: AppSettings,
	) {
		suspend operator fun invoke(
			manga: Manga,
			throughDisabledSources: Boolean,
			query: String = manga.title,
		): Flow<Manga> {
			val sources = getSources(manga.source, throughDisabledSources)
			if (sources.isEmpty()) {
				return emptyFlow()
			}
			val semaphore = Semaphore(MAX_PARALLELISM)
			return channelFlow {
				for (source in sources) {
					launch {
						val searchHelper = searchHelperFactory.create(source)
						val list =
							runCatchingCancellable {
								semaphore.withPermit {
									searchHelper(query, SearchKind.TITLE)?.manga
								}
							}.getOrNull()
						list?.forEach { m ->
							if (m.id != manga.id) {
								launch {
									val details =
										runCatchingCancellable {
											mangaRepositoryFactory.create(m.source).getDetails(m)
										}.getOrDefault(m)
									send(details)
								}
							}
						}
					}
				}
			}
		}

		private suspend fun getSources(
			ref: MangaSource,
			disabled: Boolean,
		): List<MangaSource> =
			(if (disabled) sourcesRepository.getDisabledSources() else sourcesRepository.getEnabledSources())
				.filterNot { it.name in settings.fixSourcesBlacklist || it.getTitle(context) in settings.fixSourcesBlacklist }
				.sortedByDescending { it.priority(ref) }

		private fun MangaSource.priority(ref: MangaSource): Int {
			var res = 0
			if (locale == ref.locale) {
				res += 4
			} else if (locale.toLocale() == Locale.getDefault()) {
				res += 2
			}
			if (contentType == ref.contentType) {
				res++
			}
			return res
		}
	}
