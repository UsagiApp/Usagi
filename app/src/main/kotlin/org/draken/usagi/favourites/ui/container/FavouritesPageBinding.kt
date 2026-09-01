package org.draken.usagi.favourites.ui.container

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.ui.FavouritesPage
import org.draken.usagi.favourites.ui.FavouritesPageUiState

internal class FavouritesPageBinding(
	private val scope: CoroutineScope,
	private val render: (FavouritesPageUiState) -> Unit,
	private val showRefreshResult: suspend (FavouriteOrganizerRefreshResult) -> Unit,
) {
	private var job: Job? = null

	fun bind(
		page: FavouritesPage,
		lifecycleOwner: LifecycleOwner,
	) {
		clear()
		job =
			scope.launch {
				lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
					launch {
						page.uiState.collect { state -> render(state) }
					}
					launch {
						page.organizerRefreshResults.collect { event ->
							event?.consume { result -> showRefreshResult(result) }
						}
					}
				}
			}
	}

	fun clear() {
		job?.cancel()
		job = null
	}
}
