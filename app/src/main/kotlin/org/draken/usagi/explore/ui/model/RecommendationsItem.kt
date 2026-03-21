package org.draken.usagi.explore.ui.model

import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.MangaCompactListModel

data class RecommendationsItem(
	val manga: List<MangaCompactListModel>
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is RecommendationsItem
	}
}
