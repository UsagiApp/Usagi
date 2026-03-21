package org.draken.usagi.suggestions.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import org.draken.usagi.core.db.entity.MangaEntity
import org.draken.usagi.core.db.entity.MangaTagsEntity
import org.draken.usagi.core.db.entity.TagEntity

data class SuggestionWithManga(
	@Embedded val suggestion: SuggestionEntity,
	@Relation(
		parentColumn = "manga_id",
		entityColumn = "manga_id"
	)
	val manga: MangaEntity,
	@Relation(
		parentColumn = "manga_id",
		entityColumn = "tag_id",
		associateBy = Junction(MangaTagsEntity::class)
	)
	val tags: List<TagEntity>,
)