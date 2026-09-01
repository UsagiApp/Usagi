package org.draken.usagi.favourites.ui.container

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.draken.usagi.core.util.Event
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.ui.FavouritesPage
import org.draken.usagi.favourites.ui.FavouritesPageUiState
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FavouritesPageBindingTest {
	@Test
	fun `binding a new page stops updates from the previous page`() =
		runTest {
			Dispatchers.setMain(StandardTestDispatcher(testScheduler))
			try {
				val renderedStages = mutableListOf<FavouriteStage>()
				val ownerA = StartedLifecycleOwner()
				val ownerB = StartedLifecycleOwner()
				val pageA = FakeFavouritesPage(FavouriteStage.ALL)
				val pageB = FakeFavouritesPage(FavouriteStage.READING)
				val binding = FavouritesPageBinding(this, { renderedStages += it.selectedStage }, {})

				binding.bind(pageA, ownerA)
				advanceUntilIdle()
				binding.bind(pageB, ownerB)
				advanceUntilIdle()
				pageA.emitStage(FavouriteStage.COMPLETED)
				pageB.emitStage(FavouriteStage.WAITING)
				advanceUntilIdle()

				assertEquals(FavouriteStage.WAITING, renderedStages.last())
				assertFalse(FavouriteStage.COMPLETED in renderedStages)
				binding.clear()
			} finally {
				Dispatchers.resetMain()
			}
		}

	@Test
	fun `destroying a page stops its updates`() =
		runTest {
			Dispatchers.setMain(StandardTestDispatcher(testScheduler))
			try {
				val renderedStages = mutableListOf<FavouriteStage>()
				val owner = StartedLifecycleOwner()
				val page = FakeFavouritesPage(FavouriteStage.ALL)
				val binding = FavouritesPageBinding(this, { renderedStages += it.selectedStage }, {})

				binding.bind(page, owner)
				advanceUntilIdle()
				owner.destroy()
				advanceUntilIdle()
				page.emitStage(FavouriteStage.COMPLETED)
				advanceUntilIdle()

				assertEquals(listOf(FavouriteStage.ALL), renderedStages)
				binding.clear()
				binding.clear()
			} finally {
				Dispatchers.resetMain()
			}
		}

	private class StartedLifecycleOwner : LifecycleOwner {
		private val registry = LifecycleRegistry.createUnsafe(this)

		override val lifecycle: Lifecycle = registry

		init {
			registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
			registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
		}

		fun destroy() {
			registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
			registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
		}
	}

	private class FakeFavouritesPage(
		initialStage: FavouriteStage,
	) : FavouritesPage {
		private val mutableUiState = MutableStateFlow(pageState(initialStage))
		private val mutableOrganizerRefreshResults =
			MutableStateFlow<Event<FavouriteOrganizerRefreshResult>?>(null)
		private val mutableSortOrder = MutableStateFlow<ListSortOrder?>(null)

		override val uiState = mutableUiState.asStateFlow()
		override val organizerRefreshResults = mutableOrganizerRefreshResults.asStateFlow()
		override val sortOrder = mutableSortOrder.asStateFlow()

		fun emitStage(stage: FavouriteStage) {
			mutableUiState.value = mutableUiState.value.copy(selectedStage = stage)
		}

		override fun setStage(stage: FavouriteStage) = Unit

		override fun setRuleOption(
			option: ListFilterOption,
			isApplied: Boolean,
		) = Unit

		override fun clearRuleOptions() = Unit

		override fun setSortOrder(sortOrder: ListSortOrder) {
			mutableSortOrder.value = sortOrder
		}
	}

	companion object {
		private fun pageState(stage: FavouriteStage) =
			FavouritesPageUiState(
				selectedStage = stage,
				stageCounts = null,
				availableRuleOptions = emptyList(),
				selectedRuleOptions = emptySet(),
			)
	}
}
