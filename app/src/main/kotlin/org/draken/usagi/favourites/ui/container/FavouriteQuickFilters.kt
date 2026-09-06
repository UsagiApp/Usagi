package org.draken.usagi.favourites.ui.container

import androidx.core.view.isVisible
import org.draken.usagi.R
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.dialog.SearchableSelectionItem
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.favourites.ui.FavouritesPageUiState
import org.draken.usagi.favourites.ui.selection.FavouriteSearchableSelectionDialog
import org.draken.usagi.list.domain.ListFilterOption

/** Renders transient filters without taking ownership of the active page's state. */
internal class FavouriteQuickFilters(
	private val view: ChipsView,
) {
	fun render(
		state: FavouritesPageUiState,
		apply: (Set<ListFilterOption>) -> Unit,
	) {
		val options = state.availableRuleOptions
		val selected = state.selectedRuleOptions
		val models =
			options
				.filterNot { it is ListFilterOption.Source || it is ListFilterOption.Tag }
				.map { option ->
					ChipsView.ChipModel(
						title = title(option),
						icon = option.iconResId,
						isChecked = option in selected,
						data = option,
					)
				}.toMutableList()
		val groups =
			listOf(
				Triple(R.string.smart_folder_sources, R.drawable.ic_manga_source, options.filterIsInstance<ListFilterOption.Source>()),
				Triple(R.string.genres, R.drawable.ic_tag, options.filterIsInstance<ListFilterOption.Tag>()),
			)
		groups.filter { it.third.isNotEmpty() }.forEach { (title, icon, values) ->
			models +=
				ChipsView.ChipModel(
					titleResId = title,
					icon = icon,
					isDropdown = true,
					isChecked = values.any { it in selected },
					data = title,
				)
		}
		view.onChipClickListener =
			ChipsView.OnChipClickListener { _, data ->
				if (data is ListFilterOption) {
					val selection = FavouriteFilterSelectionState(selected)
					selection.setSelected(data, data !in selected)
					apply(selection.selection())
				} else {
					val group = groups.firstOrNull { it.first == data } ?: return@OnChipClickListener
					FavouriteSearchableSelectionDialog.show(
						context = view.context,
						titleResId = group.first,
						items = group.third.map { SearchableSelectionItem(id = it, title = title(it)) },
						selected = group.third.filterTo(linkedSetOf()) { it in selected },
					) { values ->
						apply((selected - group.third.toSet()) + values)
					}
				}
			}
		view.setChips(models)
		view.isVisible = models.isNotEmpty()
	}

	private fun title(option: ListFilterOption): String =
		when (option) {
			is ListFilterOption.Source -> option.mangaSource.getTitle(view.context)
			else -> option.titleText?.toString() ?: view.context.getString(option.titleResId)
		}
}
