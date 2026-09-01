package org.draken.usagi.favourites.domain

import org.draken.usagi.list.domain.ReadingProgress
import tsuki.model.MangaState

sealed interface FavouriteScope {
	data object All : FavouriteScope

	data class Category(
		val id: Long,
	) : FavouriteScope

	data class SmartFolder(
		val id: Long,
	) : FavouriteScope
}

enum class FavouriteStage {
	ALL,
	NOT_STARTED,
	READING,
	WAITING,
	COMPLETED,
	NEEDS_REVIEW,
}

val FavouriteStage.requiresSourceRefresh: Boolean
	get() = this == FavouriteStage.WAITING || this == FavouriteStage.COMPLETED || this == FavouriteStage.NEEDS_REVIEW

object FavouriteStageClassifier {
	fun classify(
		historyPercent: Float?,
		newChapters: Int,
		sourceStates: Set<MangaState>,
	): FavouriteStage {
		if (historyPercent == null) {
			return FavouriteStage.NOT_STARTED
		}
		if (newChapters > 0 || !ReadingProgress.isCompleted(historyPercent)) {
			return FavouriteStage.READING
		}
		if (sourceStates == setOf(MangaState.FINISHED)) {
			return FavouriteStage.COMPLETED
		}
		if (sourceStates.singleOrNull() in WAITING_STATES) {
			return FavouriteStage.WAITING
		}
		return FavouriteStage.NEEDS_REVIEW
	}

	private val WAITING_STATES = setOf(MangaState.ONGOING, MangaState.PAUSED, MangaState.UPCOMING)
}
