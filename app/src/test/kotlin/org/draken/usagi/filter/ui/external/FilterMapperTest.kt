package org.draken.usagi.filter.ui.external

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.draken.usagi.core.model.LocalMangaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tsuki.model.MangaListFilter

class FilterMapperTest {
	@Test
	fun `encodes only non default values`() {
		val defaults = FilterList(TestCheckBox("Check"), TestSelect("Select", arrayOf("A", "B")))
		val working = FilterList(TestCheckBox("Check", state = true), TestSelect("Select", arrayOf("A", "B"), state = 1))

		val encoded = FilterMapper.encode(working, defaults, LocalMangaSource)

		assertEquals(setOf("cb@0=1", "sel@1=1"), encoded.map { it.key }.toSet())
	}

	@Test
	fun `round trips all supported control types`() {
		val defaults =
			FilterList(
				TestCheckBox("Check"),
				TestTriState("Tri"),
				TestText("Text"),
				TestSelect("Select", arrayOf("A", "B", "C")),
				TestSort("Sort", arrayOf("Popular", "Newest")),
			)
		val working =
			FilterList(
				TestCheckBox("Check", state = true),
				TestTriState("Tri", state = Filter.TriState.STATE_EXCLUDE),
				TestText("Text", state = "query"),
				TestSelect("Select", arrayOf("A", "B", "C"), state = 2),
				TestSort("Sort", arrayOf("Popular", "Newest"), state = Filter.Sort.Selection(1, false)),
			)

		val encoded = FilterMapper.encode(working, defaults, LocalMangaSource)
		val target = FilterList(TestCheckBox("Check"), TestTriState("Tri"), TestText("Text"), TestSelect("Select", arrayOf("A", "B", "C")), TestSort("Sort", arrayOf("Popular", "Newest")))
		FilterMapper.decode(target, MangaListFilter(tags = encoded))

		assertEquals(true, (target[0] as Filter.CheckBox).state)
		assertEquals(Filter.TriState.STATE_EXCLUDE, (target[1] as Filter.TriState).state)
		assertEquals("query", (target[2] as Filter.Text).state)
		assertEquals(2, (target[3] as Filter.Select<*>).state)
		assertEquals(Filter.Sort.Selection(1, false), (target[4] as Filter.Sort).state)
		assertEquals(setOf("cb@0=1", "tri@1=2", "txt@2=query", "sel@3=2", "srt@4=1:d"), encoded.map { it.key }.toSet())
	}

	@Test
	fun `round trips nested groups`() {
		val defaults = FilterList(TestGroup("Group", listOf(TestCheckBox("Check"))))
		val working = FilterList(TestGroup("Group", listOf(TestCheckBox("Check", state = true))))

		val encoded = FilterMapper.encode(working, defaults, LocalMangaSource)
		val target = FilterList(TestGroup("Group", listOf(TestCheckBox("Check"))))
		FilterMapper.decode(target, MangaListFilter(tags = encoded))

		assertEquals(setOf("cb@0.0=1"), encoded.map { it.key }.toSet())
		val group = target[0] as Filter.Group<*>
		assertEquals(true, (group.state.single() as Filter.CheckBox).state)
	}

	@Test
	fun `decoded filters do not leak into unrelated paths`() {
		val target = FilterList(TestCheckBox("Check"), TestCheckBox("Other"))
		FilterMapper.decode(target, MangaListFilter(tags = setOf()))
		assertTrue((target[0] as Filter.CheckBox).state.not())
		assertTrue((target[1] as Filter.CheckBox).state.not())
	}

	private class TestCheckBox(
		name: String,
		state: Boolean = false,
	) : Filter.CheckBox(name, state)

	private class TestTriState(
		name: String,
		state: Int = Filter.TriState.STATE_IGNORE,
	) : Filter.TriState(name, state)

	private class TestText(
		name: String,
		state: String = "",
	) : Filter.Text(name, state)

	private class TestSelect(
		name: String,
		values: Array<String>,
		state: Int = 0,
	) : Filter.Select<String>(name, values, state)

	private class TestSort(
		name: String,
		values: Array<String>,
		state: Selection? = null,
	) : Filter.Sort(name, values, state)

	private class TestGroup(
		name: String,
		filters: List<Filter<*>>,
	) : Filter.Group<Filter<*>>(name, filters)
}
