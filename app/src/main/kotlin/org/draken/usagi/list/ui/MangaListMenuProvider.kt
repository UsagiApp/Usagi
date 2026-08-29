package org.draken.usagi.list.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import org.draken.usagi.R
import org.draken.usagi.core.nav.router
import org.draken.usagi.favourites.ui.FavouritesOptionsHost
import org.draken.usagi.favourites.ui.list.FavouritesListFragment
import org.draken.usagi.history.ui.HistoryListFragment
import org.draken.usagi.list.ui.config.FavoritesOptionsMode
import org.draken.usagi.list.ui.config.ListConfigSection
import org.draken.usagi.suggestions.ui.SuggestionsFragment
import org.draken.usagi.tracker.ui.updates.UpdatesFragment

class MangaListMenuProvider(
	private val fragment: Fragment,
) : MenuProvider {
	override fun onCreateMenu(
		menu: Menu,
		menuInflater: MenuInflater,
	) {
		menuInflater.inflate(R.menu.opt_list, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
		when (menuItem.itemId) {
			R.id.action_list_mode -> {
				if (fragment is FavouritesListFragment) {
					when (val parent = fragment.parentFragment) {
						is FavouritesOptionsHost -> {
							parent.showFavouritesOptions()
						}

						null -> {
							fragment.router.showListConfigSheet(
								ListConfigSection.Favorites(
									categoryId = fragment.categoryId,
									mode = FavoritesOptionsMode.LIST_ONLY,
								),
							)
						}

						else -> {
							error("Unsupported Favorites list host: ${parent::class.java.name}")
						}
					}
				} else {
					val section: ListConfigSection =
						when (fragment) {
							is HistoryListFragment -> ListConfigSection.History
							is SuggestionsFragment -> ListConfigSection.Suggestions
							is UpdatesFragment -> ListConfigSection.Updated
							else -> ListConfigSection.General
						}
					fragment.router.showListConfigSheet(section)
				}
				true
			}

			else -> {
				false
			}
		}
}
