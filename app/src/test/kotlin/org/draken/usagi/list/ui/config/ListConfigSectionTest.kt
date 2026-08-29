package org.draken.usagi.list.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListConfigSectionTest {
	@Test
	fun `standalone favorites options do not require an organizer host`() {
		val section =
			ListConfigSection.Favorites(
				categoryId = 42L,
				mode = FavoritesOptionsMode.LIST_ONLY,
			)

		assertFalse(section.requiresOrganizerHost)
	}

	@Test
	fun `container favorites options require the active organizer host`() {
		val section =
			ListConfigSection.Favorites(
				categoryId = 42L,
				mode = FavoritesOptionsMode.ORGANIZER,
			)

		assertTrue(section.requiresOrganizerHost)
	}
}
