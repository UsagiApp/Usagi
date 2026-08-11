package org.draken.usagi.details.domain

import org.draken.usagi.core.model.FavouriteCategory

data class FavouriteDetailsState(
	val isFavorite: Boolean,
	val categories: Set<FavouriteCategory>,
) {
	val labelMode: FavouriteDetailsLabelMode
		get() =
			when {
				!isFavorite -> FavouriteDetailsLabelMode.ADD_TO_FAVORITES
				categories.isEmpty() -> FavouriteDetailsLabelMode.ALL_FAVORITES
				else -> FavouriteDetailsLabelMode.MANUAL_CATEGORIES
			}
}

enum class FavouriteDetailsLabelMode {
	ADD_TO_FAVORITES,
	ALL_FAVORITES,
	MANUAL_CATEGORIES,
}
