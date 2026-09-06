package org.draken.usagi.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import tsuki.model.MangaState

class FavouriteStageClassifierTest {
	@Test
	fun `favorite without active history is not started`() {
		assertStage(FavouriteStage.NOT_STARTED, historyPercent = null, states = emptySet())
	}

	@Test
	fun `incomplete progress or new chapters is reading`() {
		assertStage(FavouriteStage.READING, historyPercent = 0.5f)
		assertStage(FavouriteStage.READING, newChapters = 1)
	}

	@Test
	fun `caught up finished favorite is completed`() {
		assertStage(FavouriteStage.COMPLETED)
	}

	@Test
	fun `caught up continuing favorite is waiting`() {
		setOf(MangaState.ONGOING, MangaState.PAUSED, MangaState.UPCOMING).forEach { state ->
			assertStage(FavouriteStage.WAITING, states = setOf(state))
		}
	}

	@Test
	fun `caught up favorite with unusable status needs review`() {
		val unusableStates =
			listOf(
				emptySet(),
				setOf(MangaState.ABANDONED),
				setOf(MangaState.RESTRICTED),
				setOf(MangaState.FINISHED, MangaState.ONGOING),
			)

		unusableStates.forEach { states ->
			assertStage(FavouriteStage.NEEDS_REVIEW, states = states)
		}
	}

	@Test
	fun `only source dependent terminal stages are refresh candidates`() {
		assertEquals(
			setOf(FavouriteStage.WAITING, FavouriteStage.COMPLETED, FavouriteStage.NEEDS_REVIEW),
			FavouriteStage.entries.filterTo(linkedSetOf()) { stage -> stage.requiresSourceRefresh },
		)
	}

	private fun assertStage(
		expected: FavouriteStage,
		historyPercent: Float? = 1f,
		newChapters: Int = 0,
		states: Set<MangaState> = setOf(MangaState.FINISHED),
	) = assertEquals(
		"progress=$historyPercent, newChapters=$newChapters, states=$states",
		expected,
		FavouriteStageClassifier.classify(historyPercent, newChapters, states),
	)
}
