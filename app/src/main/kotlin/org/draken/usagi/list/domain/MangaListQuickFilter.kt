package org.draken.usagi.list.domain

import androidx.collection.ArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.draken.usagi.core.model.toChipModel
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.list.ui.model.QuickFilter
import tsuki.util.suspendlazy.getOrNull
import tsuki.util.suspendlazy.suspendLazy

abstract class MangaListQuickFilter(
	private val settings: AppSettings,
) : QuickFilterListener {
	private val appliedFilter = MutableStateFlow<Set<ListFilterOption>>(emptySet())
	private val availableFilterOptions =
		suspendLazy {
			getAvailableFilterOptions()
		}

	val appliedOptions
		get() = appliedFilter.asStateFlow()

	suspend fun availableOptions(): List<ListFilterOption> = availableFilterOptions.getOrNull().orEmpty()

	fun retainOptions(options: Collection<ListFilterOption>) {
		val available = options.toHashSet()
		appliedFilter.value = appliedFilter.value.filterTo(ArraySet()) { option -> option in available }
	}

	override fun setFilterOption(
		option: ListFilterOption,
		isApplied: Boolean,
	) {
		appliedFilter.value =
			ArraySet(appliedFilter.value).also {
				if (isApplied) {
					it.addNoConflicts(option)
				} else {
					it.remove(option)
				}
			}
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		appliedFilter.value =
			ArraySet(appliedFilter.value).also {
				if (option in it) {
					it.remove(option)
				} else {
					it.addNoConflicts(option)
				}
			}
	}

	override fun clearFilter() {
		appliedFilter.value = emptySet()
	}

	suspend fun filterItem(selectedOptions: Set<ListFilterOption>): QuickFilter? {
		if (!settings.isQuickFilterEnabled) {
			return null
		}
		val availableOptions =
			availableOptions()
				.map { option ->
					option.toChipModel(isChecked = option in selectedOptions)
				}
		return if (availableOptions.isNotEmpty()) {
			QuickFilter(availableOptions)
		} else {
			null
		}
	}

	protected abstract suspend fun getAvailableFilterOptions(): List<ListFilterOption>

	private fun ArraySet<ListFilterOption>.addNoConflicts(option: ListFilterOption) {
		add(option)
		if (option is ListFilterOption.Inverted) {
			remove(option.option)
		} else {
			removeIf { it is ListFilterOption.Inverted && it.option == option }
		}
	}
}
