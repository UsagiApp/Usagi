package org.draken.usagi.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.draken.usagi.favourites.data.createAllFavoritesInfrastructure

class Migration29To30 : Migration(29, 30) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.createAllFavoritesInfrastructure()
	}
}
