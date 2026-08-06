package org.draken.usagi.favourites.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.SmartFolderRulesError
import org.draken.usagi.favourites.domain.SmartFolderRulesResult
import org.draken.usagi.favourites.domain.SmartFoldersRepository
import org.draken.usagi.list.domain.ListSortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavouritesOrganizerDaoTest {
	private lateinit var database: MangaDatabase

	@Before
	fun setUp() {
		database =
			Room
				.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MangaDatabase::class.java)
				.allowMainThreadQueries()
				.build()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun allCategoryAndSmartFolderScopesApplyOrInsideGroupsAndAcrossGroups() =
		runTest {
			insertCategory(1, "Read later")
			insertCategory(2, "Favorites")
			insertManga(1, source = "source-a", isNsfw = false, state = "ONGOING")
			insertManga(2, source = "source-a", isNsfw = true, state = "ONGOING")
			insertManga(3, source = "source-b", isNsfw = false, state = "ONGOING")
			insertManga(4, source = "source-c", isNsfw = false, state = "ONGOING")
			insertFavourite(1, 1)
			insertFavourite(2, 1)
			insertFavourite(3, 2)
			insertFavourite(4, 1)
			insertLocal(1)
			insertLocal(3)

			assertEquals(setOf(1L, 2L, 3L, 4L), observeIds(FavouriteScope.All))
			assertEquals(setOf(1L, 2L, 4L), observeIds(FavouriteScope.Category(1)))

			val rules =
				SmartFolderRules(
					sources = setOf("source-a", "source-b"),
					categoryIds = setOf(1L, 2L),
					content = SmartFolderContent.SFW,
					device = SmartFolderDevice.ON_DEVICE,
				)
			assertEquals(setOf(1L, 3L), observeIds(FavouriteScope.SmartFolder(1), rules))
		}

	@Test
	fun deletedReferencedCategoryMakesTheSmartFolderGroupEmpty() =
		runTest {
			insertCategory(1, "Read later")
			insertManga(1, source = "source-a", isNsfw = false, state = "ONGOING")
			insertFavourite(1, 1)
			val rules = SmartFolderRules(categoryIds = setOf(1L))
			val repository = SmartFoldersRepository(database)
			repository.create("Category rule", ListSortOrder.NEWEST, rules)

			assertEquals(setOf(1L), observeIds(FavouriteScope.SmartFolder(1), rules))
			database.openHelper.writableDatabase.execSQL("UPDATE favourite_categories SET deleted_at = 1 WHERE category_id = 1")
			assertEquals(emptySet<Long>(), observeIds(FavouriteScope.SmartFolder(1), rules))
			assertEquals(
				SmartFolderRulesResult.Error(SmartFolderRulesError.MISSING_CATEGORY, rules),
				repository
					.observeAll()
					.first()
					.single()
					.rules,
			)
		}

	@Test
	fun tagValuesUseOrWhileOtherRuleGroupsUseAnd() =
		runTest {
			insertCategory(1, "Read later")
			insertManga(1, source = "source-a", isNsfw = false, state = "ONGOING")
			insertManga(2, source = "source-a", isNsfw = false, state = "ONGOING")
			insertManga(3, source = "source-a", isNsfw = false, state = "ONGOING")
			insertManga(4, source = "source-a", isNsfw = true, state = "ONGOING")
			(1L..4L).forEach { mangaId -> insertFavourite(mangaId, 1) }
			insertTag(10, "Action")
			insertTag(20, "Drama")
			insertTag(30, "Comedy")
			insertMangaTag(1, 10)
			insertMangaTag(2, 20)
			insertMangaTag(3, 30)
			insertMangaTag(4, 10)

			val rules =
				SmartFolderRules(
					tagIds = setOf(10L, 20L),
					content = SmartFolderContent.SFW,
				)

			assertEquals(setOf(1L, 2L), observeIds(FavouriteScope.SmartFolder(1), rules))
		}

	@Test
	fun stageCountsFollowHistoryTrackerAndSourceStateChanges() =
		runTest {
			insertCategory(1, "Read later")
			insertManga(1, source = "source-a", isNsfw = false, state = "ONGOING")
			insertFavourite(1, 1)

			assertEquals(stageCounts(notStarted = 1), counts())

			insertHistory(1, percent = 0.5f)
			assertEquals(stageCounts(reading = 1), counts())

			database.openHelper.writableDatabase.execSQL("UPDATE history SET percent = 1.0 WHERE manga_id = 1")
			assertEquals(stageCounts(waiting = 1), counts())

			insertTrack(1, newChapters = 1)
			assertEquals(stageCounts(reading = 1), counts())

			database.openHelper.writableDatabase.execSQL("UPDATE tracks SET chapters_new = 0 WHERE manga_id = 1")
			database.openHelper.writableDatabase.execSQL("UPDATE manga SET state = 'FINISHED' WHERE manga_id = 1")
			assertEquals(stageCounts(completed = 1), counts())

			database.openHelper.writableDatabase.execSQL("UPDATE manga SET state = 'ABANDONED' WHERE manga_id = 1")
			assertEquals(stageCounts(needsReview = 1), counts())
		}

	private fun stageCounts(
		notStarted: Int = 0,
		reading: Int = 0,
		waiting: Int = 0,
		completed: Int = 0,
		needsReview: Int = 0,
	) = FavouriteStageCounts(1, notStarted, reading, waiting, completed, needsReview)

	private suspend fun observeIds(
		scope: FavouriteScope,
		rules: SmartFolderRules? = null,
	): Set<Long> =
		database
			.getFavouritesDao()
			.observeAll(
				scope = scope,
				stage = FavouriteStage.ALL,
				rules = rules,
				order = ListSortOrder.NEWEST,
				filterOptions = emptySet(),
				limit = 100,
			).first()
			.mapTo(linkedSetOf()) { favourite -> favourite.manga.id }

	private suspend fun counts(): FavouriteStageCounts = database.getFavouritesDao().observeStageCounts(FavouriteScope.All, null).first()

	private fun insertCategory(
		id: Long,
		title: String,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO favourite_categories (category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) VALUES (?, 1, ?, ?, 'NEWEST', 1, 1, 0)",
			arrayOf<Any?>(id, id, title),
		)
	}

	private fun insertManga(
		id: Long,
		source: String,
		isNsfw: Boolean,
		state: String?,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO manga (manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source) VALUES (?, ?, NULL, ?, ?, 0.5, ?, NULL, '', NULL, ?, NULL, ?)",
			arrayOf<Any?>(id, "Manga $id", "url-$id", "public-$id", if (isNsfw) 1 else 0, state, source),
		)
	}

	private fun insertFavourite(
		mangaId: Long,
		categoryId: Long,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at) VALUES (?, ?, ?, 0, ?, 0)",
			arrayOf<Any?>(mangaId, categoryId, mangaId, mangaId),
		)
	}

	private fun insertLocal(mangaId: Long) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO local_index (manga_id, path) VALUES (?, ?)",
			arrayOf<Any?>(mangaId, "path-$mangaId"),
		)
	}

	private fun insertHistory(
		mangaId: Long,
		percent: Float,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) VALUES (?, 1, 1, 1, 1, 0, ?, 0, 1)",
			arrayOf<Any?>(mangaId, percent),
		)
	}

	private fun insertTrack(
		mangaId: Long,
		newChapters: Int,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO tracks (manga_id, last_chapter_id, chapters_new, last_check_time, last_chapter_date, last_result, last_error) VALUES (?, 0, ?, 0, 0, 0, NULL)",
			arrayOf<Any?>(mangaId, newChapters),
		)
	}

	private fun insertTag(
		id: Long,
		title: String,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO tags (tag_id, title, `key`, source, pinned) VALUES (?, ?, ?, 'source-a', 0)",
			arrayOf<Any?>(id, title, title.lowercase()),
		)
	}

	private fun insertMangaTag(
		mangaId: Long,
		tagId: Long,
	) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO manga_tags (manga_id, tag_id) VALUES (?, ?)",
			arrayOf<Any?>(mangaId, tagId),
		)
	}
}
