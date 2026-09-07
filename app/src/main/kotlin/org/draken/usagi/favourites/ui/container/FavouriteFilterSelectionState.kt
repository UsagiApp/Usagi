package org.draken.usagi.favourites.ui.container

import org.draken.usagi.list.domain.ListFilterOption

class FavouriteFilterSelectionState(
	initialSelection: Set<ListFilterOption>,
) {
	private val selected = initialSelection.toMutableSet()

	fun setSelected(
		option: ListFilterOption,
		isSelected: Boolean,
	) {
		if (isSelected) {
			selected += option
			option.conflictingContentFilter()?.let(selected::remove)
		} else {
			selected -= option
		}
	}

	fun retainAvailable(available: Collection<ListFilterOption>) {
		selected.retainAll(available.toSet())
	}

	fun selection(): Set<ListFilterOption> = selected.toSet()

	private fun ListFilterOption.conflictingContentFilter(): ListFilterOption? =
		when (this) {
			ListFilterOption.SFW -> ListFilterOption.Macro.NSFW
			ListFilterOption.Macro.NSFW -> ListFilterOption.SFW
			else -> null
		}
}
