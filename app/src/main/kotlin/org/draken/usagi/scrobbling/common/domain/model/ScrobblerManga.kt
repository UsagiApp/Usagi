package org.draken.usagi.scrobbling.common.domain.model

import org.draken.usagi.list.ui.model.ListModel

data class ScrobblerManga(
	val id: Long,
	val name: String,
	val altName: String?,
	val cover: String?,
	val url: String,
	val isBestMatch: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ScrobblerManga && other.id == id
	}

	override fun toString(): String {
		return "ScrobblerManga #$id \"$name\" $url"
	}
}
