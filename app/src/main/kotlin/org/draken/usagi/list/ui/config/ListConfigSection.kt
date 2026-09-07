package org.draken.usagi.list.ui.config

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed interface ListConfigSection : Parcelable {
	@Parcelize
	data object History : ListConfigSection

	@Parcelize
	data object General : ListConfigSection

	@Parcelize
	data class Favorites(
		val categoryId: Long,
		val mode: FavoritesOptionsMode,
	) : ListConfigSection {
		val requiresOrganizerHost: Boolean
			get() = mode == FavoritesOptionsMode.ORGANIZER
	}

	@Parcelize
	data object Suggestions : ListConfigSection

	@Parcelize
	data object Updated : ListConfigSection
}

enum class FavoritesOptionsMode {
	ORGANIZER,
	LIST_ONLY,
}
