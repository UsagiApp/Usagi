package org.draken.usagi.scrobbling.mal.domain

import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.scrobbling.common.domain.Scrobbler
import org.draken.usagi.scrobbling.common.domain.model.ScrobblerService
import org.draken.usagi.scrobbling.common.domain.model.ScrobblingStatus
import org.draken.usagi.scrobbling.mal.data.MALRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val RATING_MAX = 10f

@Singleton
class MALScrobbler @Inject constructor(
	private val repository: MALRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: MangaRepository.Factory,
) : Scrobbler(db, ScrobblerService.MAL, repository, mangaRepositoryFactory) {

	init {
		statuses[ScrobblingStatus.PLANNED] = "plan_to_read"
		statuses[ScrobblingStatus.READING] = "reading"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}

	override suspend fun updateScrobblingInfo(
		mangaId: Long,
		rating: Float,
		status: ScrobblingStatus?,
		comment: String?,
	) {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId)
		requireNotNull(entity) { "Scrobbling info for manga $mangaId not found" }
		repository.updateRate(
			rateId = entity.id,
			mangaId = entity.mangaId,
			rating = rating * RATING_MAX,
			status = statuses[status],
			comment = comment,
		)
	}

}
