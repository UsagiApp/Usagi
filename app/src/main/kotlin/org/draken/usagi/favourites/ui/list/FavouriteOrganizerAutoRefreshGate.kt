package org.draken.usagi.favourites.ui.list

import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.domain.requiresSourceRefresh

internal class FavouriteOrganizerAutoRefreshGate {
	private var refreshRequested = false

	fun shouldRefresh(stage: FavouriteStage): Boolean {
		if (refreshRequested || !stage.requiresSourceRefresh) return false
		refreshRequested = true
		return true
	}
}
