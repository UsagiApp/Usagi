package org.draken.usagi.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.draken.usagi.favourites.data.SmartFolderEntity

@Serializable
class SmartFolderBackup(
	@SerialName("smart_folder_id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("list_order") val listOrder: String,
	@SerialName("rules") val rules: String,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("deleted_at") val deletedAt: Long = 0L,
) {
	constructor(entity: SmartFolderEntity) : this(
		id = entity.id,
		title = entity.title,
		sortKey = entity.sortKey,
		listOrder = entity.listOrder,
		rules = entity.rules,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		deletedAt = entity.deletedAt,
	)

	fun toEntity() =
		SmartFolderEntity(
			id = id,
			title = title,
			sortKey = sortKey,
			listOrder = listOrder,
			rules = rules,
			createdAt = createdAt,
			updatedAt = updatedAt,
			deletedAt = deletedAt,
		)
}
