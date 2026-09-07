package org.draken.usagi.favourites.ui.container

import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.SmartFolderRulesError
import org.draken.usagi.list.ui.model.ListModel

data class FavouriteTabModel(
	val scope: FavouriteScope,
	val title: String?,
	val rules: SmartFolderRules? = null,
	val rulesError: SmartFolderRulesError? = null,
) : ListModel {
	val id: Long
		get() =
			when (scope) {
				FavouriteScope.All -> 0L
				is FavouriteScope.Category -> scope.id
				is FavouriteScope.SmartFolder -> -scope.id
			}

	override fun areItemsTheSame(other: ListModel): Boolean = other is FavouriteTabModel && other.scope == scope
}
