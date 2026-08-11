package org.draken.usagi.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.draken.usagi.favourites.data.createAllFavoritesInfrastructure

class Migration30To31 : Migration(30, 31) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.createAllFavoritesInfrastructure()
	}
}
