package org.draken.usagi.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import tsuki.model.MangaState

class FavouriteStageClassifierTest {
	@Test
	fun `favorite without active history is not started`() {
		val stage =
			FavouriteStageClassifier.classify(
				historyPercent = null,
				newChapters = 0,
				sourceStates = emptySet(),
			)

		assertEquals(FavouriteStage.NOT_STARTED, stage)
	}

	@Test
	fun `incomplete progress or new chapters is reading`() {
		assertEquals(
			FavouriteStage.READING,
			FavouriteStageClassifier.classify(
				historyPercent = 0.5f,
				newChapters = 0,
				sourceStates = setOf(MangaState.FINISHED),
			),
		)
		assertEquals(
			FavouriteStage.READING,
			FavouriteStageClassifier.classify(
				historyPercent = 1f,
				newChapters = 1,
				sourceStates = setOf(MangaState.FINISHED),
			),
		)
	}

	@Test
	fun `caught up finished favorite is completed`() {
		val stage =
			FavouriteStageClassifier.classify(
				historyPercent = 1f,
				newChapters = 0,
				sourceStates = setOf(MangaState.FINISHED),
			)

		assertEquals(FavouriteStage.COMPLETED, stage)
	}

	@Test
	fun `caught up continuing favorite is waiting`() {
		setOf(MangaState.ONGOING, MangaState.PAUSED, MangaState.UPCOMING).forEach { state ->
			assertEquals(
				FavouriteStage.WAITING,
				FavouriteStageClassifier.classify(
					historyPercent = 1f,
					newChapters = 0,
					sourceStates = setOf(state),
				),
			)
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
			assertEquals(
				FavouriteStage.NEEDS_REVIEW,
				FavouriteStageClassifier.classify(
					historyPercent = 1f,
					newChapters = 0,
					sourceStates = states,
				),
			)
		}
	}

	@Test
	fun `only source dependent terminal stages are refresh candidates`() {
		assertEquals(
			setOf(FavouriteStage.WAITING, FavouriteStage.COMPLETED, FavouriteStage.NEEDS_REVIEW),
			FavouriteStage.entries.filterTo(linkedSetOf()) { stage -> stage.requiresSourceRefresh },
		)
	}
}
