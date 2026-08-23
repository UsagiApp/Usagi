package org.draken.usagi.favourites.ui.container

import org.draken.usagi.list.domain.ListFilterOption
import org.junit.Assert.assertEquals
import org.junit.Test

class FavouriteFilterSelectionStateTest {
	@Test
	fun `selecting SFW removes the conflicting NSFW option`() {
		val state = FavouriteFilterSelectionState(setOf(ListFilterOption.Macro.NSFW))

		state.setSelected(ListFilterOption.SFW, true)

		assertEquals(setOf(ListFilterOption.SFW), state.selection())
	}

	@Test
	fun `changing scope removes selections unavailable in the destination`() {
		val state =
			FavouriteFilterSelectionState(
				setOf(ListFilterOption.Downloaded, ListFilterOption.Macro.NEW_CHAPTERS),
			)

		state.retainAvailable(listOf(ListFilterOption.Macro.NEW_CHAPTERS))

		assertEquals(setOf(ListFilterOption.Macro.NEW_CHAPTERS), state.selection())
	}
}
