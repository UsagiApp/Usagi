package org.draken.usagi.favourites.ui.categories.select

import com.google.android.material.checkbox.MaterialCheckBox
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.details.domain.FavouriteDetailsLabelMode
import org.draken.usagi.details.domain.FavouriteDetailsState
import org.draken.usagi.favourites.ui.categories.select.model.FavoriteSelectionAction
import org.draken.usagi.favourites.ui.categories.select.model.FavoriteSelectionTarget
import org.draken.usagi.favourites.ui.categories.select.model.buildFavoriteSelectionItems
import org.draken.usagi.list.domain.ListSortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FavoriteSelectionStateTest {
	@Test
	fun noEditableCategoriesStillShowsUncheckedAllFavoritesTarget() {
		val items =
			buildFavoriteSelectionItems(
				categories = emptyList(),
				globalMembershipCount = 0,
				categoryMembershipCounts = emptyMap(),
				mangaCount = 1,
				isTrackerEnabled = false,
			)

		assertEquals(1, items.size)
		assertEquals(FavoriteSelectionTarget.AllFavorites, items.single().target)
		assertEquals(MaterialCheckBox.STATE_UNCHECKED, items.single().checkedState)
		assertEquals(FavoriteSelectionAction.AddToFavorites, items.single().actionFor(isChecked = true))
	}

	@Test
	fun activeGlobalMembershipIsCheckedWithoutEditableCategories() {
		val item =
			buildFavoriteSelectionItems(
				categories = emptyList(),
				globalMembershipCount = 1,
				categoryMembershipCounts = emptyMap(),
				mangaCount = 1,
				isTrackerEnabled = false,
			).single()

		assertEquals(MaterialCheckBox.STATE_CHECKED, item.checkedState)
	}

	@Test
	fun allFavoritesAndManualTargetsProduceIndependentRemovalActions() {
		val category = category(7)
		val items =
			buildFavoriteSelectionItems(
				categories = listOf(category),
				globalMembershipCount = 1,
				categoryMembershipCounts = mapOf(7L to 1),
				mangaCount = 1,
				isTrackerEnabled = false,
			)

		assertEquals(FavoriteSelectionAction.RemoveFromFavorites, items[0].actionFor(isChecked = false))
		assertEquals(FavoriteSelectionAction.RemoveFromCategory(7), items[1].actionFor(isChecked = false))
	}

	@Test
	fun detailsUsesGlobalMembershipWhenManualCategoriesAreEmpty() {
		assertEquals(
			FavouriteDetailsLabelMode.ALL_FAVORITES,
			FavouriteDetailsState(isFavorite = true, categories = emptySet()).labelMode,
		)
		assertEquals(
			FavouriteDetailsLabelMode.ADD_TO_FAVORITES,
			FavouriteDetailsState(isFavorite = false, categories = emptySet()).labelMode,
		)
	}

	private fun category(id: Long) =
		FavouriteCategory(
			id = id,
			title = "Category $id",
			sortKey = id.toInt(),
			order = ListSortOrder.NEWEST,
			createdAt = Instant.EPOCH,
			isTrackingEnabled = false,
			isVisibleInLibrary = true,
		)
}
