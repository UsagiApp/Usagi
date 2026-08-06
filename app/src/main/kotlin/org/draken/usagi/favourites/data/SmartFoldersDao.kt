package org.draken.usagi.favourites.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartFoldersDao {
	@Query("SELECT * FROM smart_folders WHERE smart_folder_id = :id")
	suspend fun find(id: Long): SmartFolderEntity?

	@Query("SELECT * FROM smart_folders WHERE deleted_at = 0 ORDER BY sort_key, created_at")
	fun observeAll(): Flow<List<SmartFolderEntity>>

	@Query("SELECT * FROM smart_folders WHERE deleted_at = 0 ORDER BY sort_key, created_at")
	suspend fun findAllForBackup(): List<SmartFolderEntity>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(entity: SmartFolderEntity): Long

	@Upsert
	suspend fun upsert(entity: SmartFolderEntity)

	@Query("UPDATE smart_folders SET sort_key = :sortKey, updated_at = :updatedAt WHERE smart_folder_id = :id")
	suspend fun updateSortKey(
		id: Long,
		sortKey: Int,
		updatedAt: Long,
	)

	@Query("UPDATE smart_folders SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE smart_folder_id = :id")
	suspend fun setDeletedAt(
		id: Long,
		deletedAt: Long,
	)

	@Query("SELECT MAX(sort_key) FROM smart_folders WHERE deleted_at = 0")
	suspend fun getMaxSortKey(): Int?
}
