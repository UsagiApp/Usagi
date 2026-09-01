package org.draken.usagi.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration28To29 : Migration(28, 29) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS smart_folders (
				smart_folder_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				title TEXT NOT NULL,
				sort_key INTEGER NOT NULL,
				list_order TEXT NOT NULL,
				rules TEXT NOT NULL,
				created_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				deleted_at INTEGER NOT NULL
			)""",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS index_smart_folders_deleted_at_sort_key " +
				"ON smart_folders (deleted_at, sort_key)",
		)
	}
}
