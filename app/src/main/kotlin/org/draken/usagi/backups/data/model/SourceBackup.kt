package org.draken.usagi.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.draken.usagi.core.db.entity.MangaSourceEntity
import org.draken.usagi.core.model.toBackupSourceName

@Serializable
class SourceBackup(
	@SerialName("source") val source: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("used_at") val lastUsedAt: Long,
	@SerialName("added_in") val addedIn: Int,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("enabled") val isEnabled: Boolean = true, // for compatibility purposes, should be only true
) {

	constructor(entity: MangaSourceEntity) : this(
		source = entity.source.toBackupSourceName(),
		sortKey = entity.sortKey,
		lastUsedAt = entity.lastUsedAt,
		addedIn = entity.addedIn,
		isPinned = entity.isPinned,
		isEnabled = entity.isEnabled,
	)

	fun toEntity() = MangaSourceEntity(
		source = source,
		isEnabled = isEnabled,
		sortKey = sortKey,
		addedIn = addedIn,
		lastUsedAt = lastUsedAt,
		isPinned = isPinned,
		cfState = 0,
	)
}
