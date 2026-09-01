package org.draken.usagi.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.draken.usagi.core.db.migrations.Migration28To29
import org.draken.usagi.core.db.migrations.Migration29To30
import org.draken.usagi.core.db.migrations.Migration30To31
import org.draken.usagi.core.db.migrations.Migration31To32
import org.draken.usagi.favourites.data.createAllFavoritesInfrastructure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaDatabaseTest {
	@get:Rule
	val helper: MigrationTestHelper =
		MigrationTestHelper(
			InstrumentationRegistry.getInstrumentation(),
			MangaDatabase::class.java,
		)

	private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

	@Test
	fun versions() {
		assertEquals(1, migrations.first().startVersion)
		repeat(migrations.size) { i ->
			assertEquals(i + 1, migrations[i].startVersion)
			assertEquals(i + 2, migrations[i].endVersion)
		}
		assertEquals(DATABASE_VERSION, migrations.last().endVersion)
	}

	@Test
	fun migrateAll() {
		helper.createDatabase(TEST_DB, 1).close()
		for (migration in migrations) {
			helper
				.runMigrationsAndValidate(
					TEST_DB,
					migration.endVersion,
					true,
					migration,
				).close()
		}
	}

	@Test
	fun migrate29To30CreatesGlobalMembershipFromActiveFavorites() {
		helper.createDatabase(TEST_DB_29_30, 29).use { database ->
			database.execSQL(
				"INSERT INTO favourite_categories " +
					"(category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) " +
					"VALUES (1, 10, 0, 'Read later', 'NEWEST', 1, 1, 0)",
			)
			database.execSQL(
				"INSERT INTO manga " +
					"(manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source) " +
					"VALUES (1, 'Manga', NULL, 'url', 'public', 0.5, 0, NULL, '', NULL, 'ONGOING', NULL, 'source-a')",
			)
			database.execSQL(
				"INSERT INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at) " +
					"VALUES (1, 1, 0, 0, 10, 0)",
			)
		}

		helper.runMigrationsAndValidate(TEST_DB_29_30, 30, true, Migration29To30()).use { database ->
			database.query("SELECT COUNT(*) FROM favourite_categories WHERE category_id = 0").use { cursor ->
				cursor.moveToFirst()
				assertEquals(1, cursor.getInt(0))
			}
			database.query("SELECT COUNT(*) FROM favourites WHERE manga_id = 1 AND category_id = 0 AND deleted_at = 0").use { cursor ->
				cursor.moveToFirst()
				assertEquals(1, cursor.getInt(0))
			}
		}
	}

	@Test
	fun migrate30To31RepairsMissingAndInactiveGlobalMemberships() {
		helper.createDatabase(TEST_DB_30_31, 30).use { database ->
			database.execSQL(
				"INSERT INTO favourite_categories " +
					"(category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) VALUES " +
					"(0, 0, -1, '__all_favorites__', 'NEWEST', 0, 0, 0), " +
					"(1, 10, 0, 'Read later', 'NEWEST', 1, 1, 0)",
			)
			for (mangaId in 1L..3L) {
				database.execSQL(
					"INSERT INTO manga " +
						"(manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source) " +
						"VALUES (?, ?, NULL, ?, ?, 0.5, 0, NULL, '', NULL, 'ONGOING', NULL, 'source-a')",
					arrayOf<Any?>(mangaId, "Manga $mangaId", "url-$mangaId", "public-$mangaId"),
				)
			}
			database.execSQL(
				"INSERT INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at) VALUES " +
					"(1, 1, 1, 0, 1, 0), " +
					"(2, 1, 2, 0, 2, 0), " +
					"(2, 0, 0, 0, 2, 10), " +
					"(3, 0, 0, 0, 3, 0)",
			)
		}

		helper.runMigrationsAndValidate(TEST_DB_30_31, 31, true, Migration30To31()).use { database ->
			database
				.query(
					"SELECT COUNT(*) FROM favourites " +
						"WHERE category_id = 0 AND deleted_at = 0 AND manga_id IN (1, 2, 3)",
				).use { cursor ->
					cursor.moveToFirst()
					assertEquals(3, cursor.getInt(0))
				}
		}
	}

	@Test
	fun migrate31To32KeepsGlobalMembershipWhenLastManualMembershipIsRemoved() {
		helper.createDatabase(TEST_DB_31_32, 31).use { database ->
			database.createAllFavoritesInfrastructure()
			database.execSQL(
				"INSERT INTO favourite_categories " +
					"(category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) " +
					"VALUES (1, 10, 0, 'Read later', 'NEWEST', 1, 1, 0)",
			)
			database.execSQL(
				"INSERT INTO manga " +
					"(manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source) " +
					"VALUES (1, 'Manga', NULL, 'url', 'public', 0.5, 0, NULL, '', NULL, 'ONGOING', NULL, 'source-a')",
			)
			database.execSQL(
				"INSERT INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at) " +
					"VALUES (1, 1, 0, 0, 10, 0)",
			)
		}

		helper.runMigrationsAndValidate(TEST_DB_31_32, 32, true, Migration31To32()).use { database ->
			database.execSQL("UPDATE favourites SET deleted_at = 20 WHERE manga_id = 1 AND category_id = 1")
			database
				.query("SELECT COUNT(*) FROM favourites WHERE manga_id = 1 AND category_id = 0 AND deleted_at = 0")
				.use { cursor ->
					cursor.moveToFirst()
					assertEquals(1, cursor.getInt(0))
				}
		}
	}

	@Test
	fun prePopulate() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		helper.createDatabase(TEST_DB, DATABASE_VERSION).use {
			DatabasePrePopulateCallback(resources).onCreate(it)
		}
	}

	@Test
	fun migrate28To29CreatesSmartFoldersWithoutChangingExistingData() {
		helper.createDatabase(TEST_DB_28_29, 28).use { database ->
			database.execSQL(
				"INSERT INTO favourite_categories " +
					"(category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) " +
					"VALUES (1, 10, 0, 'Read later', 'NEWEST', 1, 1, 0)",
			)
		}

		helper.runMigrationsAndValidate(TEST_DB_28_29, 29, true, Migration28To29()).use { database ->
			database.query("SELECT COUNT(*) FROM favourite_categories WHERE category_id = 1").use { cursor ->
				cursor.moveToFirst()
				assertEquals(1, cursor.getInt(0))
			}
			database.query("SELECT COUNT(*) FROM smart_folders").use { cursor ->
				cursor.moveToFirst()
				assertEquals(0, cursor.getInt(0))
			}
		}
	}

	private companion object {
		const val TEST_DB = "test-db"
		const val TEST_DB_28_29 = "test-db-28-29"
		const val TEST_DB_29_30 = "test-db-29-30"
		const val TEST_DB_30_31 = "test-db-30-31"
		const val TEST_DB_31_32 = "test-db-31-32"
	}
}
