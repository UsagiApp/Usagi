package org.draken.usagi.favourites.data

import androidx.sqlite.db.SupportSQLiteDatabase

const val ALL_FAVORITES_CATEGORY_ID = 0L

fun SupportSQLiteDatabase.createAllFavoritesInfrastructure() {
	execSQL(
		"INSERT OR IGNORE INTO favourite_categories " +
			"(category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at) " +
			"VALUES (0, 0, -1, '__all_favorites__', 'NEWEST', 0, 0, 0)",
	)
	execSQL(
		"INSERT OR IGNORE INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at) " +
			"SELECT manga_id, 0, 0, MAX(pinned), MIN(created_at), 0 FROM favourites " +
			"WHERE category_id != 0 AND deleted_at = 0 GROUP BY manga_id",
	)
	execSQL(
		"UPDATE favourites SET deleted_at = 0 WHERE category_id = 0 AND EXISTS (" +
			"SELECT 1 FROM favourites AS manual_membership " +
			"WHERE manual_membership.manga_id = favourites.manga_id " +
			"AND manual_membership.category_id != 0 AND manual_membership.deleted_at = 0)",
	)
	execSQL("DROP TRIGGER IF EXISTS favourites_global_after_insert")
	execSQL("DROP TRIGGER IF EXISTS favourites_global_after_recover")
	execSQL("DROP TRIGGER IF EXISTS favourites_global_after_remove")
	execSQL(
		"""CREATE TRIGGER favourites_global_after_insert
			AFTER INSERT ON favourites
			WHEN NEW.category_id != 0 AND NEW.deleted_at = 0
			BEGIN
				INSERT OR IGNORE INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at)
				VALUES (
					NEW.manga_id,
					0,
					0,
					NEW.pinned,
					NEW.created_at,
					0
				);
				UPDATE favourites SET deleted_at = 0
				WHERE manga_id = NEW.manga_id AND category_id = 0;
			END""",
	)
	execSQL(
		"""CREATE TRIGGER favourites_global_after_recover
			AFTER UPDATE OF deleted_at ON favourites
			WHEN NEW.category_id != 0 AND NEW.deleted_at = 0
			BEGIN
				INSERT OR IGNORE INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at)
				VALUES (NEW.manga_id, 0, 0, NEW.pinned, NEW.created_at, 0);
				UPDATE favourites SET deleted_at = 0 WHERE manga_id = NEW.manga_id AND category_id = 0;
			END""",
	)
}
