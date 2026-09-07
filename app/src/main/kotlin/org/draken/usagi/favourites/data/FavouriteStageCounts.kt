package org.draken.usagi.favourites.data

import androidx.room.ColumnInfo
import org.draken.usagi.favourites.domain.FavouriteStage

data class FavouriteStageCounts(
	@ColumnInfo(name = "all_count") val all: Int,
	@ColumnInfo(name = "not_started_count") val notStarted: Int,
	@ColumnInfo(name = "reading_count") val reading: Int,
	@ColumnInfo(name = "waiting_count") val waiting: Int,
	@ColumnInfo(name = "completed_count") val completed: Int,
	@ColumnInfo(name = "needs_review_count") val needsReview: Int,
) {
	operator fun get(stage: FavouriteStage): Int =
		when (stage) {
			FavouriteStage.ALL -> all
			FavouriteStage.NOT_STARTED -> notStarted
			FavouriteStage.READING -> reading
			FavouriteStage.WAITING -> waiting
			FavouriteStage.COMPLETED -> completed
			FavouriteStage.NEEDS_REVIEW -> needsReview
		}
}
