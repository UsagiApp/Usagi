package org.koitharu.kotatsu.sync.data.model

import android.database.Cursor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.util.ext.buildContentValues
import org.koitharu.kotatsu.core.util.ext.getBoolean

@Serializable
data class MangaTagSyncDto(
	@SerialName("tag_id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("key") val key: String,
	@SerialName("source") val source: String,
    @SerialName("pinned") val isPinned: Boolean = false,
) {

	constructor(cursor: Cursor) : this(
		id = cursor.getLong(cursor.getColumnIndexOrThrow("tag_id")),
		title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
		key = cursor.getString(cursor.getColumnIndexOrThrow("key")),
		source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
        isPinned = cursor.getBoolean(cursor.getColumnIndexOrThrow("pinned")),
	)

	fun toContentValues() = buildContentValues(4) {
		put("tag_id", id)
		put("title", title)
		put("key", key)
		put("source", source)
        put("pinned", isPinned)
	}
}
