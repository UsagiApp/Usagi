package org.draken.usagi.favourites.ui.list

import org.draken.usagi.favourites.domain.FavouriteStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteOrganizerAutoRefreshGateTest {
	@Test
	fun `first terminal stage requests one refresh for the scope session`() {
		val gate = FavouriteOrganizerAutoRefreshGate()

		assertFalse(gate.shouldRefresh(FavouriteStage.ALL))
		assertFalse(gate.shouldRefresh(FavouriteStage.READING))
		assertTrue(gate.shouldRefresh(FavouriteStage.WAITING))
		assertFalse(gate.shouldRefresh(FavouriteStage.COMPLETED))
		assertFalse(gate.shouldRefresh(FavouriteStage.NEEDS_REVIEW))
	}

	@Test
	fun `non-terminal stages do not consume the refresh opportunity`() {
		val gate = FavouriteOrganizerAutoRefreshGate()

		assertFalse(gate.shouldRefresh(FavouriteStage.NOT_STARTED))
		assertFalse(gate.shouldRefresh(FavouriteStage.READING))
		assertTrue(gate.shouldRefresh(FavouriteStage.COMPLETED))
	}
}
