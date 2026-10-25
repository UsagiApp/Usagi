package org.draken.usagi.filter.data

import kotlinx.serialization.json.Json
import org.draken.usagi.core.model.LocalMangaSource
import org.junit.Assert.assertEquals
import org.junit.Test
import tsuki.model.MangaListFilter
import tsuki.model.MangaTag

class MangaListFilterSerializerTest {
	@Test
	fun `dynamic tags survive persistence round trip`() {
		val filter =
			MangaListFilter(
				query = "search",
				tags =
					setOf(
						MangaTag("Check", "cb@0=1", LocalMangaSource),
						MangaTag("Select", "sel@2=3", LocalMangaSource),
					),
			)
		val persisted = PersistableFilter(name = "preset", source = LocalMangaSource, filter = filter)

		val restored = Json.decodeFromString<PersistableFilter>(Json.encodeToString(persisted))

		assertEquals("preset", restored.name)
		assertEquals(LocalMangaSource, restored.source)
		assertEquals("search", restored.filter.query)
		assertEquals(
			setOf("cb@0=1", "sel@2=3"),
			restored.filter.tags
				.map { it.key }
				.toSet(),
		)
	}
}
