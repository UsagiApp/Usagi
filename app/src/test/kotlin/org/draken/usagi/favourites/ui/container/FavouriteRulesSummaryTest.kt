package org.draken.usagi.favourites.ui.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavouriteRulesSummaryTest {
	@Test
	fun `unrestricted smart folder without transient filters has no summary`() {
		assertNull(summary())
	}

	@Test
	fun `validation error replaces rule and filter summaries`() {
		assertEquals(
			"Invalid rules",
			summary(error = "Invalid rules", persistent = "SFW", filters = listOf("On device")),
		)
	}

	@Test
	fun `persistent and transient conditions are combined`() {
		assertEquals(
			"SFW · On device · New chapters",
			summary(persistent = "SFW", filters = listOf("On device", "New chapters")),
		)
	}

	@Test
	fun `more than three transient filters use the compact count`() {
		assertEquals(
			"4 active filters",
			summary(filters = listOf("One", "Two", "Three", "Four")),
		)
	}

	private fun summary(
		error: String? = null,
		persistent: String? = null,
		filters: List<String> = emptyList(),
	) = buildFavouriteRulesSummary(error, persistent, filters, "4 active filters")
}
