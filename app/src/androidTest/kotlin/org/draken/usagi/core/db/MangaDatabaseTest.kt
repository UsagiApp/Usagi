package org.draken.usagi.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.draken.usagi.core.db.migrations.Migration28To29
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
	}
}
