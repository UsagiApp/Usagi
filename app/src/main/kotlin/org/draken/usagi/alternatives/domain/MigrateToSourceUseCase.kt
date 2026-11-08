package org.draken.usagi.alternatives.domain

import org.draken.usagi.core.parser.MangaDataRepository
import org.draken.usagi.search.domain.SearchKind
import org.draken.usagi.search.domain.SearchV2Helper
import tsuki.model.MangaSource
import javax.inject.Inject

class MigrateToSourceUseCase
	@Inject
	constructor(
		private val mangaDataRepository: MangaDataRepository,
		private val searchHelperFactory: SearchV2Helper.Factory,
		private val migrateUseCase: MigrateUseCase,
	) {
		/** Returns true when a matching manga was found on [target] and migrated. */
		suspend operator fun invoke(
			mangaId: Long,
			target: MangaSource,
		): Boolean {
			val seed = mangaDataRepository.findMangaById(mangaId, withChapters = true) ?: return false
			if (seed.source.name == target.name) {
				return false
			}
			val searchHelper = searchHelperFactory.create(target)
			val candidate =
				searchHelper(seed.title, SearchKind.TITLE)?.manga?.firstOrNull()
					?: seed.altTitle
						?.takeIf { it.isNotBlank() && it != seed.title }
						?.let { searchHelper(it, SearchKind.TITLE)?.manga?.firstOrNull() } ?: return false
			migrateUseCase(seed, candidate)
			return true
		}
	}
