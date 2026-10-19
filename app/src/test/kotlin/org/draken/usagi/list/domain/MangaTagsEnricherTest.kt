package org.draken.usagi.list.domain

import kotlinx.coroutines.test.runTest
import org.draken.usagi.core.model.LocalMangaSource
import org.draken.usagi.core.model.TestMangaSource
import org.draken.usagi.core.parser.EmptyMangaRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import tsuki.model.Manga
import tsuki.model.MangaSource
import tsuki.model.MangaTag

class MangaTagsEnricherTest {
	private val excludedTag = MangaTag(title = "Yaoi", key = "tag:yaoi", source = TestMangaSource)

	@Test
	fun nothingHappensWhenExclusionIsEmpty() =
		runTest {
			val manga = listOf(createManga())
			val repository = FakeMangaRepository { error("getDetails must not be requested") }
			val result = MangaTagsEnricher().enrich(repository, manga, ExcludedTags(emptySet()))
			assertEquals(manga, result)
			assertEquals(0, repository.detailsRequests)
		}

	@Test
	fun nothingHappensWhenEveryItemHasTags() =
		runTest {
			val manga = listOf(createManga(tags = setOf(MangaTag(title = "Action", key = "tag:action", source = TestMangaSource))))
			val repository = FakeMangaRepository { error("getDetails must not be requested") }
			val result = MangaTagsEnricher().enrich(repository, manga, ExcludedTags(setOf("Yaoi")))
			assertEquals(manga, result)
			assertEquals(0, repository.detailsRequests)
		}

	@Test
	fun localStorageIsSkipped() =
		runTest {
			val manga = listOf(createManga(source = LocalMangaSource))
			val repository = FakeMangaRepository(source = LocalMangaSource) { error("getDetails must not be requested") }
			val result = MangaTagsEnricher().enrich(repository, manga, ExcludedTags(setOf("Yaoi")))
			assertEquals(manga, result)
			assertEquals(0, repository.detailsRequests)
		}

	@Test
	fun missingTagsAreResolvedFromDetails() =
		runTest {
			val manga = createManga()
			val repository = FakeMangaRepository { item -> item.copy(tags = setOf(excludedTag)) }
			val result = MangaTagsEnricher().enrich(repository, listOf(manga), ExcludedTags(setOf("Yaoi")))
			assertEquals(setOf(excludedTag), result.single().tags)
			assertEquals(1, repository.detailsRequests)
		}

	@Test
	fun itemIsKeptWhenDetailsFail() =
		runTest {
			val manga = createManga()
			val repository = FakeMangaRepository { throw RuntimeException("Unknown error") }
			val result = MangaTagsEnricher().enrich(repository, listOf(manga), ExcludedTags(setOf("Yaoi")))
			assertEquals(listOf(manga), result)
			assertEquals(1, repository.detailsRequests)
		}

	@Test
	fun resolvedTagsAreCached() =
		runTest {
			val manga = listOf(createManga())
			val repository = FakeMangaRepository { item -> item.copy(tags = setOf(excludedTag)) }
			val enricher = MangaTagsEnricher()
			val exclusion = ExcludedTags(setOf("Yaoi"))
			enricher.enrich(repository, manga, exclusion)
			enricher.enrich(repository, manga, exclusion)
			assertEquals(1, repository.detailsRequests)
		}

	@Test
	fun resolvedTagsMatchExclusion() =
		runTest {
			val included = createManga()
			val excluded = createManga(url = "/g/2/")
			val repository =
				FakeMangaRepository { item ->
					if (item.url == excluded.url) item.copy(tags = setOf(excludedTag)) else item
				}
			val exclusion = ExcludedTags(setOf("Yaoi"))
			val result =
				MangaTagsEnricher()
					.enrich(repository, listOf(included, excluded), exclusion)
					.filterNot { it in exclusion }
			assertEquals(listOf(included), result)
		}

	private fun createManga(
		url: String = "/g/1/",
		tags: Set<MangaTag> = emptySet(),
		source: MangaSource = TestMangaSource,
	) =
		Manga(
			id = 1L,
			title = "Manga",
			altTitles = emptySet(),
			url = url,
			publicUrl = "https://example.org$url",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = tags,
			state = null,
			authors = emptySet(),
			source = source,
		)

	private class FakeMangaRepository(
		source: MangaSource = TestMangaSource,
		private val detailsProvider: suspend (Manga) -> Manga,
	) : EmptyMangaRepository(source) {
		var detailsRequests = 0
			private set

		override suspend fun getDetails(manga: Manga): Manga {
			detailsRequests++
			return detailsProvider(manga)
		}
	}
}
