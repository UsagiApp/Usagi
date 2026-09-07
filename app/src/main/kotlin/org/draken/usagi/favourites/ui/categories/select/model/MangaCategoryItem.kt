package org.draken.usagi.favourites.ui.categories.select.model

import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.checkbox.MaterialCheckBox.CheckedState
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.list.ui.ListModelDiffCallback
import org.draken.usagi.list.ui.model.ListModel

data class MangaCategoryItem(
	val target: FavoriteSelectionTarget,
	@CheckedState val checkedState: Int,
	val isTrackerEnabled: Boolean,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is MangaCategoryItem && other.target.id == target.id

	override fun getChangePayload(previousState: ListModel): Any? =
		if (previousState is MangaCategoryItem && previousState.checkedState != checkedState) {
			ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
		} else {
			super.getChangePayload(previousState)
		}

	fun actionFor(isChecked: Boolean): FavoriteSelectionAction =
		when (val selectionTarget = target) {
			FavoriteSelectionTarget.AllFavorites -> {
				if (isChecked) FavoriteSelectionAction.AddToFavorites else FavoriteSelectionAction.RemoveFromFavorites
			}

			is FavoriteSelectionTarget.Category -> {
				if (isChecked) {
					FavoriteSelectionAction.AddToCategory(selectionTarget.value.id)
				} else {
					FavoriteSelectionAction.RemoveFromCategory(selectionTarget.value.id)
				}
			}
		}
}

sealed interface FavoriteSelectionTarget {
	val id: Long

	data object AllFavorites : FavoriteSelectionTarget {
		override val id: Long = 0
	}

	data class Category(
		val value: FavouriteCategory,
	) : FavoriteSelectionTarget {
		override val id: Long = value.id
	}
}

sealed interface FavoriteSelectionAction {
	data object AddToFavorites : FavoriteSelectionAction

	data object RemoveFromFavorites : FavoriteSelectionAction

	data class AddToCategory(
		val categoryId: Long,
	) : FavoriteSelectionAction

	data class RemoveFromCategory(
		val categoryId: Long,
	) : FavoriteSelectionAction
}

fun buildFavoriteSelectionItems(
	categories: List<FavouriteCategory>,
	globalMembershipCount: Int,
	categoryMembershipCounts: Map<Long, Int>,
	mangaCount: Int,
	isTrackerEnabled: Boolean,
): List<MangaCategoryItem> =
	buildList(categories.size + 1) {
		add(
			MangaCategoryItem(
				target = FavoriteSelectionTarget.AllFavorites,
				checkedState = checkedState(globalMembershipCount, mangaCount),
				isTrackerEnabled = false,
			),
		)
		categories.forEach { category ->
			add(
				MangaCategoryItem(
					target = FavoriteSelectionTarget.Category(category),
					checkedState = checkedState(categoryMembershipCounts[category.id] ?: 0, mangaCount),
					isTrackerEnabled = isTrackerEnabled,
				),
			)
		}
	}

@CheckedState
private fun checkedState(
	membershipCount: Int,
	mangaCount: Int,
): Int =
	when (membershipCount) {
		0 -> MaterialCheckBox.STATE_UNCHECKED
		mangaCount -> MaterialCheckBox.STATE_CHECKED
		else -> MaterialCheckBox.STATE_INDETERMINATE
	}
