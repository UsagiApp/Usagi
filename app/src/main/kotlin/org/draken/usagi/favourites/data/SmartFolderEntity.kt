package org.draken.usagi.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.draken.usagi.core.db.TABLE_SMART_FOLDERS

@Entity(
	tableName = TABLE_SMART_FOLDERS,
	indices = [Index(value = ["deleted_at", "sort_key"])],
)
data class SmartFolderEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "smart_folder_id") val id: Long,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "list_order") val listOrder: String,
	@ColumnInfo(name = "rules") val rules: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
)
