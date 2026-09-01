package org.draken.usagi.favourites.ui.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavouriteRulesSummaryTest {
	@Test
	fun `unrestricted smart folder without transient filters has no summary`() {
		assertNull(
			buildFavouriteRulesSummary(
				invalidRulesLabel = null,
				persistentSummary = null,
				selectedFilterTitles = emptyList(),
				overflowFilterSummary = "4 active filters",
			),
		)
	}

	@Test
	fun `validation error replaces rule and filter summaries`() {
		assertEquals(
			"Invalid rules",
			buildFavouriteRulesSummary(
				invalidRulesLabel = "Invalid rules",
				persistentSummary = "SFW",
				selectedFilterTitles = listOf("On device"),
				overflowFilterSummary = "4 active filters",
			),
		)
	}

	@Test
	fun `persistent and transient conditions are combined`() {
		assertEquals(
			"SFW · On device · New chapters",
			buildFavouriteRulesSummary(
				invalidRulesLabel = null,
				persistentSummary = "SFW",
				selectedFilterTitles = listOf("On device", "New chapters"),
				overflowFilterSummary = "4 active filters",
			),
		)
	}

	@Test
	fun `more than three transient filters use the compact count`() {
		assertEquals(
			"4 active filters",
			buildFavouriteRulesSummary(
				invalidRulesLabel = null,
				persistentSummary = null,
				selectedFilterTitles = listOf("One", "Two", "Three", "Four"),
				overflowFilterSummary = "4 active filters",
			),
		)
	}
}
