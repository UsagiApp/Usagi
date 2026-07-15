package org.draken.usagi.filter.ui.external

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.model.titleRes
import org.draken.usagi.filter.ui.FilterCoordinator
import org.draken.usagi.filter.ui.FilterCoordinator.SortState
import org.draken.usagi.filter.ui.external.model.SortOption

@HiltViewModel(assistedFactory = SortViewModel.Factory::class)
class SortViewModel @AssistedInject constructor(
	@Assisted private val filter: FilterCoordinator,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private var sortState: SortState? = null
	private val contentFlow = MutableStateFlow<List<SortOption>>(emptyList())

	val content: StateFlow<List<SortOption>> = contentFlow

	init {
		launchLoadingJob(Dispatchers.Default) {
			sortState = filter.loadSortState()
			rebuild()
		}
	}

	fun onOptionClick(model: SortOption) {
		when (val state = sortState) {
			is SortState.Source -> {
				val index = model.id
				if (state.supportsDirection) {
					// Tapping the selected option flips direction; a new option keeps ascending.
					val ascending = if (index == state.selectedIndex) !state.isAscending else true
					filter.applySourceSort(index, ascending)
					sortState = state.copy(selectedIndex = index, isAscending = ascending)
				} else {
					filter.applySourceSort(index, false)
					sortState = state.copy(selectedIndex = index)
				}
				rebuild()
			}

			is SortState.Native -> {
				val order = state.options.firstOrNull { it.ordinal == model.id } ?: return
				filter.setSortOrder(order)
				sortState = state.copy(selected = order)
				rebuild()
			}

			null -> Unit
		}
	}

	private fun rebuild() {
		contentFlow.value = when (val state = sortState) {
			is SortState.Source -> state.options.mapIndexed { i, label ->
				SortOption(
					id = i,
					title = label,
					indicator = when {
						i != state.selectedIndex -> SortOption.Indicator.NONE
						!state.supportsDirection -> SortOption.Indicator.SELECTED
						state.isAscending -> SortOption.Indicator.ASCENDING
						else -> SortOption.Indicator.DESCENDING
					},
				)
			}

			is SortState.Native -> state.options.map { order ->
				SortOption(
					id = order.ordinal,
					title = context.getString(order.titleRes),
					indicator = if (order == state.selected) {
						SortOption.Indicator.SELECTED
					} else {
						SortOption.Indicator.NONE
					},
				)
			}

			null -> emptyList()
		}
	}

	@AssistedFactory
	interface Factory {
		fun create(filter: FilterCoordinator): SortViewModel
	}
}
