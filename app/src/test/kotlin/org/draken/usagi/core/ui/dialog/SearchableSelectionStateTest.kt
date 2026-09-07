package org.draken.usagi.core.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchableSelectionStateTest {
	private val items =
		listOf(
			SearchableSelectionItem(id = "readmanga", title = "ReadManga", subtitle = "Russian"),
			SearchableSelectionItem(id = "mangadex", title = "MangaDex", subtitle = "Multi-language"),
			SearchableSelectionItem(id = "mangahub", title = "MangaHub", subtitle = "English"),
		)

	@Test
	fun `search matches titles and subtitles without case sensitivity`() {
		val state = SearchableSelectionState(items, initiallySelected = emptySet())

		assertEquals(listOf("readmanga"), state.filtered("RUSSIAN").map { it.id })
		assertEquals(listOf("readmanga", "mangadex", "mangahub"), state.filtered("manga").map { it.id })
	}

	@Test
	fun `selection changes stay in the staged state`() {
		val initial = linkedSetOf("readmanga")
		val state = SearchableSelectionState(items, initiallySelected = initial)

		state.setSelected("mangadex", true)
		state.setSelected("readmanga", false)

		assertEquals(setOf("readmanga"), initial)
		assertFalse(state.isSelected("readmanga"))
		assertTrue(state.isSelected("mangadex"))
	}

	@Test
	fun `clear removes every staged selection`() {
		val state = SearchableSelectionState(items, initiallySelected = setOf("readmanga", "mangadex"))

		state.clear()

		assertEquals(emptySet<String>(), state.selection())
	}
}
