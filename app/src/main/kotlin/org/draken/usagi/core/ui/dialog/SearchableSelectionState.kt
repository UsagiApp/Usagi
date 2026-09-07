package org.draken.usagi.core.ui.dialog

data class SearchableSelectionItem<T>(
	val id: T,
	val title: String,
	val subtitle: String? = null,
)

class SearchableSelectionState<T>(
	private val items: List<SearchableSelectionItem<T>>,
	initiallySelected: Set<T>,
) {
	private val selected = initiallySelected.toMutableSet()

	fun filtered(query: String): List<SearchableSelectionItem<T>> {
		val normalizedQuery = query.trim()
		if (normalizedQuery.isEmpty()) return items
		return items.filter { item ->
			item.title.contains(normalizedQuery, ignoreCase = true) ||
				item.subtitle?.contains(normalizedQuery, ignoreCase = true) == true
		}
	}

	fun setSelected(
		id: T,
		isSelected: Boolean,
	) {
		if (isSelected) selected += id else selected -= id
	}

	fun isSelected(id: T): Boolean = id in selected

	fun clear() = selected.clear()

	fun selection(): Set<T> = selected.toSet()
}
