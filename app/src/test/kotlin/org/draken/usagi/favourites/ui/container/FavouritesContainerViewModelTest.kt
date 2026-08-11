package org.draken.usagi.favourites.ui.container

import org.draken.usagi.favourites.domain.FavouriteScope
import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesContainerViewModelTest {
	@Test
	fun `all favorites remains when there are no editable folders`() {
		val tabs = buildFavouriteTabs(emptyList(), emptyList())

		assertEquals(listOf(FavouriteScope.All), tabs.map(FavouriteTabModel::scope))
	}
}
