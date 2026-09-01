package org.draken.usagi.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration31To32 : Migration(31, 32) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("DROP TRIGGER IF EXISTS favourites_global_after_remove")
	}
}
