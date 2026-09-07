package org.draken.usagi.favourites.ui.categories.adapter

import org.draken.usagi.favourites.domain.model.Cover
import org.draken.usagi.list.ui.model.ListModel

data class AllCategoriesListModel(
	val mangaCount: Int,
	val covers: List<Cover>,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is AllCategoriesListModel
}
